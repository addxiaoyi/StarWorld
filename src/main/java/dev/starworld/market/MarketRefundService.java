package dev.starworld.market;

import dev.starworld.integration.TreasuryRefundPort;
import dev.starworld.logistics.LogisticsState.AuthoritySnapshot;
import dev.starworld.market.MarketState.*;
import dev.starworld.persistence.FairDispatchQueue;
import dev.starworld.persistence.WorldStore;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Bilateral consent, frozen cargo, durable reversal outbox, then atomic return of original goods. */
public final class MarketRefundService {
    public enum Status { REQUESTED, CANCELLED, PENDING, IN_FLIGHT, RETRY, REVIEW, MONEY_RETURNED, COMPLETED }
    public record Refund(UUID id,UUID purchaseId,String transactionId,Status status,int attempts,long nextAttempt,String detail,long createdPeriod){}
    public record Dispatch(Refund refund,Purchase purchase,String leaseToken){
        public TreasuryRefundPort.Request request(){return new TreasuryRefundPort.Request(refund.transactionId(),purchase.request());}
    }
    private static final String QUEUE="starworld:market-refund";
    private final WorldStore store;private final MarketService market;
    public MarketRefundService(WorldStore store,MarketService market)throws SQLException{
        this.store=Objects.requireNonNull(store);this.market=Objects.requireNonNull(market);
        store.tx(s->{FairDispatchQueue.initialize(s,QUEUE);
            s.execute("CREATE TABLE IF NOT EXISTS market_refund(id TEXT PRIMARY KEY,purchase TEXT NOT NULL UNIQUE REFERENCES market_purchase(id),transaction_id TEXT NOT NULL UNIQUE,state TEXT NOT NULL,actor TEXT NOT NULL,approver TEXT,created_period INTEGER NOT NULL,accepted_period INTEGER,attempts INTEGER NOT NULL DEFAULT 0,next_attempt INTEGER NOT NULL DEFAULT 0,lease TEXT,last_error TEXT NOT NULL DEFAULT '',refunded_at TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS market_refund_due ON market_refund(state,next_attempt,created_period,id)");return null;});
    }
    public Optional<Refund> refund(UUID id)throws SQLException{return store.query("SELECT * FROM market_refund WHERE id=?",this::read,id).stream().findFirst();}
    public List<Refund> refunds(UUID city,int limit,int offset)throws SQLException{page(limit,offset);return store.query("SELECT r.* FROM market_refund r JOIN market_purchase p ON p.id=r.purchase WHERE p.buyer_city=? OR p.seller_city=? ORDER BY r.created_period DESC,r.id LIMIT ? OFFSET ?",this::read,city,city,limit,offset);}
    public Refund request(UUID purchaseId,AuthoritySnapshot auth,UUID governedNation)throws SQLException{
        return store.tx(s->{Purchase p=market.purchase(purchaseId).orElseThrow();check(p,true,auth,governedNation);
            UUID id=UUID.nameUUIDFromBytes((market.namespace()+":refund:"+purchaseId).getBytes(StandardCharsets.UTF_8));
            Optional<Refund> existing=refund(id);if(existing.isPresent()&&existing.get().status()!=Status.CANCELLED)return existing.get();
            market.requireRefundable(p);
            if(existing.isEmpty())s.execute("INSERT INTO market_refund(id,purchase,transaction_id,state,actor,created_period) VALUES(?,?,?,'REQUESTED',?,?)",id,p.id(),"starworld:refund:"+market.namespace()+":"+id,auth.actor(),auth.period());
            else s.execute("UPDATE market_refund SET state='REQUESTED',actor=?,created_period=?,last_error='' WHERE id=?",auth.actor(),auth.period(),id);
            s.audit(auth.actor().toString(),"MARKET REFUND REQUEST "+id+" "+purchaseId);return refund(id).orElseThrow();});
    }
    public Refund accept(UUID id,AuthoritySnapshot auth,UUID governedNation)throws SQLException{
        return store.tx(s->{Refund refund=refund(id).orElseThrow();Purchase p=market.purchase(refund.purchaseId()).orElseThrow();check(p,false,auth,governedNation);
            if(refund.status()!=Status.REQUESTED){if(refund.status()==Status.CANCELLED)throw new IllegalArgumentException("退款申请已撤销");return refund;}
            market.holdRefund(p.id(),auth);
            s.execute("UPDATE market_refund SET state='PENDING',approver=?,accepted_period=?,next_attempt=0 WHERE id=?",auth.actor(),auth.period(),id);
            s.audit(auth.actor().toString(),"MARKET REFUND ACCEPT "+id);return refund(id).orElseThrow();});
    }
    public Refund cancel(UUID id,AuthoritySnapshot auth,UUID governedNation)throws SQLException{
        return store.tx(s->{Refund refund=refund(id).orElseThrow();Purchase p=market.purchase(refund.purchaseId()).orElseThrow();
            boolean buyer=governedNation.equals(p.buyerNation());check(p,buyer,auth,governedNation);
            if(refund.status()==Status.CANCELLED)return refund;
            if(refund.status()!=Status.REQUESTED)throw new IllegalArgumentException("双方已确认的退款不能解除货物冻结；请等待原凭据完成");
            s.execute("UPDATE market_refund SET state='CANCELLED' WHERE id=?",id);s.audit(auth.actor().toString(),"MARKET REFUND CANCEL "+id);return refund(id).orElseThrow();});
    }
    /** Explicit retry keeps the same approved request and immutable original payment. */
    public Refund retry(UUID id,AuthoritySnapshot auth,UUID governedNation)throws SQLException{
        return store.tx(s->{Refund refund=refund(id).orElseThrow();Purchase p=market.purchase(refund.purchaseId()).orElseThrow();check(p,governedNation.equals(p.buyerNation()),auth,governedNation);
            if(refund.status()!=Status.REVIEW&&refund.status()!=Status.RETRY)throw new IllegalArgumentException("当前退款不需要手动重试");
            s.execute("UPDATE market_refund SET state='PENDING',next_attempt=0,lease=NULL WHERE id=?",id);return refund(id).orElseThrow();});
    }
    public List<Dispatch> leaseDue(int limit,long now)throws SQLException{batch(limit,now);return store.tx(s->{
        List<Refund> fresh=s.query("SELECT * FROM market_refund WHERE state IN ('PENDING','RETRY','IN_FLIGHT') AND next_attempt=0 ORDER BY created_period,id LIMIT ?",this::read,limit);
        List<Refund> due=s.query("SELECT * FROM market_refund WHERE state IN ('PENDING','RETRY','IN_FLIGHT') AND next_attempt>0 AND next_attempt<=? ORDER BY next_attempt,created_period,id LIMIT ?",this::read,now,limit);
        List<Dispatch> result=new ArrayList<>();for(Refund refund:FairDispatchQueue.select(s,QUEUE,fresh,due,limit)){
            Purchase purchase=market.purchase(refund.purchaseId()).orElseThrow();if(purchase.status()!=PurchaseStatus.REFUNDING)throw new IllegalStateException("退款原购买未冻结");
            String lease=UUID.randomUUID().toString();s.execute("UPDATE market_refund SET state='IN_FLIGHT',lease=?,attempts=attempts+1,next_attempt=? WHERE id=?",lease,Math.addExact(now,MarketService.LEASE_MILLIS),refund.id());
            result.add(new Dispatch(refund(refund.id()).orElseThrow(),purchase,lease));}return List.copyOf(result);});}
    public boolean finish(Dispatch dispatch,TreasuryRefundPort.Delivery delivery,long now)throws SQLException{
        Objects.requireNonNull(dispatch);Objects.requireNonNull(delivery);if(now<0||!dispatch.refund().transactionId().equals(delivery.transactionId()))throw new IllegalArgumentException("退款回调凭据不符");
        return store.tx(s->{Refund refund=refund(dispatch.refund().id()).orElseThrow();String lease=s.query("SELECT COALESCE(lease,'') FROM market_refund WHERE id=?",r->r.getString(1),refund.id()).getFirst();
            if(refund.status()!=Status.IN_FLIGHT||!lease.equals(dispatch.leaseToken()))return false;
            Purchase p=market.purchase(refund.purchaseId()).orElseThrow();if(p.status()!=PurchaseStatus.REFUNDING)throw new IllegalStateException("退款货物未冻结");
            TreasuryRefundPort.Request request=new TreasuryRefundPort.Request(refund.transactionId(),p.request());
            Status next=switch(delivery.status()){case CONFIRMED->delivery.receipt()!=null&&delivery.receipt().matches(request)?Status.MONEY_RETURNED:Status.REVIEW;case CONFLICT->Status.REVIEW;case REJECTED,RETRY->Status.RETRY;};
            long delay=next==Status.MONEY_RETURNED?0:Math.min(300000L,1000L*(1L<<Math.min(8,refund.attempts())));
            s.execute("UPDATE market_refund SET state=?,lease=NULL,next_attempt=?,last_error=?,refunded_at=? WHERE id=?",next.name(),Math.addExact(now,delay),delivery.detail(),next==Status.MONEY_RETURNED?delivery.receipt().appliedAt().toString():null,refund.id());
            s.audit("market-refund","MARKET REFUND MONEY "+refund.id()+" "+next);return true;});
    }
    public List<Refund> leaseReturns(int limit,long now)throws SQLException{batch(limit,now);return store.tx(s->{
        List<Refund> rows=s.query("SELECT * FROM market_refund WHERE state='MONEY_RETURNED' AND next_attempt<=? ORDER BY next_attempt,created_period,id LIMIT ?",this::read,now,limit);
        for(Refund refund:rows)s.execute("UPDATE market_refund SET next_attempt=? WHERE id=?",Math.addExact(now,MarketService.LEASE_MILLIS),refund.id());return List.copyOf(rows);});}
    public Refund complete(UUID id,AuthoritySnapshot auth)throws SQLException{return store.tx(s->{Refund refund=refund(id).orElseThrow();
        if(refund.status()==Status.COMPLETED)return refund;if(refund.status()!=Status.MONEY_RETURNED)throw new IllegalArgumentException("原付款退款尚无匹配回执");
        market.returnRefunded(refund.purchaseId(),auth);s.execute("UPDATE market_refund SET state='COMPLETED',last_error='付款与原商品均已退还' WHERE id=?",id);return refund(id).orElseThrow();});}
    private void check(Purchase p,boolean buyer,AuthoritySnapshot auth,UUID governedNation)throws SQLException{
        market.refundOwners(p,auth);UUID city=buyer?p.buyerCity():p.sellerCity(),nation=buyer?p.buyerNation():p.sellerNation();
        if(!nation.equals(governedNation)||!auth.managedCities().contains(city))throw new IllegalArgumentException(buyer?"退款申请需要原买方国家创始人权限":"退款确认需要原卖方国家创始人权限");
    }
    private Refund read(ResultSet r)throws SQLException{return new Refund(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("purchase")),r.getString("transaction_id"),Status.valueOf(r.getString("state")),r.getInt("attempts"),r.getLong("next_attempt"),r.getString("last_error"),r.getLong("created_period"));}
    private void batch(int limit,long now){page(limit,0);if(limit>market.definitions().dispatchBatch()||now<0)throw new IllegalArgumentException("退款批次超限");}
    private static void page(int limit,int offset){if(limit<1||limit>100||offset<0)throw new IllegalArgumentException("退款每页1至100项");}
}

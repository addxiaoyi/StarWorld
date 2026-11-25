package dev.starworld.market;

import dev.starworld.integration.MarketTaxPort;
import dev.starworld.integration.TreasuryTransferPort;
import dev.starworld.logistics.LogisticsState.AuthoritySnapshot;
import dev.starworld.market.MarketState.*;
import dev.starworld.persistence.WorldStore;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** A short lived, immutable tariff quote. Goods and money move only when a founder accepts. */
public final class MarketQuoteService {
    public static final long LIFETIME_MILLIS=60_000;
    public static final int OPEN_LIMIT=20;
    public record Quote(UUID id,UUID listingId,UUID routeId,UUID buyerCity,UUID buyerNation,UUID sellerNation,UUID actor,long units,BigDecimal goodsTotal,BigDecimal taxAmount,long createdAt,long expiresAt,UUID purchaseId){
        public Quote {
            Objects.requireNonNull(id);Objects.requireNonNull(listingId);Objects.requireNonNull(routeId);Objects.requireNonNull(buyerCity);Objects.requireNonNull(buyerNation);Objects.requireNonNull(sellerNation);Objects.requireNonNull(actor);
            goodsTotal=TreasuryTransferPort.money(goodsTotal);taxAmount=MarketTaxPort.tax(taxAmount);
            if(units<1||buyerNation.equals(sellerNation)||taxAmount.compareTo(goodsTotal)>0||createdAt<0||expiresAt<=createdAt||expiresAt-createdAt>LIFETIME_MILLIS)throw new IllegalArgumentException("无效市场税费报价");TreasuryTransferPort.money(goodsTotal.add(taxAmount));
        }
        public BigDecimal total(){return goodsTotal.add(taxAmount);}
    }
    private final WorldStore store;private final MarketService market;
    public MarketQuoteService(WorldStore store,MarketService market){this.store=Objects.requireNonNull(store);this.market=Objects.requireNonNull(market);}
    public Optional<Quote> quote(UUID id)throws SQLException{return store.query("SELECT * FROM market_quote WHERE id=?",this::read,id).stream().findFirst();}
    public Quote prepare(UUID listingId,UUID route,UUID buyer,UUID nation,long units,MarketTaxPort.Quote tax,AuthoritySnapshot auth,long now)throws SQLException{
        Objects.requireNonNull(tax);if(now<0)throw new IllegalArgumentException("报价时间无效");
        return store.tx(s->{
            Listing l=market.quoteBasis(listingId,route,buyer,nation,units,auth);
            if(!tax.payerActor().equals(auth.actor())||!tax.collectingNation().equals(l.sellerNation())||tax.goodsTotal().compareTo(l.unitPrice().multiply(BigDecimal.valueOf(units)))!=0)throw new IllegalArgumentException("税费报价与商品或原国家不符");
            var existing=quote(tax.id());if(existing.isPresent()){
                Quote q=existing.get();if(!q.listingId().equals(listingId)||!q.routeId().equals(route)||!q.buyerCity().equals(buyer)||!q.buyerNation().equals(nation)||!q.actor().equals(auth.actor())||q.units()!=units||q.goodsTotal().compareTo(tax.goodsTotal())!=0||q.taxAmount().compareTo(tax.taxAmount())!=0)throw new IllegalArgumentException("报价ID已用于其他明细");return q;
            }
            if(s.query("SELECT COUNT(*) FROM (SELECT 1 FROM market_quote WHERE buyer_city=? AND purchase IS NULL AND expires_at>? LIMIT ?)",r->r.getInt(1),buyer,now,OPEN_LIMIT).getFirst()>=OPEN_LIMIT)throw new IllegalArgumentException("本城待确认报价已达20项，请等待到期");
            s.execute("INSERT INTO market_quote(id,listing,route,buyer_city,buyer_nation,seller_nation,actor,units,goods_total,tax_amount,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",tax.id(),listingId,route,buyer,nation,l.sellerNation(),auth.actor(),units,tax.goodsTotal().toPlainString(),tax.taxAmount().toPlainString(),now,Math.addExact(now,LIFETIME_MILLIS));
            return quote(tax.id()).orElseThrow();
        });
    }
    public Purchase accept(UUID id,UUID city,UUID nation,AuthoritySnapshot auth,long now)throws SQLException{
        if(now<0)throw new IllegalArgumentException("报价时间无效");
        return store.tx(s->{Quote q=quote(id).orElseThrow(()->new IllegalArgumentException("报价不存在或已到期"));
            if(!q.buyerCity().equals(city)||!q.buyerNation().equals(nation)||!auth.managedCities().contains(city)||!nation.equals(auth.cityNations().get(city)))throw new IllegalArgumentException("报价仅限原购买国家及城市确认");
            if(q.purchaseId()!=null){Purchase p=market.purchase(q.purchaseId()).orElseThrow();market.refundOwners(p,auth);return p;}
            if(now<q.createdAt()||now>=q.expiresAt())throw new IllegalArgumentException("报价已到期，请重新查看当前税费");
            Purchase p=market.buyQuoted(q,auth);s.execute("UPDATE market_quote SET purchase=? WHERE id=? AND purchase IS NULL",p.id(),q.id());
            s.audit(auth.actor().toString(),"MARKET QUOTE ACCEPT "+id+" goods="+q.goodsTotal()+" tariff="+q.taxAmount()+" total="+q.total());return p;
        });
    }
    /** Accepted quotes are permanent payment terms; only unaccepted expired previews can be removed. */
    public int cleanup(long now,int limit)throws SQLException{
        if(now<0||limit<1||limit>50)throw new IllegalArgumentException("报价清理批次超限");
        return store.tx(s->{List<UUID> expired=s.query("SELECT id FROM market_quote WHERE purchase IS NULL AND expires_at<=? ORDER BY expires_at,id LIMIT ?",r->UUID.fromString(r.getString(1)),now,limit);for(UUID id:expired)s.execute("DELETE FROM market_quote WHERE id=? AND purchase IS NULL",id);return expired.size();});
    }
    private Quote read(ResultSet r)throws SQLException{return new Quote(id(r,"id"),id(r,"listing"),id(r,"route"),id(r,"buyer_city"),id(r,"buyer_nation"),id(r,"seller_nation"),id(r,"actor"),r.getLong("units"),new BigDecimal(r.getString("goods_total")),new BigDecimal(r.getString("tax_amount")),r.getLong("created_at"),r.getLong("expires_at"),r.getString("purchase")==null?null:id(r,"purchase"));}
    private static UUID id(ResultSet r,String key)throws SQLException{return UUID.fromString(r.getString(key));}
}

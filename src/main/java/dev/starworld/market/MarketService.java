package dev.starworld.market;

import dev.starworld.integration.TreasuryTransferPort;
import dev.starworld.logistics.LogisticsService;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.market.MarketState.*;
import dev.starworld.persistence.WorldStore;
import dev.starworld.persistence.FairDispatchQueue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Durable physical listings and national money outbox, sharing the warehouse/transport transaction. */
public final class MarketService {
    public static final long LEASE_MILLIS=30_000;
    private static final String DISPATCH_QUEUE="starworld:market-payment";
    private final WorldStore store;private final LogisticsService logistics;private final MarketDefinitions definitions;private final String namespace;
    public MarketService(WorldStore store,LogisticsService logistics,MarketDefinitions definitions)throws SQLException{
        this.store=Objects.requireNonNull(store);this.logistics=Objects.requireNonNull(logistics);this.definitions=Objects.requireNonNull(definitions);
        namespace=store.tx(s->{
            FairDispatchQueue.initialize(s,DISPATCH_QUEUE);
            s.execute("CREATE TABLE IF NOT EXISTS market_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL,namespace TEXT NOT NULL)");
            s.execute("INSERT OR IGNORE INTO market_schema(id,version,namespace) VALUES(1,1,?)",UUID.randomUUID());
            int schema=s.query("SELECT version FROM market_schema WHERE id=1",r->r.getInt(1)).getFirst();
            if(schema<1||schema>4)throw new SQLException("Unsupported physical market schema");
            s.execute("CREATE TABLE IF NOT EXISTS market_listing(id TEXT PRIMARY KEY,seller_city TEXT NOT NULL REFERENCES city_state(id),seller_nation TEXT NOT NULL,item TEXT NOT NULL,units INTEGER NOT NULL CHECK(units>0),unit_price TEXT NOT NULL,state TEXT NOT NULL,created_period INTEGER NOT NULL,expires_period INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS market_listing_city ON market_listing(seller_city,state,created_period)");
            s.execute("CREATE INDEX IF NOT EXISTS market_listing_available ON market_listing(state,expires_period,id)");
            s.execute("CREATE TABLE IF NOT EXISTS market_purchase(id TEXT PRIMARY KEY,listing TEXT NOT NULL REFERENCES market_listing(id),buyer_city TEXT NOT NULL REFERENCES city_state(id),buyer_nation TEXT NOT NULL,seller_city TEXT NOT NULL,seller_nation TEXT NOT NULL,route TEXT NOT NULL,shipment TEXT NOT NULL UNIQUE,transaction_id TEXT NOT NULL UNIQUE,total TEXT NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_attempt INTEGER NOT NULL DEFAULT 0,lease TEXT,last_error TEXT NOT NULL DEFAULT '',created_period INTEGER NOT NULL,paid_at TEXT)");
            if(schema==1)s.execute("CREATE UNIQUE INDEX IF NOT EXISTS market_listing_purchase ON market_purchase(listing) WHERE state<>'CANCELLED'");
            s.execute("CREATE INDEX IF NOT EXISTS market_purchase_due ON market_purchase(state,next_attempt,created_period,id)");
            s.execute("CREATE INDEX IF NOT EXISTS market_purchase_fair_due ON market_purchase(next_attempt,created_period,id)");
            s.execute("CREATE INDEX IF NOT EXISTS market_purchase_buyer ON market_purchase(buyer_city,created_period)");
            s.execute("CREATE INDEX IF NOT EXISTS market_purchase_seller ON market_purchase(seller_city,created_period)");
            s.execute("CREATE INDEX IF NOT EXISTS market_purchase_route ON market_purchase(route)");
            s.execute("CREATE INDEX IF NOT EXISTS market_listing_public ON market_listing(state,created_period,id)");
            s.execute("CREATE TABLE IF NOT EXISTS market_expiry_check(listing TEXT PRIMARY KEY REFERENCES market_listing(id),period INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS market_history(listing TEXT PRIMARY KEY REFERENCES market_listing(id),buyer_city TEXT NOT NULL,period INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS market_clock(id INTEGER PRIMARY KEY CHECK(id=1),period INTEGER NOT NULL)");s.execute("INSERT OR IGNORE INTO market_clock(id,period) VALUES(1,-1)");
            s.execute("CREATE TABLE IF NOT EXISTS market_delivery_receipt(purchase TEXT PRIMARY KEY REFERENCES market_purchase(id),period INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS market_delivery_stat(scope TEXT NOT NULL,item TEXT NOT NULL,orders INTEGER NOT NULL,units INTEGER NOT NULL,gross TEXT NOT NULL,first_period INTEGER NOT NULL,last_period INTEGER NOT NULL,PRIMARY KEY(scope,item))");
            s.execute("CREATE TABLE IF NOT EXISTS market_delivery_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");s.execute("INSERT OR IGNORE INTO market_delivery_schema(id,version) VALUES(1,1)");
            if(s.query("SELECT version FROM market_delivery_schema WHERE id=1",r->r.getInt(1)).getFirst()!=1)throw new SQLException("Unsupported market delivery statistics schema");
            s.execute("CREATE TABLE IF NOT EXISTS market_delivery_clock(id INTEGER PRIMARY KEY CHECK(id=1),sequence INTEGER NOT NULL)");s.execute("INSERT OR IGNORE INTO market_delivery_clock(id,sequence) VALUES(1,0)");
            MarketSchema.upgrade(s,schema);
            return s.query("SELECT namespace FROM market_schema WHERE id=1",r->r.getString(1)).getFirst();
        });
    }
    public MarketDefinitions definitions(){return definitions;}
    String namespace(){return namespace;}
    void ensureCity(dev.starworld.domain.WorldState.CoreCity city)throws SQLException{store.ensureCity(city);}
    public Optional<Listing> listing(UUID id)throws SQLException{return store.query("SELECT * FROM market_listing WHERE id=?",this::readListing,id).stream().findFirst();}
    public Optional<Purchase> purchase(UUID id)throws SQLException{return store.query("SELECT * FROM market_purchase WHERE id=?",this::readPurchase,id).stream().findFirst();}
    public List<Listing> listings(UUID city,int limit,int offset)throws SQLException{page(limit,offset);return city==null?store.query("SELECT * FROM market_listing WHERE state='AVAILABLE' ORDER BY created_period,id LIMIT ? OFFSET ?",this::readListing,limit,offset):store.query("SELECT * FROM market_listing WHERE seller_city=? ORDER BY created_period DESC,id LIMIT ? OFFSET ?",this::readListing,city,limit,offset);}
    public List<Purchase> purchases(UUID city,int limit,int offset)throws SQLException{page(limit,offset);return store.query("SELECT * FROM market_purchase WHERE buyer_city=? OR seller_city=? ORDER BY created_period DESC,id LIMIT ? OFFSET ?",this::readPurchase,city,city,limit,offset);}
    public List<PriceHistory> history(String item,int limit,int offset)throws SQLException{page(limit,offset);return store.query("SELECT l.*,p.units AS purchased_units,h.buyer_city,h.period,h.purchase FROM market_history h JOIN market_listing l ON l.id=h.listing JOIN market_purchase p ON p.id=h.purchase WHERE (? IS NULL OR l.item=?) ORDER BY h.period DESC,h.purchase LIMIT ? OFFSET ?",r->new PriceHistory(uuid(r,"id"),r.getString("item"),r.getLong("purchased_units"),new BigDecimal(r.getString("unit_price")),uuid(r,"seller_city"),uuid(r,"buyer_city"),r.getLong("period"),uuid(r,"purchase")),item,item,limit,offset);}
    /**
     * Returns auditable paid-market deliveries after the physical logistics order reached COMPLETED.
     * The query deliberately excludes SHIPPED/PAID rows and never treats a payment receipt as delivery.
     * A city filter matches either endpoint; null means all cities. Results are grouped by item and
     * ordered deterministically. Statistics are updated by the bounded completion reconciler; query never scans
     * the complete order history. Money is accumulated with BigDecimal rather than SQLite numeric coercion.
     */
    public List<DeliveryStats> deliveryStats(UUID city,String item,int limit,int offset)throws SQLException{
        page(limit,offset);
        return List.copyOf(store.query("SELECT * FROM market_delivery_stat WHERE scope=? AND (? IS NULL OR item=?) ORDER BY item LIMIT ? OFFSET ?",r->{long units=r.getLong("units");BigDecimal gross=new BigDecimal(r.getString("gross"));return new DeliveryStats(r.getString("item"),r.getLong("orders"),units,gross,gross.divide(BigDecimal.valueOf(units),2,java.math.RoundingMode.HALF_UP),r.getLong("first_period"),r.getLong("last_period"));},city==null?"*":city.toString(),item,item,limit,offset));
    }
    /** Completion ledger and all three scope aggregates commit together; repeats/restarts cannot double count. */
    public int reconcileDeliveries(int limit)throws SQLException{
        page(limit,0);
        return store.tx(s->{long cursor=s.query("SELECT sequence FROM market_delivery_clock WHERE id=1",r->r.getLong(1)).getFirst();
            List<DeliveryReceipt> receipts=s.query("SELECT sequence,order_id,period FROM logistics_receipt WHERE sequence>? ORDER BY sequence LIMIT ?",r->new DeliveryReceipt(r.getLong(1),UUID.fromString(r.getString(2)),r.getLong(3)),cursor,limit);
            for(DeliveryReceipt receipt:receipts){List<Purchase> purchases=s.query("SELECT * FROM market_purchase WHERE shipment=?",this::readPurchase,receipt.shipment());if(!purchases.isEmpty()){
                Purchase purchase=purchases.getFirst();Listing listing=listing(purchase.listingId()).orElseThrow();Order order=logistics.order(receipt.shipment()).orElseThrow();
                if(purchase.status()!=PurchaseStatus.SHIPPED||order.status()!=OrderStatus.COMPLETED||!order.originCity().equals(purchase.sellerCity())||!order.targetCity().equals(purchase.buyerCity())||!order.originNation().equals(purchase.sellerNation())||!order.targetNation().equals(purchase.buyerNation())||!listing.sellerCity().equals(purchase.sellerCity())||!listing.sellerNation().equals(purchase.sellerNation())||!order.offered().equals(cargo(listing,purchase))||!order.requested().isEmpty()||order.completedPeriod()!=receipt.period()||!logistics.paidShipment(order.id()))throw new SQLException("Completed market delivery identity mismatch: "+purchase.id());
                if(s.query("SELECT 1 FROM market_delivery_receipt WHERE purchase=?",r->1,purchase.id()).isEmpty()){DeliveryRow row=new DeliveryRow(purchase.id(),purchase.sellerCity(),purchase.buyerCity(),listing.item(),purchase.units(),listing.unitPrice(),receipt.period());for(String scope:List.of("*",row.seller().toString(),row.buyer().toString()))recordDelivery(scope,row);s.execute("INSERT INTO market_delivery_receipt(purchase,period) VALUES(?,?)",row.purchase(),row.period());}
            }s.execute("UPDATE market_delivery_clock SET sequence=? WHERE id=1",receipt.sequence());}return receipts.size();});
    }
    private void recordDelivery(String scope,DeliveryRow row)throws SQLException{
        List<DeliveryStats> old=store.query("SELECT * FROM market_delivery_stat WHERE scope=? AND item=?",r->new DeliveryStats(r.getString("item"),r.getLong("orders"),r.getLong("units"),new BigDecimal(r.getString("gross")),BigDecimal.ZERO,r.getLong("first_period"),r.getLong("last_period")),scope,row.item());
        DeliveryStats previous=old.isEmpty()?null:old.getFirst();long orders=previous==null?1:Math.addExact(previous.orders(),1),units=previous==null?row.units():Math.addExact(previous.units(),row.units());BigDecimal amount=row.price().multiply(BigDecimal.valueOf(row.units())),gross=previous==null?amount:previous.gross().add(amount);long first=previous==null?row.period():Math.min(previous.firstCompletedPeriod(),row.period()),last=previous==null?row.period():Math.max(previous.lastCompletedPeriod(),row.period());
        store.execute("INSERT INTO market_delivery_stat(scope,item,orders,units,gross,first_period,last_period) VALUES(?,?,?,?,?,?,?) ON CONFLICT(scope,item) DO UPDATE SET orders=excluded.orders,units=excluded.units,gross=excluded.gross,first_period=excluded.first_period,last_period=excluded.last_period",scope,row.item(),orders,units,gross.toPlainString(),first,last);
    }
    private record DeliveryRow(UUID purchase,UUID seller,UUID buyer,String item,long units,BigDecimal price,long period){}
    private record DeliveryReceipt(long sequence,UUID shipment,long period){}
    public Listing list(UUID id,UUID city,String item,long units,BigDecimal unitPrice,AuthoritySnapshot auth)throws SQLException{
        Objects.requireNonNull(id);if(!definitions.items().contains(item)||units<1||units>definitions.maxUnits())throw new IllegalArgumentException("物品未开放市场或批次数量超限");BigDecimal price=TreasuryTransferPort.money(unitPrice);if(price.compareTo(definitions.maximumUnitPrice())>0)throw new IllegalArgumentException("挂牌单价超限");TreasuryTransferPort.money(price.multiply(BigDecimal.valueOf(units)));
        return store.tx(s->{fresh(auth);UUID nation=manager(city,auth);Optional<Listing> saved=listing(id);if(saved.isPresent()){Listing l=saved.get();if(!l.sellerCity().equals(city)||!l.sellerNation().equals(nation)||!l.item().equals(item)||l.units()!=units||l.unitPrice().compareTo(price)!=0)throw new IllegalArgumentException("挂牌ID已用于不同参数");return l;}
            if(s.query("SELECT COUNT(*) FROM (SELECT 1 FROM market_listing WHERE seller_city=? AND (available_units>0 OR reserved_units>0) LIMIT ?)",r->r.getLong(1),city,definitions.maxListingsPerCity()).getFirst()>=definitions.maxListingsPerCity())throw new IllegalArgumentException("城市未结束挂牌已达上限");
            Map<String,Long> stock=s.stock(city);if(stock.getOrDefault(item,0L)<units)throw new IllegalArgumentException("仓库挂牌实物不足");s.stock(city,item,stock.get(item)-units);
            s.execute("INSERT INTO market_listing(id,seller_city,seller_nation,item,units,unit_price,state,created_period,expires_period,available_units) VALUES(?,?,?,?,?,?,'AVAILABLE',?,?,?)",id,city,nation,item,units,price.toPlainString(),auth.period(),Math.addExact(auth.period(),definitions.listingLifetimePeriods()),units);s.audit(auth.actor().toString(),"MARKET LIST "+id+" "+item+" "+units+" @ "+price);return listing(id).orElseThrow();});
    }
    /** Omitted quantity buys the remaining stock once; replay returns the original reserved quantity. */
    public Purchase buy(UUID id,UUID listingId,UUID routeId,UUID buyerCity,UUID governedNation,AuthoritySnapshot auth)throws SQLException{
        return reserve(id,listingId,routeId,buyerCity,governedNation,null,auth,null);
    }
    /** Caller proves current founder/administrator authority on the Paper thread. */
    public Purchase buy(UUID id,UUID listingId,UUID routeId,UUID buyerCity,UUID governedNation,long units,AuthoritySnapshot auth)throws SQLException{
        if(units<1)throw new IllegalArgumentException("购买数量必须为正整数");
        return reserve(id,listingId,routeId,buyerCity,governedNation,units,auth,null);
    }
    Purchase buyQuoted(MarketQuoteService.Quote quote,AuthoritySnapshot auth)throws SQLException{
        return reserve(quote.id(),quote.listingId(),quote.routeId(),quote.buyerCity(),quote.buyerNation(),quote.units(),auth,quote);
    }
    Listing quoteBasis(UUID listing,UUID route,UUID buyerCity,UUID governedNation,long units,AuthoritySnapshot auth)throws SQLException{
        fresh(auth);UUID buyer=manager(buyerCity,auth);Listing l=listing(listing).orElseThrow();owner(l.sellerCity(),l.sellerNation(),auth);
        if(!buyer.equals(governedNation)||buyer.equals(l.sellerNation()))throw new IllegalArgumentException("报价需要两个不同国家的当前授权");
        if(l.status()!=ListingStatus.AVAILABLE||auth.period()>=l.expiresPeriod()||units<1||units>l.availableUnits()||units>definitions.maxUnits())throw new IllegalArgumentException("挂牌可购买数量不足、已关闭或到期");
        if(units>route(l,buyerCity,route,auth).capacity())throw new IllegalArgumentException("本次报价数量超过商路运力");return l;
    }
    private Purchase reserve(UUID id,UUID listingId,UUID routeId,UUID buyerCity,UUID governedNation,Long requested,AuthoritySnapshot auth,MarketQuoteService.Quote quote)throws SQLException{
        Objects.requireNonNull(id);Objects.requireNonNull(governedNation);
        return store.tx(s->{
            fresh(auth);UUID buyer=manager(buyerCity,auth);
            if(!buyer.equals(governedNation))throw new IllegalArgumentException("国家付款授权与当前城市不符");
            Listing l=listing(listingId).orElseThrow();owner(l.sellerCity(),l.sellerNation(),auth);
            if(l.sellerNation().equals(buyer))throw new IllegalArgumentException("同国城市请使用实物运输；金币市场需要两个不同国库");
            Optional<Purchase> saved=purchase(id);
            if(saved.isPresent()){
                Purchase previous=saved.get();boolean all=s.query("SELECT buy_all FROM market_purchase WHERE id=?",r->r.getInt(1)==1,id).getFirst();
                if(!previous.listingId().equals(listingId)||!previous.routeId().equals(routeId)
                        ||!previous.buyerCity().equals(buyerCity)||!previous.buyerNation().equals(buyer)
                        ||all!=(requested==null)||(requested!=null&&previous.units()!=requested)||!Objects.equals(previous.taxQuote(),quote==null?null:quote.id()))
                    throw new IllegalArgumentException("购买请求ID已用于不同参数");
                return previous;
            }
            long units=requested==null?l.availableUnits():requested;
            if(l.status()!=ListingStatus.AVAILABLE||auth.period()>=l.expiresPeriod()||units<1||units>l.availableUnits()||units>definitions.maxUnits())
                throw new IllegalArgumentException("挂牌可购买数量不足、已关闭或到期");
            Route route=route(l,buyerCity,routeId,auth);
            if(units>route.capacity())throw new IllegalArgumentException("本次购买数量超过商路运力，请减少购买数量");
            // Partial sales must not turn a bounded listing count into unbounded outstanding payments.
            for(UUID city:List.of(l.sellerCity(),buyerCity)){
                // A purchase has distinct endpoint cities; each branch uses its partial open-order index.
                long open=s.query("SELECT COUNT(*) FROM (SELECT 1 FROM market_purchase WHERE buyer_city=? AND state NOT IN ('SHIPPED','REFUNDED','CANCELLED') "
                        +"UNION ALL SELECT 1 FROM market_purchase WHERE seller_city=? AND state NOT IN ('SHIPPED','REFUNDED','CANCELLED') LIMIT ?)",r->r.getLong(1),city,city,definitions.maxOpenPurchasesPerCity()).getFirst();
                if(open>=definitions.maxOpenPurchasesPerCity())throw new IllegalArgumentException("城市未完成付款/发货准备已达上限");
            }
            BigDecimal goodsTotal=TreasuryTransferPort.money(l.unitPrice().multiply(BigDecimal.valueOf(units))),tax=quote==null?new BigDecimal("0.00"):quote.taxAmount();
            if(quote!=null&&(!quote.listingId().equals(listingId)||!quote.routeId().equals(routeId)||!quote.buyerCity().equals(buyerCity)||!quote.buyerNation().equals(buyer)||!quote.sellerNation().equals(l.sellerNation())||quote.units()!=units||quote.goodsTotal().compareTo(goodsTotal)!=0))throw new IllegalArgumentException("购买与冻结税费报价不符");
            BigDecimal total=TreasuryTransferPort.money(goodsTotal.add(tax));
            UUID shipment=UUID.nameUUIDFromBytes((namespace+":shipment:"+id).getBytes(StandardCharsets.UTF_8));
            String transaction="starworld:market:"+namespace+":"+id;
            s.execute("INSERT INTO market_purchase(id,listing,buyer_city,buyer_nation,seller_city,seller_nation,route,shipment,transaction_id,total,state,created_period,units,buy_all,goods_total,tax_amount,tax_quote) VALUES(?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?)",
                    id,l.id(),buyerCity,buyer,l.sellerCity(),l.sellerNation(),routeId,shipment,transaction,total.toPlainString(),auth.period(),units,requested==null?1:0,goodsTotal.toPlainString(),tax.toPlainString(),quote==null?null:quote.id());
            balance(l,l.availableUnits()-units,Math.addExact(l.reservedUnits(),units),l.shippedUnits(),l.returnedUnits(),null);
            s.audit(auth.actor().toString(),"MARKET BUY PREPARE "+id+" units="+units+" total="+total);
            return purchase(id).orElseThrow();
        });
    }
    /** Claims work before a main-thread payment call. Unknown external outcomes always retain the same transaction ID. */
    public List<Dispatch> leaseDue(int limit,long now)throws SQLException{page(limit,0);if(limit>definitions.dispatchBatch()||now<0)throw new IllegalArgumentException("Invalid payment batch");return store.tx(s->{List<Purchase> fresh=s.query("SELECT * FROM market_purchase WHERE state IN ('PENDING','RETRY','IN_FLIGHT') AND next_attempt=0 ORDER BY created_period,id LIMIT ?",this::readPurchase,limit);List<Purchase> due=s.query("SELECT * FROM market_purchase WHERE state IN ('PENDING','RETRY','IN_FLIGHT') AND next_attempt>0 AND next_attempt<=? ORDER BY next_attempt,created_period,id LIMIT ?",this::readPurchase,now,limit);List<Purchase> rows=FairDispatchQueue.select(s,DISPATCH_QUEUE,fresh,due,limit);List<Dispatch> result=new ArrayList<>();for(Purchase p:rows){String token=UUID.randomUUID().toString();s.execute("UPDATE market_purchase SET state='IN_FLIGHT',lease=?,attempts=attempts+1,next_attempt=? WHERE id=?",token,Math.addExact(now,LEASE_MILLIS),p.id());result.add(new Dispatch(purchase(p.id()).orElseThrow(),token));}return List.copyOf(result);});}
    public boolean finish(Dispatch dispatch,TreasuryTransferPort.Delivery delivery,long now)throws SQLException{Objects.requireNonNull(dispatch);Objects.requireNonNull(delivery);if(now<0||!dispatch.purchase().transactionId().equals(delivery.transactionId()))throw new IllegalArgumentException("付款回调ID不符");return store.tx(s->{Purchase p=purchase(dispatch.purchase().id()).orElseThrow();String lease=s.query("SELECT COALESCE(lease,'') FROM market_purchase WHERE id=?",r->r.getString(1),p.id()).getFirst();if(p.status()!=PurchaseStatus.IN_FLIGHT||!lease.equals(dispatch.leaseToken()))return false;
            PurchaseStatus next=switch(delivery.status()){case CONFIRMED->delivery.receipt()!=null&&delivery.receipt().matches(p.request())?PurchaseStatus.PAID:PurchaseStatus.REVIEW;case RETRY->PurchaseStatus.RETRY;case REJECTED->PurchaseStatus.REJECTED;case CONFLICT->PurchaseStatus.REVIEW;};
            long backoff=Math.min(300000L,1000L*(1L<<Math.min(8,p.attempts())));s.execute("UPDATE market_purchase SET state=?,lease=NULL,next_attempt=?,last_error=?,paid_at=? WHERE id=?",next.name(),Math.addExact(now,backoff),delivery.detail(),next==PurchaseStatus.PAID?delivery.receipt().appliedAt().toString():null,p.id());s.audit("market-outbox","MARKET PAYMENT "+p.id()+" "+next);return true;});}
    public List<Purchase> paid(int limit)throws SQLException{page(limit,0);return store.query("SELECT * FROM market_purchase WHERE state='PAID' ORDER BY created_period,id LIMIT ?",this::readPurchase,limit);}
    /** A blocked early shipment cannot starve later paid customers; reservations remain PAID during backoff. */
    public List<Purchase> leasePaid(int limit,long now)throws SQLException{page(limit,0);if(limit>definitions.dispatchBatch()||now<0)throw new IllegalArgumentException("Invalid shipment batch");return store.tx(s->{List<Purchase> rows=s.query("SELECT * FROM market_purchase WHERE state='PAID' AND next_attempt<=? ORDER BY next_attempt,created_period,id LIMIT ?",this::readPurchase,now,limit);for(Purchase p:rows)s.execute("UPDATE market_purchase SET next_attempt=? WHERE id=? AND state='PAID'",Math.addExact(now,LEASE_MILLIS),p.id());return List.copyOf(rows);});}
    /** The exact purchase slice enters logistics atomically with its own price-history receipt. */
    public Order ship(UUID purchaseId,AuthoritySnapshot auth)throws SQLException{
        return store.tx(s->{
            fresh(auth);Purchase p=purchase(purchaseId).orElseThrow();
            if(p.status()==PurchaseStatus.SHIPPED)return logistics.order(p.shipmentId()).orElseThrow();
            if(p.status()!=PurchaseStatus.PAID)throw new IllegalArgumentException("付款尚未核实，不能发货");
            Listing l=listing(p.listingId()).orElseThrow();
            owner(p.sellerCity(),p.sellerNation(),auth);owner(p.buyerCity(),p.buyerNation(),auth);route(l,p.buyerCity(),p.routeId(),auth);
            // The original buyer consent was durably authorized; dispatch captures current owners only.
            Set<UUID> managed=new HashSet<>(auth.managedCities());managed.add(p.buyerCity());
            AuthoritySnapshot shipmentAuthority=new AuthoritySnapshot(auth.actor(),auth.period(),auth.cityNations(),managed,auth.controlledAnchors(),auth.validNodes(),auth.permittedTrades(),auth.corridors());
            return logistics.createPaidShipment(p.shipmentId(),p.routeId(),p.transactionId(),cargo(l,p),shipmentAuthority,()->{
                Purchase current=purchase(p.id()).orElseThrow();Listing reserved=listing(l.id()).orElseThrow();
                if(current.status()!=PurchaseStatus.PAID||reserved.reservedUnits()<p.units())
                    throw new IllegalStateException("购买份额托管已消费或付款状态变化");
                s.execute("UPDATE market_purchase SET state='SHIPPED' WHERE id=?",p.id());
                balance(reserved,reserved.availableUnits(),reserved.reservedUnits()-p.units(),Math.addExact(reserved.shippedUnits(),p.units()),reserved.returnedUnits(),null);
                s.execute("INSERT INTO market_history(purchase,listing,buyer_city,period) VALUES(?,?,?,?)",p.id(),l.id(),p.buyerCity(),auth.period());
                s.audit("market-shipment","MARKET SHIP "+p.id()+" "+p.shipmentId()+" units="+p.units());
            });
        });
    }
    public Listing cancelListing(UUID id,AuthoritySnapshot auth)throws SQLException{
        return store.tx(s->{
            fresh(auth);Listing l=listing(id).orElseThrow();
            if(!manager(l.sellerCity(),auth).equals(l.sellerNation()))throw new IllegalArgumentException("挂牌原国归属已变化");
            if(closed(l))return l;
            if(l.status()!=ListingStatus.AVAILABLE)throw new IllegalArgumentException("挂牌没有可退还的未预留实物");
            refund(l,l.availableUnits());
            balance(l,0,l.reservedUnits(),l.shippedUnits(),Math.addExact(l.returnedUnits(),l.availableUnits()),ListingStatus.CANCELLED);
            s.audit(auth.actor().toString(),"MARKET CANCEL LIST "+id+" returned="+l.availableUnits());
            return listing(id).orElseThrow();
        });
    }
    public Purchase cancelUnsent(UUID id,AuthoritySnapshot auth)throws SQLException{
        return store.tx(s->{
            fresh(auth);Purchase p=purchase(id).orElseThrow();owner(p.sellerCity(),p.sellerNation(),auth);
            if(!manager(p.buyerCity(),auth).equals(p.buyerNation()))throw new IllegalArgumentException("购买国归属变化");
            if(p.status()==PurchaseStatus.CANCELLED)return p;
            if(p.status()!=PurchaseStatus.PENDING||p.attempts()!=0)throw new IllegalArgumentException("已派发/未知付款须原ID对账，不能盲目撤销退款");
            Listing l=listing(p.listingId()).orElseThrow();cargo(l,p);
            if(l.reservedUnits()<p.units())throw new IllegalStateException("购买预留数量不符");
            if(closed(l)){
                refund(l,p.units());
                balance(l,l.availableUnits(),l.reservedUnits()-p.units(),l.shippedUnits(),Math.addExact(l.returnedUnits(),p.units()),null);
            }else balance(l,Math.addExact(l.availableUnits(),p.units()),l.reservedUnits()-p.units(),l.shippedUnits(),l.returnedUnits(),null);
            s.execute("UPDATE market_purchase SET state='CANCELLED' WHERE id=?",id);
            s.audit(auth.actor().toString(),"MARKET CANCEL UNSENT "+id+" units="+p.units());
            return purchase(id).orElseThrow();
        });
    }
    public Purchase retry(UUID id,AuthoritySnapshot auth,UUID governedNation)throws SQLException{return store.tx(s->{fresh(auth);Purchase p=purchase(id).orElseThrow();if(!manager(p.buyerCity(),auth).equals(p.buyerNation())||!p.buyerNation().equals(governedNation))throw new IllegalArgumentException("需要原付款国家统治权限");if(!Set.of(PurchaseStatus.REJECTED,PurchaseStatus.REVIEW,PurchaseStatus.RETRY).contains(p.status()))throw new IllegalArgumentException("当前状态不能重试");s.execute("UPDATE market_purchase SET state='PENDING',next_attempt=0,lease=NULL,last_error='' WHERE id=?",id);return purchase(id).orElseThrow();});}

    /** Same transaction as bilateral refund acceptance; prevents all later shipment/payment jobs. */
    Purchase holdRefund(UUID id,AuthoritySnapshot auth)throws SQLException{
        return store.tx(s->{fresh(auth);Purchase p=purchase(id).orElseThrow();refundOwners(p,auth);cargo(listing(p.listingId()).orElseThrow(),p);
            if(p.status()==PurchaseStatus.REFUNDING)return p;
            if(p.status()!=PurchaseStatus.PAID&&p.status()!=PurchaseStatus.SHIPPED)throw new IllegalArgumentException("退款需要已核实付款且尚未交付的购买");
            if(p.status()==PurchaseStatus.SHIPPED)logistics.holdPaidRefund(p.shipmentId(),p.transactionId(),auth);
            else if(logistics.order(p.shipmentId()).isPresent())throw new IllegalStateException("未发货购买存在不一致的货运");
            store.execute("UPDATE market_purchase SET state='REFUNDING',lease=NULL,last_error='双方确认退款，托管冻结' WHERE id=?",id);
            return purchase(id).orElseThrow();});
    }
    /** A refund service with a matched durable receipt is the only caller. Goods stay conserved. */
    Purchase returnRefunded(UUID id,AuthoritySnapshot auth)throws SQLException{
        return store.tx(s->{fresh(auth);Purchase p=purchase(id).orElseThrow();refundOwners(p,auth);
            if(p.status()==PurchaseStatus.REFUNDED)return p;
            if(p.status()!=PurchaseStatus.REFUNDING)throw new IllegalArgumentException("购买尚未冻结退款");
            Listing l=listing(p.listingId()).orElseThrow();Map<String,Long> goods=cargo(l,p);boolean shipped=logistics.order(p.shipmentId()).isPresent();
            if(shipped)logistics.returnPaidRefund(p.shipmentId(),p.transactionId(),goods,auth);else refund(l,p.units());
            long reserved=l.reservedUnits()-(shipped?0:p.units()),inTransit=l.shippedUnits()-(shipped?p.units():0);
            ListingStatus state=closed(l)?l.status():l.availableUnits()>0?ListingStatus.AVAILABLE:reserved>0?ListingStatus.LOCKED:ListingStatus.CANCELLED;
            balance(l,l.availableUnits(),reserved,inTransit,Math.addExact(l.returnedUnits(),p.units()),state);
            store.execute("UPDATE market_purchase SET state='REFUNDED',last_error='原付款已退还，商品已归还原卖城' WHERE id=?",id);
            store.audit("market-refund","MARKET REFUND COMPLETE "+id+" units="+p.units());return purchase(id).orElseThrow();});
    }
    void refundOwners(Purchase p,AuthoritySnapshot auth)throws SQLException{fresh(auth);owner(p.sellerCity(),p.sellerNation(),auth);owner(p.buyerCity(),p.buyerNation(),auth);}
    void requireRefundable(Purchase p)throws SQLException{
        if(p.status()==PurchaseStatus.PAID)return;
        if(p.status()!=PurchaseStatus.SHIPPED)throw new IllegalArgumentException("退款仅适用于已付款且尚未交付的商品");
        Order order=logistics.order(p.shipmentId()).orElseThrow();
        if(order.status()!=OrderStatus.IN_TRANSIT&&order.status()!=OrderStatus.PAUSED)throw new IllegalArgumentException("已交付或终止的货物不能申请退款");
    }
    public List<Listing> expiring(long period,int limit)throws SQLException{page(limit,0);return store.query("SELECT l.* FROM market_listing l LEFT JOIN market_expiry_check c ON c.listing=l.id WHERE l.state='AVAILABLE' AND l.expires_period<=? ORDER BY COALESCE(c.period,-1),l.expires_period,l.id LIMIT ?",this::readListing,period,limit);}
    public int expire(AuthoritySnapshot auth,int limit)throws SQLException{
        return store.tx(s->{
            fresh(auth);int count=0;
            for(Listing l:expiring(auth.period(),limit)){
                s.execute("INSERT INTO market_expiry_check(listing,period) VALUES(?,?) ON CONFLICT(listing) DO UPDATE SET period=excluded.period",l.id(),auth.period());
                try{owner(l.sellerCity(),l.sellerNation(),auth);refund(l,l.availableUnits());}
                catch(IllegalArgumentException unavailable){continue;}
                balance(l,0,l.reservedUnits(),l.shippedUnits(),Math.addExact(l.returnedUnits(),l.availableUnits()),ListingStatus.EXPIRED);count++;
            }
            return count;
        });
    }
    private static boolean closed(Listing listing){return listing.status()==ListingStatus.CANCELLED||listing.status()==ListingStatus.EXPIRED;}
    private Map<String,Long> cargo(Listing listing,Purchase purchase){
        if(!listing.id().equals(purchase.listingId())||!listing.sellerCity().equals(purchase.sellerCity())
                ||!listing.sellerNation().equals(purchase.sellerNation())||purchase.units()>listing.units()
                ||purchase.goodsTotal().compareTo(listing.unitPrice().multiply(BigDecimal.valueOf(purchase.units())))!=0)
            throw new IllegalStateException("Purchase quantity, money or seller differs from its listing");
        return Map.of(listing.item(),purchase.units());
    }
    private void balance(Listing original,long available,long reserved,long shipped,long returned,ListingStatus closure)throws SQLException{
        ListingStatus status=closure!=null?closure:closed(original)?original.status():available>0?ListingStatus.AVAILABLE:reserved>0?ListingStatus.LOCKED:returned>0?ListingStatus.CANCELLED:ListingStatus.SHIPPED;
        // Validate the partition before issuing one atomic balance update.
        new Listing(original.id(),original.sellerCity(),original.sellerNation(),original.item(),original.units(),original.unitPrice(),status,original.createdPeriod(),original.expiresPeriod(),available,reserved,shipped,returned);
        store.execute("UPDATE market_listing SET available_units=?,reserved_units=?,shipped_units=?,returned_units=?,state=? WHERE id=?",available,reserved,shipped,returned,status.name(),original.id());
    }
    private Route route(Listing l,UUID target,UUID routeId,AuthoritySnapshot auth)throws SQLException{Route r=logistics.route(routeId).orElseThrow(()->new IllegalArgumentException("未知商路"));if(!r.originCity().equals(l.sellerCity())||!r.targetCity().equals(target)||!r.enabled())throw new IllegalArgumentException("商路必须从卖方城市正向通往买方");logistics.routeLimitation(r).ifPresent(reason->{throw new IllegalArgumentException(reason);});if(!auth.permittedTrades().contains(TradePair.of(r.originCity(),r.targetCity())))throw new IllegalArgumentException("战争或外交阻断市场商路");for(UUID nodeId:r.nodes()){Node node=logistics.node(nodeId).orElseThrow(()->new IllegalArgumentException("商路节点缺失"));if(!node.enabled()||!auth.validNodes().contains(nodeId))throw new IllegalArgumentException("商路节点已停用、未加载或领土/锚点失效");owner(node.cityId(),node.nationId(),auth);}logistics.requireCorridor(r,auth);return r;}
    private void refund(Listing l,long units)throws SQLException{Map<String,Long> stock=store.stock(l.sellerCity());long next=Math.addExact(stock.getOrDefault(l.item(),0L),units);if(next>store.cityCapacity(l.sellerCity()))throw new IllegalArgumentException("原仓已满，挂牌实物继续托管");store.stock(l.sellerCity(),l.item(),next);}
    private UUID owner(UUID city,AuthoritySnapshot auth)throws SQLException{UUID nation=auth.cityNations().get(city);if(nation==null||store.view(city).filter(c->c.nationId().equals(nation)).isEmpty())throw new IllegalArgumentException("当前城市归属未核实");return nation;}
    private void owner(UUID city,UUID expected,AuthoritySnapshot auth)throws SQLException{if(!expected.equals(owner(city,auth)))throw new IllegalArgumentException("国家归属变化，原付款和货物保留待核对");}
    private UUID manager(UUID city,AuthoritySnapshot auth)throws SQLException{UUID nation=owner(city,auth);if(!auth.managedCities().contains(city))throw new IllegalArgumentException("需要城市管理权限");return nation;}
    private void fresh(AuthoritySnapshot auth)throws SQLException{Objects.requireNonNull(auth);long saved=store.query("SELECT period FROM market_clock WHERE id=1",r->r.getLong(1)).getFirst();if(auth.period()<saved)throw new IllegalArgumentException("市场授权周期已过期");store.execute("UPDATE market_clock SET period=? WHERE id=1",auth.period());}
    private Listing readListing(ResultSet r)throws SQLException{return new Listing(uuid(r,"id"),uuid(r,"seller_city"),uuid(r,"seller_nation"),r.getString("item"),r.getLong("units"),new BigDecimal(r.getString("unit_price")),ListingStatus.valueOf(r.getString("state")),r.getLong("created_period"),r.getLong("expires_period"),r.getLong("available_units"),r.getLong("reserved_units"),r.getLong("shipped_units"),r.getLong("returned_units"));}
    private Purchase readPurchase(ResultSet r)throws SQLException{return new Purchase(uuid(r,"id"),uuid(r,"listing"),uuid(r,"buyer_city"),uuid(r,"buyer_nation"),uuid(r,"seller_city"),uuid(r,"seller_nation"),uuid(r,"route"),uuid(r,"shipment"),r.getString("transaction_id"),new BigDecimal(r.getString("total")),PurchaseStatus.valueOf(r.getString("state")),r.getInt("attempts"),r.getLong("next_attempt"),r.getString("last_error"),r.getLong("created_period"),r.getLong("units"),new BigDecimal(r.getString("goods_total")),new BigDecimal(r.getString("tax_amount")),r.getString("tax_quote")==null?null:uuid(r,"tax_quote"));}
    private static UUID uuid(ResultSet r,String key)throws SQLException{return UUID.fromString(r.getString(key));}
    private static void page(int limit,int offset){if(limit<1||limit>100||offset<0)throw new IllegalArgumentException("市场每页1至100项");}
}

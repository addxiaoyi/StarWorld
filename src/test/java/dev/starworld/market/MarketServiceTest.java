package dev.starworld.market;

import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.TreasuryTransferPort;
import dev.starworld.logistics.*;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.market.MarketState.*;
import java.io.IOException;
import dev.starworld.persistence.WorldStore;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarketServiceTest {
    @TempDir Path temp;
    static final String BREAD="minecraft:bread",PLANKS="minecraft:oak_planks";
    private AuthoritySnapshot corridor(Rig r,long period,UUID...managed)throws Exception{
        var auth=r.auth(period,managed);Map<UUID,Node> nodes=new HashMap<>();for(Node n:r.logistics.nodes(100,0))nodes.put(n.id(),n);var route=r.logistics.route(r.route).orElseThrow();var plan=Corridor.plan(route,nodes);
        return new AuthoritySnapshot(auth.actor(),period,auth.cityNations(),auth.managedCities(),auth.controlledAnchors(),auth.validNodes(),auth.permittedTrades(),Map.of(route.id(),new Corridor.Proof(route.id(),plan.fingerprint(),true,plan.columns().size(),1284,"captured")));
    }
    @Test void boundMarketRouteRequiresEvidenceAtReservationAndPaidShipmentAndPreservesCargoOnFailure()throws Exception{
        try(Rig r=new Rig()){
            r.logistics.bindCorridor(r.route,corridor(r,0,r.seller.cityId()));Listing listing=r.list();
            assertThrows(SQLException.class,()->r.buy(listing));assertEquals(20,r.market.listing(listing.id()).orElseThrow().availableUnits());
            Purchase purchase=r.market.buy(UUID.randomUUID(),listing.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),7L,corridor(r,1,r.buyer.cityId()));
            Dispatch dispatch=r.market.leaseDue(10,0).getFirst();r.market.finish(dispatch,r.paid(dispatch),0);
            assertThrows(SQLException.class,()->r.market.ship(purchase.id(),r.auth(2)));assertEquals(PurchaseStatus.PAID,r.market.purchase(purchase.id()).orElseThrow().status());assertEquals(7,r.market.listing(listing.id()).orElseThrow().reservedUnits());
            assertEquals(7,r.market.ship(purchase.id(),corridor(r,2)).offered().get(BREAD));assertEquals(PurchaseStatus.SHIPPED,r.market.purchase(purchase.id()).orElseThrow().status());
            r.restart();assertTrue(r.logistics.route(r.route).orElseThrow().corridorRequired());assertEquals(1,r.logistics.settle(r.auth(3),10).paused());
        }
    }
    @Test void preparedPaymentPreventsChangingTheDeclaredRouteContract()throws Exception{
        try(Rig r=new Rig()){r.buy(r.list());assertThrows(SQLException.class,()->r.logistics.bindCorridor(r.route,corridor(r,2,r.seller.cityId())));assertFalse(r.logistics.route(r.route).orElseThrow().corridorRequired());}
    }
    @Test void literalDottedAndSlashedItemIDsLoadFromMarketSequenceWithoutPathTraversal()throws Exception{
        Path file=temp.resolve("market-literal-items.yml");String dotted="server.food:grains/wheat.flour",slashed="server.kitchen:grain/baozi";
        Files.writeString(file,"market-version: 1\nitems: [\""+dotted+"\", \""+slashed+"\"]\nmax-listings-per-city: 4\nlisting-lifetime-periods: 10\ndispatch-batch: 2\nmax-units: 32\nmaximum-unit-price: \"100.00\"\n");
        MarketDefinitions definitions=MarketDefinitions.load(file.toFile());assertEquals(Set.of(dotted,slashed),definitions.items());
    }
    final class Rig implements AutoCloseable {
        final Path db=temp.resolve(UUID.randomUUID()+".db");final UUID actor=UUID.randomUUID(),world=UUID.randomUUID();final CoreCity seller=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"卖城","卖国",1,"test"),buyer=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"买城","买国",1,"test");
        final Map<UUID,UUID> ownership=new HashMap<>();final Set<String> anchors=new HashSet<>();WorldStore store;LogisticsService logistics;MarketService market;UUID route,left,right;
        Rig()throws Exception{ownership.put(seller.cityId(),seller.nationId());ownership.put(buyer.cityId(),buyer.nationId());open();for(CoreCity c:List.of(seller,buyer)){store.ensureCity(c);store.stock(c.cityId(),BREAD,1000);store.stock(c.cityId(),PLANKS,1000);}Site a=new Site(world,"world",0,64,0),b=new Site(world,"world",320,64,0);anchors.add(a.anchorKey());anchors.add(b.anchorKey());left=logistics.registerNode(UUID.randomUUID(),seller.cityId(),NodeType.ROAD,a,"plains",auth(0,seller.cityId())).id();right=logistics.registerNode(UUID.randomUUID(),buyer.cityId(),NodeType.ROAD,b,"plains",auth(0,buyer.cityId())).id();route=logistics.createRoute(UUID.randomUUID(),seller.cityId(),buyer.cityId(),List.of(left,right),Mode.LAND,auth(0,seller.cityId())).id();}
        void open()throws Exception{store=new WorldStore(db,10000);logistics=new LogisticsService(store,LogisticsDefinitions.defaults());market=new MarketService(store,logistics,MarketDefinitions.defaults());}
        void restart()throws Exception{store.close();open();}
        AuthoritySnapshot auth(long period,UUID... managed)throws Exception{Set<UUID> ids=new HashSet<>();for(Node n:logistics.nodes(100,0))ids.add(n.id());return new AuthoritySnapshot(actor,period,ownership,Set.of(managed),anchors,ids,ownership.keySet().stream().filter(c->!c.equals(seller.cityId())).map(c->TradePair.of(seller.cityId(),c)).collect(java.util.stream.Collectors.toSet()));}
        CoreCity addBuyer()throws Exception{CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"另一买城","另一买国",1,"test");ownership.put(city.cityId(),city.nationId());store.ensureCity(city);store.stock(city.cityId(),BREAD,1000);store.stock(city.cityId(),PLANKS,1000);return city;}
        UUID routeTo(CoreCity city)throws Exception{Site site=new Site(world,"world",640,64,0);anchors.add(site.anchorKey());UUID node=logistics.registerNode(UUID.randomUUID(),city.cityId(),NodeType.ROAD,site,"plains",auth(1,city.cityId())).id();return logistics.createRoute(UUID.randomUUID(),seller.cityId(),city.cityId(),List.of(left,node),Mode.LAND,auth(1,seller.cityId())).id();}
        Purchase partial(Listing listing,CoreCity city,UUID routeId,long units,long period)throws Exception{return market.buy(UUID.randomUUID(),listing.id(),routeId,city.cityId(),city.nationId(),units,auth(period,city.cityId()));}
        void payAndShip(Purchase p,long period)throws Exception{for(Dispatch d:market.leaseDue(10,period*100000)){market.finish(d,paid(d),period*100000);}market.ship(p.id(),auth(period));}
        Listing list()throws Exception{return market.list(UUID.randomUUID(),seller.cityId(),BREAD,20,new BigDecimal("1.25"),auth(1,seller.cityId()));}
        Purchase buy(Listing l)throws Exception{return market.buy(UUID.randomUUID(),l.id(),route,buyer.cityId(),buyer.nationId(),auth(1,buyer.cityId()));}
        Dispatch lease(long now)throws Exception{return market.leaseDue(10,now).getFirst();}
        TreasuryTransferPort.Delivery paid(Dispatch d){var p=d.purchase();var r=p.request();return new TreasuryTransferPort.Delivery(r.transactionId(),TreasuryTransferPort.Status.CONFIRMED,new TreasuryTransferPort.Receipt(r.transactionId(),r.payer(),r.receiver(),r.amount(),"OTHER",Instant.parse("2026-11-20T00:00:00Z")),"durable treasury proof");}
        long stock(CoreCity c)throws Exception{return store.stock(c.cityId()).get(BREAD);}
        public void close()throws Exception{store.close();}
    }
    @Test void listingReservesExactGoodsOnceAndUnsentCancellationRestoresOnlyItsEscrow()throws Exception{try(Rig r=new Rig()){long original=r.stock(r.seller);Listing l=r.list();assertEquals(original-20,r.stock(r.seller));assertEquals(l,r.market.list(l.id(),r.seller.cityId(),BREAD,20,new BigDecimal("1.25"),r.auth(1,r.seller.cityId())));assertEquals(original-20,r.stock(r.seller));assertThrows(SQLException.class,()->r.market.list(l.id(),r.seller.cityId(),BREAD,21,new BigDecimal("1.25"),r.auth(1,r.seller.cityId())));r.market.cancelListing(l.id(),r.auth(1,r.seller.cityId()));r.market.cancelListing(l.id(),r.auth(1,r.seller.cityId()));assertEquals(original,r.stock(r.seller));}}
    @Test void lostPaymentAckAndRestartReuseSameIdAndPhysicalDeliveryOccursExactlyOnce()throws Exception{try(Rig r=new Rig()){Listing l=r.list();Purchase p=r.buy(l);assertEquals(new BigDecimal("25.00"),p.total());assertEquals(p,r.market.buy(p.id(),l.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),r.auth(1,r.buyer.cityId())));Dispatch lost=r.lease(0);assertTrue(r.market.leaseDue(10,1000).isEmpty());r.restart();Dispatch retry=r.lease(MarketService.LEASE_MILLIS);assertEquals(lost.purchase().transactionId(),retry.purchase().transactionId());assertEquals(2,retry.purchase().attempts());assertFalse(r.market.finish(lost,r.paid(lost),MarketService.LEASE_MILLIS));assertTrue(r.market.finish(retry,r.paid(retry),MarketService.LEASE_MILLIS));Order shipment=r.market.ship(p.id(),r.auth(2));assertEquals(PurchaseStatus.SHIPPED,r.market.purchase(p.id()).orElseThrow().status());assertEquals(Map.of(),shipment.requested());assertEquals(980,r.stock(r.seller));assertEquals(1000,r.stock(r.buyer));r.restart();assertEquals(shipment,r.market.ship(p.id(),r.auth(2)));for(int period=3;period<8;period++)r.logistics.settle(r.auth(period),10);assertEquals(OrderStatus.COMPLETED,r.logistics.order(shipment.id()).orElseThrow().status());assertEquals(1020,r.stock(r.buyer));assertEquals(980,r.stock(r.seller));assertEquals(1,r.market.history(BREAD,10,0).size());assertThrows(SQLException.class,()->r.logistics.cancel(shipment.id(),r.auth(8,r.seller.cityId())));assertTrue(r.market.leaseDue(10,999999).isEmpty());}}
    @Test void completedDeliveryStatsExcludePaidAndShippedRowsAndKeepExactMoney()throws Exception{try(Rig r=new Rig()){Listing l=r.list();Purchase p=r.buy(l);Dispatch d=r.lease(0);r.market.finish(d,r.paid(d),0);assertTrue(r.market.deliveryStats(r.buyer.cityId(),BREAD,10,0).isEmpty());Order shipment=r.market.ship(p.id(),r.auth(2));assertTrue(r.market.deliveryStats(r.buyer.cityId(),BREAD,10,0).isEmpty());for(int period=3;period<8;period++)r.logistics.settle(r.auth(period),10);assertEquals(1,r.market.reconcileDeliveries(10));assertEquals(0,r.market.reconcileDeliveries(10));List<DeliveryStats> stats=r.market.deliveryStats(r.buyer.cityId(),BREAD,10,0);assertEquals(1,stats.size());DeliveryStats s=stats.getFirst();assertEquals(BREAD,s.item());assertEquals(1,s.orders());assertEquals(20,s.units());assertEquals(new BigDecimal("25.00"),s.gross());assertEquals(new BigDecimal("1.25"),s.averageUnitPrice());assertEquals(r.logistics.order(shipment.id()).orElseThrow().completedPeriod(),s.firstCompletedPeriod());assertEquals(s.firstCompletedPeriod(),s.lastCompletedPeriod());assertEquals(stats,r.market.deliveryStats(r.seller.cityId(),BREAD,10,0));}}
    @Test void deliveryStatisticsBackfillOldCompletionsInBoundedPagesAndPersistAcrossRestart()throws Exception{try(Rig r=new Rig()){for(String item:List.of(BREAD,PLANKS)){Listing l=r.market.list(UUID.randomUUID(),r.seller.cityId(),item,2,new BigDecimal("1.35"),r.auth(1,r.seller.cityId()));r.buy(l);}for(Dispatch d:r.market.leaseDue(10,0)){r.market.finish(d,r.paid(d),0);r.market.ship(d.purchase().id(),r.auth(2));}for(int period=3;period<8;period++)r.logistics.settle(r.auth(period),10);r.restart();assertTrue(r.market.deliveryStats(null,null,10,0).isEmpty());assertEquals(1,r.market.reconcileDeliveries(1));assertEquals(1,r.market.deliveryStats(null,null,10,0).size());r.restart();assertEquals(1,r.market.reconcileDeliveries(1));assertEquals(0,r.market.reconcileDeliveries(1));List<DeliveryStats> values=r.market.deliveryStats(null,null,10,0);assertEquals(List.of(BREAD,PLANKS),values.stream().map(DeliveryStats::item).toList());assertEquals(List.of(values.get(1)),r.market.deliveryStats(null,null,1,1));assertEquals(List.of(values.get(0)),r.market.deliveryStats(r.seller.cityId(),BREAD,10,0));assertTrue(r.market.deliveryStats(UUID.randomUUID(),null,10,0).isEmpty());assertThrows(UnsupportedOperationException.class,()->values.clear());}}
    @Test void deliveryStatisticsCumulativeGrossCanExceedTheSingleTransferLimit()throws Exception{try(Rig r=new Rig()){MarketDefinitions d=MarketDefinitions.defaults();r.market=new MarketService(r.store,r.logistics,new MarketDefinitions(d.items(),d.maxListingsPerCity(),d.listingLifetimePeriods(),d.dispatchBatch(),d.maxUnits(),new BigDecimal("1000000000000.00")));for(int i=0;i<2;i++){Listing l=r.market.list(UUID.randomUUID(),r.seller.cityId(),BREAD,1,new BigDecimal("800000000000.01"),r.auth(1,r.seller.cityId()));r.buy(l);}for(Dispatch d1:r.market.leaseDue(10,0)){r.market.finish(d1,r.paid(d1),0);r.market.ship(d1.purchase().id(),r.auth(2));}for(int period=3;period<8;period++)r.logistics.settle(r.auth(period),10);assertEquals(2,r.market.reconcileDeliveries(10));DeliveryStats stats=r.market.deliveryStats(null,BREAD,10,0).getFirst();assertEquals(new BigDecimal("1600000000000.02"),stats.gross());assertEquals(new BigDecimal("800000000000.01"),stats.averageUnitPrice());assertEquals(2,stats.orders());assertEquals(2,stats.units());}}
    @Test void failedDeliveryStatisticsCommitRollsBackAllScopesCursorAndReceiptThenRetriesOnce()throws Exception{try(Rig r=new Rig()){Purchase p=r.buy(r.list());Dispatch d=r.lease(0);r.market.finish(d,r.paid(d),0);r.market.ship(p.id(),r.auth(2));for(int period=3;period<8;period++)r.logistics.settle(r.auth(period),10);r.store.execute("CREATE TRIGGER reject_market_delivery_receipt BEFORE INSERT ON market_delivery_receipt BEGIN SELECT RAISE(ABORT,'statistics crash boundary'); END");assertThrows(SQLException.class,()->r.market.reconcileDeliveries(10));assertTrue(r.market.deliveryStats(null,null,10,0).isEmpty());assertTrue(r.market.deliveryStats(r.seller.cityId(),null,10,0).isEmpty());assertEquals(0,r.store.query("SELECT sequence FROM market_delivery_clock WHERE id=1",v->v.getInt(1)).getFirst());r.restart();r.store.execute("DROP TRIGGER reject_market_delivery_receipt");assertEquals(1,r.market.reconcileDeliveries(10));assertEquals(0,r.market.reconcileDeliveries(10));assertEquals(1,r.market.deliveryStats(null,null,10,0).getFirst().orders());assertEquals(1020,r.stock(r.buyer));}}
    @Test void nonMarketTradeReceiptsAdvanceTheBoundedCursorWithoutBeingCountedAndFutureSchemaIsRejected()throws Exception{try(Rig r=new Rig()){UUID barter=UUID.randomUUID();r.logistics.createOffer(barter,r.route,Map.of(BREAD,1L),Map.of(PLANKS,1L),r.auth(1,r.seller.cityId()));r.logistics.accept(barter,r.auth(1,r.buyer.cityId()));Purchase p=r.buy(r.list());Dispatch d=r.lease(0);r.market.finish(d,r.paid(d),0);r.market.ship(p.id(),r.auth(2));for(int period=3;period<8;period++)r.logistics.settle(r.auth(period),10);assertEquals(1,r.market.reconcileDeliveries(1));assertEquals(1,r.market.reconcileDeliveries(1));assertEquals(0,r.market.reconcileDeliveries(1));assertEquals(1,r.market.deliveryStats(null,null,10,0).getFirst().orders());assertEquals(1,r.store.query("SELECT COUNT(*) FROM market_delivery_receipt",v->v.getInt(1)).getFirst());r.store.execute("UPDATE market_delivery_schema SET version=99");assertThrows(SQLException.class,()->new MarketService(r.store,r.logistics,MarketDefinitions.defaults()));}}
    @Test void unverifiedOrConflictingReceiptNeverShipsAndDispatchedGoodsCannotBeBlindlyRefunded()throws Exception{try(Rig r=new Rig()){Listing l=r.list();Purchase p=r.buy(l);Dispatch d=r.lease(0);TreasuryTransferPort.Request request=d.purchase().request();var wrong=new TreasuryTransferPort.Receipt(request.transactionId(),request.payer(),request.receiver(),new BigDecimal("26.00"),"OTHER",Instant.now());r.market.finish(d,new TreasuryTransferPort.Delivery(request.transactionId(),TreasuryTransferPort.Status.CONFIRMED,wrong,"collision"),0);assertEquals(PurchaseStatus.REVIEW,r.market.purchase(p.id()).orElseThrow().status());assertThrows(SQLException.class,()->r.market.ship(p.id(),r.auth(2)));assertThrows(SQLException.class,()->r.market.cancelUnsent(p.id(),r.auth(2,r.buyer.cityId())));assertThrows(SQLException.class,()->r.market.cancelListing(l.id(),r.auth(2,r.seller.cityId())));assertEquals(980,r.stock(r.seller));assertEquals(1000,r.stock(r.buyer));r.restart();assertEquals(PurchaseStatus.REVIEW,r.market.purchase(p.id()).orElseThrow().status());}}
    @Test void shipmentCreationFailureRollsBackReservationHistoryAndReceiptHandoff()throws Exception{try(Rig r=new Rig()){Listing l=r.list();Purchase p=r.buy(l);Dispatch d=r.lease(0);r.market.finish(d,r.paid(d),0);r.store.execute("CREATE TRIGGER reject_paid_cargo BEFORE INSERT ON logistics_cargo BEGIN SELECT RAISE(ABORT,'shipment crash boundary'); END");assertThrows(SQLException.class,()->r.market.ship(p.id(),r.auth(2)));assertEquals(PurchaseStatus.PAID,r.market.purchase(p.id()).orElseThrow().status());assertEquals(ListingStatus.LOCKED,r.market.listing(l.id()).orElseThrow().status());assertTrue(r.logistics.order(p.shipmentId()).isEmpty());assertTrue(r.market.history(null,10,0).isEmpty());r.restart();r.store.execute("DROP TRIGGER reject_paid_cargo");r.market.ship(p.id(),r.auth(2));assertEquals(1,r.market.history(null,10,0).size());assertEquals(980,r.stock(r.seller));}}
    @Test void warLostOwnershipInvalidRouteAndFalseGovernedNationRejectPurchasingWithoutCharging()throws Exception{try(Rig r=new Rig()){Listing l=r.list();AuthoritySnapshot normal=r.auth(1,r.buyer.cityId());AuthoritySnapshot war=new AuthoritySnapshot(normal.actor(),1,normal.cityNations(),normal.managedCities(),normal.controlledAnchors(),normal.validNodes(),Set.of());assertThrows(SQLException.class,()->r.market.buy(UUID.randomUUID(),l.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),war));assertThrows(SQLException.class,()->r.market.buy(UUID.randomUUID(),l.id(),r.route,r.buyer.cityId(),r.seller.nationId(),normal));AuthoritySnapshot noNode=new AuthoritySnapshot(normal.actor(),1,normal.cityNations(),normal.managedCities(),normal.controlledAnchors(),Set.of(),normal.permittedTrades());assertThrows(SQLException.class,()->r.market.buy(UUID.randomUUID(),l.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),noNode));r.store.ensureCity(new CoreCity(r.seller.cityId(),UUID.randomUUID(),"失城","新国",1,"core snapshot"));assertThrows(SQLException.class,()->r.buy(l));assertEquals(ListingStatus.AVAILABLE,r.market.listing(l.id()).orElseThrow().status());assertTrue(r.market.leaseDue(10,0).isEmpty());assertEquals(980,r.stock(r.seller));}}
    @Test void disabledNodeCannotPreparePaymentEvenWhenPhysicalAuthorityIsStillValid()throws Exception{try(Rig r=new Rig()){Listing l=r.list();r.logistics.setNodeEnabled(r.left,false,r.auth(1,r.seller.cityId()));AuthoritySnapshot physical=r.auth(1,r.buyer.cityId());assertTrue(physical.validNodes().contains(r.left));assertThrows(SQLException.class,()->r.market.buy(UUID.randomUUID(),l.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),physical));assertEquals(ListingStatus.AVAILABLE,r.market.listing(l.id()).orElseThrow().status());assertTrue(r.market.leaseDue(10,0).isEmpty());assertEquals(980,r.stock(r.seller));}}
    @Test void unpaidPurchaseCanCancelBeforeFirstDispatchAndOnlyOneBuyerCanLockListing()throws Exception{try(Rig r=new Rig()){Listing l=r.list();Purchase p=r.buy(l);assertThrows(SQLException.class,()->r.market.buy(UUID.randomUUID(),l.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),r.auth(1,r.buyer.cityId())));r.market.cancelUnsent(p.id(),r.auth(1,r.buyer.cityId()));assertEquals(ListingStatus.AVAILABLE,r.market.listing(l.id()).orElseThrow().status());Purchase next=r.buy(l);assertNotEquals(p.transactionId(),next.transactionId());Dispatch d=r.lease(0);assertThrows(SQLException.class,()->r.market.cancelUnsent(next.id(),r.auth(1,r.buyer.cityId())));assertEquals(next.id(),d.purchase().id());assertEquals(980,r.stock(r.seller));}}
    @Test void expirationCannotDestroyReservedGoodsWhenCapacityDropsAndOldSchemaIsRejected()throws Exception{try(Rig r=new Rig()){Listing l=r.list();r.store.cityCapacity(r.seller.cityId(),980);assertEquals(0,r.market.expire(r.auth(l.expiresPeriod()),10));assertEquals(ListingStatus.AVAILABLE,r.market.listing(l.id()).orElseThrow().status());r.store.stock(r.seller.cityId(),BREAD,950);assertEquals(1,r.market.expire(r.auth(l.expiresPeriod()+1),10));assertEquals(970,r.stock(r.seller));assertEquals(0,r.market.expire(r.auth(l.expiresPeriod()+2),10));r.store.execute("UPDATE market_schema SET version=99");assertThrows(SQLException.class,()->new MarketService(r.store,r.logistics,MarketDefinitions.defaults()));}}
    @Test void paidShipmentBackoffPersistsAndBlockedFirstCustomerDoesNotStarveNext()throws Exception{try(Rig r=new Rig()){r.buy(r.list());r.buy(r.list());for(Dispatch d:r.market.leaseDue(10,0))r.market.finish(d,r.paid(d),0);List<Purchase> first=r.market.leasePaid(1,5000),second=r.market.leasePaid(1,5000);assertEquals(1,first.size());assertEquals(1,second.size());assertNotEquals(first.getFirst().id(),second.getFirst().id());assertTrue(r.market.leasePaid(1,5000).isEmpty());r.restart();assertTrue(r.market.leasePaid(1,5000).isEmpty());assertEquals(PurchaseStatus.PAID,r.market.purchase(first.getFirst().id()).orElseThrow().status());assertEquals(1,r.market.leasePaid(1,35001).size());}}
    @Test void fullExpiredEscrowRotatesPastTheOldestListingWithoutLosingEitherBatch()throws Exception{try(Rig r=new Rig()){Listing a=r.list(),b=r.list();r.store.cityCapacity(r.seller.cityId(),960);long period=Math.max(a.expiresPeriod(),b.expiresPeriod());UUID first=r.market.expiring(period,1).getFirst().id();assertEquals(0,r.market.expire(r.auth(period),1));UUID next=r.market.expiring(period,1).getFirst().id();assertNotEquals(first,next);r.store.stock(r.seller.cityId(),BREAD,940);assertEquals(1,r.market.expire(r.auth(period),1));assertEquals(ListingStatus.EXPIRED,r.market.listing(next).orElseThrow().status());assertEquals(ListingStatus.AVAILABLE,r.market.listing(first).orElseThrow().status());assertEquals(960,r.stock(r.seller));}}
    @Test void definitionsAndMoneyStayExactAndDoNotOverwriteExistingConfiguration()throws Exception{Path file=temp.resolve("market.yml");MarketDefinitions.writeDefaults(file.toFile());assertEquals(MarketDefinitions.defaults(),MarketDefinitions.load(file.toFile()));Files.writeString(file,Files.readString(file)+"\n# administrator\n");MarketDefinitions.writeDefaults(file.toFile());assertTrue(Files.readString(file).endsWith("# administrator\n"));assertThrows(IllegalArgumentException.class,()->TreasuryTransferPort.money(new BigDecimal("0.001")));assertThrows(IllegalArgumentException.class,()->TreasuryTransferPort.money(new BigDecimal("-1")));Files.writeString(file,Files.readString(file).replace("market-version: 1","market-version: 99"));assertThrows(IllegalArgumentException.class,()->MarketDefinitions.load(file.toFile()));}

    @Test void failedPaymentBacklogCannotStarveNewPurchaseAcrossRestart()throws Exception{
        try(Rig r=new Rig()){
            MarketDefinitions d=MarketDefinitions.defaults();MarketDefinitions bulk=new MarketDefinitions(d.items(),200,d.listingLifetimePeriods(),1,d.maxUnits(),d.maximumUnitPrice(),200);
            r.market=new MarketService(r.store,r.logistics,bulk);
            Map<UUID,String> originalTransactions=new HashMap<>();for(int i=0;i<130;i++){Purchase old=r.buy(r.market.list(UUID.randomUUID(),r.seller.cityId(),BREAD,1,new BigDecimal("1.00"),r.auth(1,r.seller.cityId())));originalTransactions.put(old.id(),old.transactionId());}
            r.store.execute("UPDATE market_purchase SET state='RETRY',attempts=8,next_attempt=0,created_period=1");
            Purchase newest=r.buy(r.market.list(UUID.randomUUID(),r.seller.cityId(),BREAD,1,new BigDecimal("1.00"),r.auth(1,r.seller.cityId())));
            r.store.execute("UPDATE market_purchase SET created_period=2 WHERE id=?",newest.id());originalTransactions.put(newest.id(),newest.transactionId());Set<UUID> seen=new HashSet<>();
            for(int tick=0;tick<140;tick++){
                if(tick==70){r.restart();r.market=new MarketService(r.store,r.logistics,bulk);}
                long now=tick*2000L;List<Dispatch> due=r.market.leaseDue(1,now);if(due.isEmpty())continue;Dispatch dispatch=due.getFirst();seen.add(dispatch.purchase().id());
                assertEquals(originalTransactions.get(dispatch.purchase().id()),dispatch.purchase().transactionId());
                TreasuryTransferPort.Delivery result=dispatch.purchase().id().equals(newest.id())?r.paid(dispatch):new TreasuryTransferPort.Delivery(dispatch.purchase().transactionId(),TreasuryTransferPort.Status.RETRY,null,"deterministic backlog");
                assertTrue(r.market.finish(dispatch,result,now));
            }
            assertEquals(131,seen.size());Purchase paid=r.market.purchase(newest.id()).orElseThrow();assertEquals(PurchaseStatus.PAID,paid.status());assertEquals(newest.transactionId(),paid.transactionId());assertEquals(869,r.stock(r.seller));assertEquals(1000,r.stock(r.buyer));
        }
    }
    @Test void tightenedTransportConfigurationPreservesPaidEscrowAndRejectsNewPaymentsUntilRecovery()throws Exception{
        try(Rig r=new Rig()){
            Purchase pending=r.buy(r.list());Dispatch dispatch=r.lease(0);r.market.finish(dispatch,r.paid(dispatch),0);Listing unsold=r.list();
            r.store.close();r.store=new WorldStore(r.db,10000);LogisticsDefinitions d=LogisticsDefinitions.defaults();
            LogisticsDefinitions reduced=new LogisticsDefinitions(d.nodes(),d.terrainTime(),d.maxNodesPerCity(),d.maxRoutesPerCity(),d.maxWaypoints(),d.maxOpenOrdersPerCity(),d.maxBatch(),d.blocksPerPeriod(),1,d.offerLifetimePeriods(),d.maxItemsPerOrder());
            r.logistics=new LogisticsService(r.store,reduced);r.market=new MarketService(r.store,r.logistics,MarketDefinitions.defaults());
            assertTrue(r.logistics.routeLimitation(r.logistics.route(r.route).orElseThrow()).isPresent());
            assertThrows(SQLException.class,()->r.buy(unsold));assertThrows(SQLException.class,()->r.market.ship(pending.id(),r.auth(2)));
            assertEquals(PurchaseStatus.PAID,r.market.purchase(pending.id()).orElseThrow().status());assertEquals(ListingStatus.LOCKED,r.market.listing(pending.listingId()).orElseThrow().status());assertTrue(r.logistics.order(pending.shipmentId()).isEmpty());assertEquals(960,r.stock(r.seller));
            r.restart();Order shipped=r.market.ship(pending.id(),r.auth(3));assertEquals(pending.shipmentId(),shipped.id());assertEquals(shipped,r.market.ship(pending.id(),r.auth(3)));assertEquals(1,r.market.history(BREAD,10,0).size());assertEquals(960,r.stock(r.seller));
        }
    }


    @Test void steadyNewPurchasesCannotStarveRecoverablePaymentAcrossRestart()throws Exception{
        try(Rig r=new Rig()){
            Purchase old=r.buy(r.list());Dispatch first=r.market.leaseDue(1,0).getFirst();r.market.finish(first,new TreasuryTransferPort.Delivery(old.transactionId(),TreasuryTransferPort.Status.RETRY,null,"temporary outage"),0);assertEquals(2000,r.market.purchase(old.id()).orElseThrow().nextAttempt());int recoveredAt=-1;
            for(int tick=1;tick<=12;tick++){
                if(tick==1||tick==7)r.restart();Listing fresh=r.market.list(UUID.randomUUID(),r.seller.cityId(),BREAD,1,new BigDecimal("1.00"),r.auth(tick,r.seller.cityId()));r.market.buy(UUID.randomUUID(),fresh.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),r.auth(tick,r.buyer.cityId()));
                long now=tick*2000L;List<Dispatch> batch=r.market.leaseDue(1,now);assertEquals(1,batch.size());Dispatch next=batch.getFirst();if(next.purchase().id().equals(old.id())){recoveredAt=tick;assertEquals(old.transactionId(),next.purchase().transactionId());assertEquals(2,next.purchase().attempts());}assertTrue(r.market.finish(next,r.paid(next),now));
            }
            assertTrue(recoveredAt>=1&&recoveredAt<=2,"Recoverable payment gets its lane while new purchases arrive every tick");r.restart();assertEquals(PurchaseStatus.PAID,r.market.purchase(old.id()).orElseThrow().status());assertEquals(968,r.stock(r.seller));assertEquals(1000,r.stock(r.buyer));assertEquals(1,r.store.query("SELECT COUNT(*) FROM market_purchase WHERE state='PENDING'",v->v.getInt(1)).getFirst());
        }
    }
    @Test void failedPaymentLeaseRollsBackCursorAndRowsBeforeRespectingNextBatchLimit()throws Exception{
        try(Rig r=new Rig()){
            Purchase old=r.buy(r.list());Dispatch first=r.market.leaseDue(1,0).getFirst();r.market.finish(first,new TreasuryTransferPort.Delivery(old.transactionId(),TreasuryTransferPort.Status.RETRY,null,"later"),0);for(int i=0;i<4;i++)r.buy(r.list());String before=r.store.query("SELECT next_lane FROM starworld_dispatch_cursor WHERE queue='starworld:market-payment'",v->v.getString(1)).getFirst();
            r.store.execute("CREATE TRIGGER reject_market_lease BEFORE UPDATE ON market_purchase WHEN NEW.state='IN_FLIGHT' BEGIN SELECT RAISE(ABORT,'lease rollback'); END");assertThrows(SQLException.class,()->r.market.leaseDue(1,2000));assertEquals(before,r.store.query("SELECT next_lane FROM starworld_dispatch_cursor WHERE queue='starworld:market-payment'",v->v.getString(1)).getFirst());assertEquals(PurchaseStatus.RETRY,r.market.purchase(old.id()).orElseThrow().status());assertEquals(1,r.market.purchase(old.id()).orElseThrow().attempts());assertEquals(0,r.store.query("SELECT COUNT(*) FROM market_purchase WHERE state='IN_FLIGHT'",v->v.getInt(1)).getFirst());
            r.store.execute("DROP TRIGGER reject_market_lease");List<Dispatch> batch=r.market.leaseDue(3,2000);assertEquals(3,batch.size());assertEquals(old.id(),batch.getFirst().purchase().id());assertEquals(3,r.store.query("SELECT COUNT(*) FROM market_purchase WHERE state='IN_FLIGHT'",v->v.getInt(1)).getFirst());assertEquals(2,r.store.query("SELECT COUNT(*) FROM market_purchase WHERE state='PENDING'",v->v.getInt(1)).getFirst());
        }
    }

    @Test void twoBuyersReceiveOnlyTheirReservedSlicesAndStatisticsSurviveRestart()throws Exception{
        try(Rig r=new Rig()){
            CoreCity second=r.addBuyer();UUID secondRoute=r.routeTo(second);Listing lot=r.list();
            Purchase first=r.partial(lot,r.buyer,r.route,7,1),other=r.partial(lot,second,secondRoute,13,1);
            assertEquals(new BigDecimal("8.75"),first.total());assertEquals(new BigDecimal("16.25"),other.total());
            assertNotEquals(first.transactionId(),other.transactionId());assertNotEquals(first.shipmentId(),other.shipmentId());
            assertEquals(980,r.stock(r.seller));assertEquals(20,r.market.listing(lot.id()).orElseThrow().reservedUnits());
            r.payAndShip(first,2);r.restart();Order secondShipment=r.market.ship(other.id(),r.auth(2));
            assertEquals(Map.of(BREAD,13L),secondShipment.offered());
            assertEquals(Map.of(BREAD,7L),r.logistics.order(first.shipmentId()).orElseThrow().offered());
            assertEquals(secondShipment,r.market.ship(other.id(),r.auth(2)));
            for(int period=3;period<12;period++)r.logistics.settle(r.auth(period),10);
            assertEquals(2,r.market.reconcileDeliveries(10));r.restart();assertEquals(0,r.market.reconcileDeliveries(10));
            assertEquals(1007,r.stock(r.buyer));assertEquals(1013,r.stock(second));assertEquals(980,r.stock(r.seller));
            assertEquals(20,r.market.listing(lot.id()).orElseThrow().shippedUnits());
            assertEquals(ListingStatus.SHIPPED,r.market.listing(lot.id()).orElseThrow().status());
            assertEquals(2,r.market.history(BREAD,10,0).size());
            assertEquals(Set.of(first.id(),other.id()),r.market.history(BREAD,10,0).stream().map(PriceHistory::purchaseId).collect(java.util.stream.Collectors.toSet()));
            DeliveryStats all=r.market.deliveryStats(null,BREAD,10,0).getFirst();assertEquals(2,all.orders());assertEquals(20,all.units());assertEquals(new BigDecimal("25.00"),all.gross());
            assertEquals(7,r.market.deliveryStats(r.buyer.cityId(),BREAD,10,0).getFirst().units());
            assertEquals(13,r.market.deliveryStats(second.cityId(),BREAD,10,0).getFirst().units());
        }
    }
    @Test void remainderClosureCannotReturnAnotherBuyersReservationAndClosedUnsentSliceReturnsOnce()throws Exception{
        try(Rig r=new Rig()){
            Listing lot=r.list();Purchase first=r.partial(lot,r.buyer,r.route,7,1);
            Listing closed=r.market.cancelListing(lot.id(),r.auth(1,r.seller.cityId()));
            assertEquals(13,closed.returnedUnits());assertEquals(7,closed.reservedUnits());assertEquals(993,r.stock(r.seller));
            assertEquals(closed,r.market.cancelListing(lot.id(),r.auth(1,r.seller.cityId())));
            r.restart();Purchase cancelled=r.market.cancelUnsent(first.id(),r.auth(1,r.buyer.cityId()));
            assertEquals(cancelled,r.market.cancelUnsent(first.id(),r.auth(1,r.buyer.cityId())));assertEquals(1000,r.stock(r.seller));
            Listing after=r.market.listing(lot.id()).orElseThrow();assertEquals(ListingStatus.CANCELLED,after.status());assertEquals(20,after.returnedUnits());assertEquals(0,after.reservedUnits());assertEquals(0,after.availableUnits());
            assertTrue(r.market.leaseDue(10,0).isEmpty());
        }
    }
    @Test void paidSliceShipsAfterSellerCancelsOnlyRemainderAndRecordsExactDelivery()throws Exception{
        try(Rig r=new Rig()){
            Listing lot=r.list();Purchase purchase=r.partial(lot,r.buyer,r.route,7,1);Dispatch dispatch=r.lease(0);r.market.finish(dispatch,r.paid(dispatch),0);
            r.market.cancelListing(lot.id(),r.auth(1,r.seller.cityId()));r.restart();r.market.ship(purchase.id(),r.auth(2));
            for(int period=3;period<8;period++)r.logistics.settle(r.auth(period),10);
            assertEquals(1,r.market.reconcileDeliveries(10));assertEquals(993,r.stock(r.seller));assertEquals(1007,r.stock(r.buyer));
            Listing after=r.market.listing(lot.id()).orElseThrow();assertEquals(ListingStatus.CANCELLED,after.status());assertEquals(13,after.returnedUnits());assertEquals(7,after.shippedUnits());
            assertEquals(new BigDecimal("8.75"),r.market.deliveryStats(null,BREAD,10,0).getFirst().gross());
        }
    }
    @Test void expiryReturnsOnlyUnreservedStockAndPaidSliceStillShips()throws Exception{
        try(Rig r=new Rig()){
            Listing lot=r.list();Purchase purchase=r.partial(lot,r.buyer,r.route,4,1);Dispatch dispatch=r.lease(0);r.market.finish(dispatch,r.paid(dispatch),0);
            assertEquals(1,r.market.expire(r.auth(lot.expiresPeriod()),10));assertEquals(996,r.stock(r.seller));
            assertEquals(0,r.market.expire(r.auth(lot.expiresPeriod()),10));r.restart();
            assertEquals(Map.of(BREAD,4L),r.market.ship(purchase.id(),r.auth(lot.expiresPeriod())).offered());
            Listing after=r.market.listing(lot.id()).orElseThrow();assertEquals(ListingStatus.EXPIRED,after.status());assertEquals(16,after.returnedUnits());assertEquals(4,after.shippedUnits());
        }
    }
    @Test void insufficientRefundCapacityPreservesClosedReservationUntilExplicitRetry()throws Exception{
        try(Rig r=new Rig()){
            Listing lot=r.list();Purchase purchase=r.partial(lot,r.buyer,r.route,7,1);r.market.cancelListing(lot.id(),r.auth(1,r.seller.cityId()));r.store.cityCapacity(r.seller.cityId(),995);
            assertThrows(SQLException.class,()->r.market.cancelUnsent(purchase.id(),r.auth(1,r.buyer.cityId())));
            assertEquals(PurchaseStatus.PENDING,r.market.purchase(purchase.id()).orElseThrow().status());assertEquals(7,r.market.listing(lot.id()).orElseThrow().reservedUnits());assertEquals(993,r.stock(r.seller));
            r.restart();r.store.cityCapacity(r.seller.cityId(),1000);r.market.cancelUnsent(purchase.id(),r.auth(1,r.buyer.cityId()));assertEquals(1000,r.stock(r.seller));
        }
    }
    @Test void partialReservationFailureRollsBackThePurchaseAndEveryEscrowBalance()throws Exception{
        try(Rig r=new Rig()){
            Listing lot=r.list();r.store.execute("CREATE TRIGGER reject_reservation BEFORE UPDATE ON market_listing BEGIN SELECT RAISE(ABORT,'reserve crash'); END");
            assertThrows(SQLException.class,()->r.partial(lot,r.buyer,r.route,6,1));assertTrue(r.market.purchases(r.buyer.cityId(),10,0).isEmpty());assertEquals(lot,r.market.listing(lot.id()).orElseThrow());assertEquals(980,r.stock(r.seller));
            r.store.execute("DROP TRIGGER reject_reservation");Purchase purchase=r.partial(lot,r.buyer,r.route,6,1);Dispatch dispatch=r.lease(0);r.market.finish(dispatch,r.paid(dispatch),0);
            r.store.execute("CREATE TRIGGER reject_partial_history BEFORE INSERT ON market_history BEGIN SELECT RAISE(ABORT,'history crash'); END");
            assertThrows(SQLException.class,()->r.market.ship(purchase.id(),r.auth(2)));assertEquals(PurchaseStatus.PAID,r.market.purchase(purchase.id()).orElseThrow().status());assertEquals(6,r.market.listing(lot.id()).orElseThrow().reservedUnits());assertTrue(r.logistics.order(purchase.shipmentId()).isEmpty());
            r.restart();r.store.execute("DROP TRIGGER reject_partial_history");r.market.ship(purchase.id(),r.auth(2));assertEquals(6,r.market.listing(lot.id()).orElseThrow().shippedUnits());assertEquals(14,r.market.listing(lot.id()).orElseThrow().availableUnits());
        }
    }
    @Test void quantitiesCannotOversellAndRequestReplayRetainsItsFrozenSlice()throws Exception{
        try(Rig r=new Rig()){
            Listing lot=r.list();Purchase partial=r.partial(lot,r.buyer,r.route,6,1);r.restart();
            assertEquals(partial,r.market.buy(partial.id(),lot.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),6,r.auth(1,r.buyer.cityId())));
            assertThrows(SQLException.class,()->r.market.buy(partial.id(),lot.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),7,r.auth(1,r.buyer.cityId())));
            assertThrows(SQLException.class,()->r.market.buy(partial.id(),lot.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),r.auth(1,r.buyer.cityId())));
            assertThrows(SQLException.class,()->r.partial(lot,r.buyer,r.route,15,1));
            Purchase remaining=r.buy(lot);assertEquals(14,remaining.units());assertEquals(new BigDecimal("17.50"),remaining.total());
            r.market.cancelUnsent(partial.id(),r.auth(1,r.buyer.cityId()));
            assertEquals(remaining,r.market.buy(remaining.id(),lot.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),r.auth(1,r.buyer.cityId())));
            assertEquals(6,r.market.listing(lot.id()).orElseThrow().availableUnits());assertEquals(14,r.market.listing(lot.id()).orElseThrow().reservedUnits());assertEquals(980,r.stock(r.seller));
        }
    }
    @Test void competingPartialRequestsSerializeWithoutDoubleReservation()throws Exception{
        try(Rig r=new Rig()){
            Listing lot=r.list();AuthoritySnapshot auth=r.auth(1,r.buyer.cityId());
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
                var start=new java.util.concurrent.CountDownLatch(1);List<java.util.concurrent.Future<Boolean>> results=new ArrayList<>();
                for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();try{r.market.buy(UUID.randomUUID(),lot.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),13,auth);return true;}catch(SQLException unavailable){return false;}}));
                start.countDown();int success=0;for(var result:results)if(result.get(5,java.util.concurrent.TimeUnit.SECONDS))success++;
                assertEquals(1,success);assertEquals(1,r.market.purchases(r.buyer.cityId(),10,0).size());
            }
            Listing after=r.market.listing(lot.id()).orElseThrow();assertEquals(7,after.availableUnits());assertEquals(13,after.reservedUnits());assertEquals(980,r.stock(r.seller));
        }
    }
    @Test void aLargeLotCanDeliverACapacitySizedSliceWhileItsRemainderStaysAvailable()throws Exception{
        try(Rig r=new Rig()){
            Listing lot=r.market.list(UUID.randomUUID(),r.seller.cityId(),BREAD,600,new BigDecimal("1.25"),r.auth(1,r.seller.cityId()));
            assertThrows(SQLException.class,()->r.partial(lot,r.buyer,r.route,300,1));
            Purchase slice=r.partial(lot,r.buyer,r.route,200,1);r.payAndShip(slice,2);
            for(int period=3;period<8;period++)r.logistics.settle(r.auth(period),10);
            r.market.reconcileDeliveries(10);Listing available=r.market.listing(lot.id()).orElseThrow();
            assertEquals(ListingStatus.AVAILABLE,available.status());assertEquals(400,available.availableUnits());assertEquals(200,available.shippedUnits());
            assertEquals(1200,r.stock(r.buyer));assertEquals(400,r.stock(r.seller));
            DeliveryStats stats=r.market.deliveryStats(null,BREAD,10,0).getFirst();assertEquals(200,stats.units());assertEquals(new BigDecimal("250.00"),stats.gross());
            r.market.cancelListing(lot.id(),r.auth(8,r.seller.cityId()));assertEquals(800,r.stock(r.seller));
        }
    }
    @Test void loweredQuantityLimitBlocksNewReservationsButCannotRewriteAnExistingReceipt()throws Exception{
        try(Rig r=new Rig()){
            Listing lot=r.list();Purchase slice=r.partial(lot,r.buyer,r.route,12,1);MarketDefinitions d=MarketDefinitions.defaults();
            r.market=new MarketService(r.store,r.logistics,new MarketDefinitions(d.items(),d.maxListingsPerCity(),d.listingLifetimePeriods(),d.dispatchBatch(),5,d.maximumUnitPrice()));
            assertEquals(slice,r.market.buy(slice.id(),lot.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),12,r.auth(2,r.buyer.cityId())));
            assertThrows(SQLException.class,()->r.partial(lot,r.buyer,r.route,6,2));
            assertEquals(8,r.market.listing(lot.id()).orElseThrow().availableUnits());assertEquals(1,r.market.purchases(r.buyer.cityId(),10,0).size());
            r.payAndShip(slice,3);assertEquals(Map.of(BREAD,12L),r.logistics.order(slice.shipmentId()).orElseThrow().offered());
        }
    }
    @Test void partialPaymentsRemainBoundedAndCancelledAttemptReleasesItsSlot()throws Exception{
        try(Rig r=new Rig()){
            MarketDefinitions d=MarketDefinitions.defaults();r.market=new MarketService(r.store,r.logistics,new MarketDefinitions(d.items(),d.maxListingsPerCity(),d.listingLifetimePeriods(),d.dispatchBatch(),d.maxUnits(),d.maximumUnitPrice(),2));
            Listing lot=r.list();Purchase first=r.partial(lot,r.buyer,r.route,1,1);r.partial(lot,r.buyer,r.route,1,1);
            assertThrows(SQLException.class,()->r.partial(lot,r.buyer,r.route,1,1));assertEquals(18,r.market.listing(lot.id()).orElseThrow().availableUnits());
            r.market.cancelUnsent(first.id(),r.auth(1,r.buyer.cityId()));r.partial(lot,r.buyer,r.route,1,1);assertEquals(18,r.market.listing(lot.id()).orElseThrow().availableUnits());assertEquals(2,r.market.listing(lot.id()).orElseThrow().reservedUnits());
            assertThrows(SQLException.class,()->r.store.execute("UPDATE market_listing SET available_units=19 WHERE id=?",lot.id()));
        }
    }

}

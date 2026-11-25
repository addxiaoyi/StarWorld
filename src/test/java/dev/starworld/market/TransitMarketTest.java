package dev.starworld.market;

import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.*;
import dev.starworld.integration.TransitPermitPort.*;
import dev.starworld.logistics.*;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.market.MarketServiceTest.Rig;
import dev.starworld.market.MarketState.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TransitMarketTest {
    @TempDir java.nio.file.Path temp;
    final CoreCity third=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"过境城","过境国",1,"test");
    final Map<UUID,Permit> permits=new HashMap<>();
    final Map<UUID,Route> routes=new HashMap<>();
    Rig rig()throws Exception{
        MarketServiceTest fixture=new MarketServiceTest();fixture.temp=temp;Rig r=fixture.new Rig();r.ownership.put(third.cityId(),third.nationId());r.store.ensureCity(third);r.store.stock(third.cityId(),"minecraft:oak_planks",1000);r.store.stock(r.buyer.cityId(),"minecraft:iron_ingot",1000);
        Site site=new Site(r.world,"world",160,64,0);r.anchors.add(site.anchorKey());Node node=r.logistics.registerNode(UUID.randomUUID(),third.cityId(),NodeType.ROAD,site,"plains",r.auth(0,third.cityId()));
        Route route=r.logistics.createRoute(UUID.randomUUID(),r.seller.cityId(),r.buyer.cityId(),List.of(r.left,node.id(),r.right),Mode.LAND,r.auth(0,r.seller.cityId()));r.route=route.id();routes.put(route.id(),route);return r;
    }
    Permit approve(Rig r,Route route,String fee,long revision)throws Exception{
        Map<UUID,Node> nodes=new HashMap<>();for(Node node:r.logistics.nodes(1000,0))nodes.put(node.id(),node);Set<Parcel> parcels=new HashSet<>();for(int i=1;i<20;i++)parcels.add(new Parcel(r.world,"world",third.cityId(),i,0));
        Permit permit=new Permit(new Terms(route.transitKey(),Corridor.plan(route,nodes).fingerprint(),third.nationId(),r.seller.nationId(),r.buyer.nationId(),parcels,new BigDecimal(fee),1_000_000+revision),revision,false,r.actor,revision);permits.put(route.id(),permit);return permit;
    }
    AuthoritySnapshot auth(Rig r,long period,UUID... managed)throws Exception{
        var a=r.auth(period,managed);Map<UUID,Corridor.Proof> proofs=new HashMap<>();for(var entry:permits.entrySet()){var permit=entry.getValue();if(permit.revoked())continue;proofs.put(entry.getKey(),new Corridor.Proof(entry.getKey(),permit.terms().fingerprint(),true,321,1284,"live verified transit",Map.of(third.nationId(),permit)));}
        return new AuthoritySnapshot(a.actor(),period,a.cityNations(),a.managedCities(),a.controlledAnchors(),a.validNodes(),a.permittedTrades(),proofs);
    }
    MarketQuoteService.Quote quote(Rig r,MarketQuoteService service,Listing lot,long period,long now)throws Exception{return service.prepare(lot.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),7,new MarketTaxPort.Quote(UUID.randomUUID(),r.seller.nationId(),r.actor,new BigDecimal("8.75"),new BigDecimal("0.26")),auth(r,period,r.buyer.cityId()),now);}
    Purchase buy(Rig r,long period)throws Exception{MarketQuoteService quotes=new MarketQuoteService(r.store,r.market);var q=quote(r,quotes,r.list(),period,100);return quotes.accept(q.id(),r.buyer.cityId(),r.buyer.nationId(),auth(r,period,r.buyer.cityId()),101);}
    void pay(Rig r,Purchase p)throws Exception{var d=r.market.leaseDue(10,0).getFirst();var proof=new TreasuryTransferPort.Receipt(p.transactionId(),p.buyerNation(),p.request().credits(),"SETTLEMENT",Instant.EPOCH,p.request().description());assertTrue(r.market.finish(d,new TreasuryTransferPort.Delivery(p.transactionId(),TreasuryTransferPort.Status.CONFIRMED,proof,"durable atomic settlement"),0));}
    @Test void declarationDoesNotGrantPassageAndChangingPermitBeforeConsentCannotReserveGoods()throws Exception{
        try(Rig r=rig()){
            Route route=routes.get(r.route);assertTrue(route.corridorRequired());assertTrue(route.requiresTransit());var quotes=new MarketQuoteService(r.store,r.market);Listing lot=r.list();
            assertThrows(SQLException.class,()->quote(r,quotes,lot,1,100));assertThrows(SQLException.class,()->r.logistics.createOffer(UUID.randomUUID(),r.route,Map.of("minecraft:bread",7L),Map.of("minecraft:iron_ingot",1L),r.auth(1,r.seller.cityId())));
            approve(r,route,"0.10",1);var old=quote(r,quotes,lot,1,100);assertEquals(new BigDecimal("0.70"),old.tollTotal());assertEquals(new BigDecimal("9.71"),old.total());assertEquals(20,r.market.listing(lot.id()).orElseThrow().availableUnits());
            approve(r,route,"0.10",3);assertThrows(SQLException.class,()->quotes.accept(old.id(),r.buyer.cityId(),r.buyer.nationId(),auth(r,2,r.buyer.cityId()),101));
            assertThrows(SQLException.class,()->r.market.buy(UUID.randomUUID(),lot.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),7,auth(r,2,r.buyer.cityId())));
            var fresh=quote(r,quotes,lot,2,102);Purchase p=quotes.accept(fresh.id(),r.buyer.cityId(),r.buyer.nationId(),auth(r,2,r.buyer.cityId()),103);assertEquals(Map.of(r.seller.nationId(),new BigDecimal("9.01"),third.nationId(),new BigDecimal("0.70")),p.request().credits());
            r.restart();var restartedQuotes=new MarketQuoteService(r.store,r.market);assertEquals(p,restartedQuotes.accept(fresh.id(),r.buyer.cityId(),r.buyer.nationId(),auth(r,3,r.buyer.cityId()),999999));assertEquals(route,r.logistics.route(r.route).orElseThrow());
        }
    }
    @Test void revokedOrRepricedTransitPausesBeforeMaintenanceAndSamePriceRenewalResumesWithoutChargingThirdWarehouse()throws Exception{
        try(Rig r=rig()){
            Route route=routes.get(r.route);approve(r,route,"0.10",1);Purchase p=buy(r,1);pay(r,p);r.market.ship(p.id(),auth(r,1));long thirdStock=r.store.stock(third.cityId()).get("minecraft:oak_planks"),originStock=r.store.stock(r.seller.cityId()).get("minecraft:oak_planks");
            permits.clear();assertEquals(1,r.logistics.settle(auth(r,2),10).paused());assertEquals(originStock,r.store.stock(r.seller.cityId()).get("minecraft:oak_planks"));
            approve(r,route,"0.20",3);assertEquals(1,r.logistics.settle(auth(r,3),10).paused());assertEquals(0,r.logistics.order(p.shipmentId()).orElseThrow().progressedPeriods());
            approve(r,route,"0.10",5);assertTrue(TransitCharges.sameEconomics(p.transit(),Map.of(third.nationId(),permits.get(route.id()))));r.restart();
            for(int period=4;period<4+route.travelPeriods();period++)r.logistics.settle(auth(r,period),10);
            assertEquals(OrderStatus.COMPLETED,r.logistics.order(p.shipmentId()).orElseThrow().status());assertEquals(thirdStock,r.store.stock(third.cityId()).get("minecraft:oak_planks"));assertTrue(r.store.stock(r.seller.cityId()).get("minecraft:oak_planks")<originStock);
            assertTrue(r.market.leaseDue(10,999999).isEmpty());assertEquals(1007,r.stock(r.buyer));r.market.reconcileDeliveries(10);assertEquals(new BigDecimal("8.75"),r.market.deliveryStats(null,null,10,0).getFirst().gross());
        }
    }
    @Test void fullRefundRetainsAllBeneficiariesAcrossRestartAndReturnsGoodsOnlyAfterExactReverseReceipt()throws Exception{
        try(Rig r=rig()){
            approve(r,routes.get(r.route),"0.10",1);Purchase p=buy(r,1);pay(r,p);r.market.ship(p.id(),auth(r,1));var refunds=new MarketRefundService(r.store,r.market);var refund=refunds.request(p.id(),auth(r,2,r.buyer.cityId()),r.buyer.nationId());refunds.accept(refund.id(),auth(r,2,r.seller.cityId()),r.seller.nationId());
            var work=refunds.leaseDue(10,0).getFirst();assertEquals(p.request().credits(),work.request().original().credits());var partial=new TreasuryRefundPort.Receipt(work.request().transactionId(),p.transactionId(),p.buyerNation(),Map.of(p.sellerNation(),p.total()),Instant.EPOCH);
            refunds.finish(work,new TreasuryRefundPort.Delivery(work.request().transactionId(),TreasuryTransferPort.Status.CONFIRMED,partial,"invalid partial reversal"),0);assertEquals(MarketRefundService.Status.REVIEW,refunds.refund(refund.id()).orElseThrow().status());assertEquals(OrderStatus.REFUNDING,r.logistics.order(p.shipmentId()).orElseThrow().status());assertEquals(980,r.stock(r.seller));
            r.restart();refunds=new MarketRefundService(r.store,r.market);refunds.retry(refund.id(),auth(r,3,r.buyer.cityId()),r.buyer.nationId());work=refunds.leaseDue(10,10000).getFirst();var full=new TreasuryRefundPort.Receipt(work.request().transactionId(),p.transactionId(),p.buyerNation(),p.request().credits(),Instant.EPOCH);
            refunds.finish(work,new TreasuryRefundPort.Delivery(work.request().transactionId(),TreasuryTransferPort.Status.CONFIRMED,full,"full reverse"),10000);refunds.complete(refund.id(),auth(r,3));assertEquals(987,r.stock(r.seller));assertEquals(1000,r.stock(r.buyer));assertEquals(PurchaseStatus.REFUNDED,r.market.purchase(p.id()).orElseThrow().status());
            r.restart();refunds=new MarketRefundService(r.store,r.market);refunds.complete(refund.id(),auth(r,4));assertEquals(987,r.stock(r.seller));assertTrue(refunds.leaseDue(10,999999).isEmpty());assertEquals(p.request(),r.market.purchase(p.id()).orElseThrow().request());
        }
    }
    @Test void barterRequiresAFreePermitAndPaidRerouteCannotChargeANewCorridorImplicitly()throws Exception{
        try(Rig r=rig()){
            Route original=routes.get(r.route);approve(r,original,"0.10",1);assertThrows(SQLException.class,()->r.logistics.createOffer(UUID.randomUUID(),r.route,Map.of("minecraft:bread",7L),Map.of("minecraft:iron_ingot",1L),auth(r,1,r.seller.cityId())));
            Purchase p=buy(r,1);pay(r,p);r.market.ship(p.id(),auth(r,1));Route alternate=r.logistics.createRoute(UUID.randomUUID(),r.seller.cityId(),r.buyer.cityId(),original.nodes(),Mode.LAND,auth(r,2,r.seller.cityId()));routes.put(alternate.id(),alternate);approve(r,alternate,"0.10",1);
            assertThrows(SQLException.class,()->r.logistics.proposeReroute(UUID.randomUUID(),p.shipmentId(),alternate.id(),auth(r,2,r.seller.cityId())));
            approve(r,alternate,"0.00",3);var reroute=r.logistics.proposeReroute(UUID.randomUUID(),p.shipmentId(),alternate.id(),auth(r,2,r.seller.cityId()));r.logistics.acceptReroute(reroute.id(),auth(r,2,r.buyer.cityId()));assertEquals(alternate.id(),r.logistics.order(p.shipmentId()).orElseThrow().routeId());assertTrue(r.market.leaseDue(10,999999).isEmpty());
            var barter=r.logistics.createOffer(UUID.randomUUID(),alternate.id(),Map.of("minecraft:bread",7L),Map.of("minecraft:iron_ingot",1L),auth(r,2,r.seller.cityId()));r.logistics.accept(barter.id(),auth(r,2,r.buyer.cityId()));
            approve(r,alternate,"0.20",5);assertEquals(2,r.logistics.settle(auth(r,3),10).paused());assertEquals(OrderStatus.PAUSED,r.logistics.order(p.shipmentId()).orElseThrow().status());
        }
    }
    @Test void permitDraftIsImmutableBoundedAndSurvivesMissingLocalAcknowledgement()throws Exception{
        try(Rig r=rig()){
            Permit approved=approve(r,routes.get(r.route),"0.10",1);UUID id=UUID.randomUUID();var service=r.logistics.transitProposals();var draft=service.prepare(id,r.route,r.actor,approved.terms(),0,100);
            assertNull(draft.approvedRevision());assertEquals(draft,service.prepare(id,r.route,r.actor,approved.terms(),0,100));
            assertThrows(SQLException.class,()->service.prepare(id,r.route,r.actor,approved.terms(),1,100));
            Receipt receipt=new Receipt(id,Action.APPROVE,0,approved);r.store.execute("CREATE TRIGGER reject_permit_ack BEFORE UPDATE ON logistics_transit_proposal BEGIN SELECT RAISE(ABORT,'lost local ack'); END");assertThrows(SQLException.class,()->service.acknowledge(id,receipt));assertNull(service.proposal(id).orElseThrow().approvedRevision());r.store.execute("DROP TRIGGER reject_permit_ack");
            r.restart();var restored=r.logistics.transitProposals();assertEquals(draft,restored.proposal(id).orElseThrow());var acknowledged=restored.acknowledge(id,receipt);assertEquals(1,acknowledged.approvedRevision());assertEquals(acknowledged,restored.acknowledge(id,receipt));assertEquals(acknowledged,restored.prepare(id,r.route,r.actor,approved.terms(),0,100));
            for(int i=0;i<20;i++)restored.prepare(UUID.randomUUID(),r.route,r.actor,approved.terms(),1,100);
            assertThrows(SQLException.class,()->restored.prepare(UUID.randomUUID(),r.route,r.actor,approved.terms(),1,100));restored.prepare(UUID.randomUUID(),r.route,r.actor,approved.terms(),1,60_100);
            assertEquals(1,restored.proposal(id).orElseThrow().approvedRevision());
        }
    }
}

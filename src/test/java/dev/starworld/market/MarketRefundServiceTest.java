package dev.starworld.market;

import dev.starworld.integration.*;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.market.MarketRefundService.*;
import dev.starworld.market.MarketState.Purchase;
import dev.starworld.market.MarketState.PurchaseStatus;
import dev.starworld.market.MarketState.Listing;
import dev.starworld.market.MarketState.ListingStatus;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.starworld.market.MarketServiceTest.Rig;
import static dev.starworld.market.MarketServiceTest.BREAD;
import static org.junit.jupiter.api.Assertions.*;

class MarketRefundServiceTest {
    @TempDir java.nio.file.Path temp;
    private Rig rig()throws Exception{MarketServiceTest fixture=new MarketServiceTest();fixture.temp=temp;return fixture.new Rig();}
    private Purchase paid(Rig r,boolean shipped)throws Exception{
        Purchase p=r.buy(r.list());var dispatch=r.lease(0);r.market.finish(dispatch,r.paid(dispatch),0);
        if(shipped)r.market.ship(p.id(),r.auth(2));return r.market.purchase(p.id()).orElseThrow();
    }
    private Refund consent(Rig r,MarketRefundService refunds,Purchase p)throws Exception{
        Refund request=refunds.request(p.id(),r.auth(3,r.buyer.cityId()),r.buyer.nationId());
        return refunds.accept(request.id(),r.auth(3,r.seller.cityId()),r.seller.nationId());
    }
    private TreasuryRefundPort.Delivery proof(Dispatch dispatch){var request=dispatch.request();return new TreasuryRefundPort.Delivery(request.transactionId(),TreasuryTransferPort.Status.CONFIRMED,
            new TreasuryRefundPort.Receipt(request.transactionId(),request.original().transactionId(),request.original().payer(),Map.of(request.original().receiver(),request.original().amount()),java.time.Instant.parse("2026-11-25T00:00:00Z")),"persisted refund");}
    @Test void bilateralConsentHoldsTransportAndRestartReconcilesOneMoneyAndCargoReturn()throws Exception{
        try(Rig r=rig()){
            Purchase p=paid(r,true);MarketRefundService refunds=new MarketRefundService(r.store,r.market);
            Refund request=refunds.request(p.id(),r.auth(3,r.buyer.cityId()),r.buyer.nationId());
            assertEquals(OrderStatus.IN_TRANSIT,r.logistics.order(p.shipmentId()).orElseThrow().status());
            assertThrows(SQLException.class,()->refunds.accept(request.id(),r.auth(3,r.buyer.cityId()),r.buyer.nationId()));
            refunds.accept(request.id(),r.auth(3,r.seller.cityId()),r.seller.nationId());
            assertEquals(OrderStatus.REFUNDING,r.logistics.order(p.shipmentId()).orElseThrow().status());
            assertEquals(PurchaseStatus.REFUNDING,r.market.purchase(p.id()).orElseThrow().status());
            assertThrows(SQLException.class,()->r.market.ship(p.id(),r.auth(4)));
            assertThrows(SQLException.class,()->r.logistics.cancel(p.shipmentId(),r.auth(4,r.buyer.cityId())));
            r.logistics.settle(r.auth(4),10);assertEquals(1000,r.stock(r.buyer));assertEquals(980,r.stock(r.seller));
            Dispatch lost=refunds.leaseDue(10,0).getFirst();assertTrue(refunds.leaseDue(10,100).isEmpty());
            r.restart();MarketRefundService restored=new MarketRefundService(r.store,r.market);
            Dispatch retry=restored.leaseDue(10,MarketService.LEASE_MILLIS).getFirst();assertEquals(lost.request(),retry.request());
            assertFalse(restored.finish(lost,proof(lost),MarketService.LEASE_MILLIS));
            assertTrue(restored.finish(retry,proof(retry),MarketService.LEASE_MILLIS));
            assertEquals(Status.MONEY_RETURNED,restored.refund(request.id()).orElseThrow().status());assertEquals(980,r.stock(r.seller));
            assertEquals(Status.COMPLETED,restored.complete(request.id(),r.auth(5)).status());
            assertEquals(Status.COMPLETED,restored.complete(request.id(),r.auth(5)).status());
            assertEquals(PurchaseStatus.REFUNDED,r.market.purchase(p.id()).orElseThrow().status());assertEquals(OrderStatus.CANCELLED,r.logistics.order(p.shipmentId()).orElseThrow().status());
            assertEquals(1000,r.stock(r.buyer));assertEquals(1000,r.stock(r.seller));
            Listing listing=r.market.listing(p.listingId()).orElseThrow();assertEquals(20,listing.returnedUnits());assertEquals(0,listing.shippedUnits());assertEquals(ListingStatus.CANCELLED,listing.status());
            assertEquals(0,r.market.reconcileDeliveries(10));assertTrue(r.market.deliveryStats(null,null,10,0).isEmpty());
            assertEquals(1,r.market.history(null,10,0).size());assertTrue(restored.leaseDue(10,999999).isEmpty());
            r.restart();assertEquals(Status.COMPLETED,new MarketRefundService(r.store,r.market).complete(request.id(),r.auth(5)).status());assertEquals(1000,r.stock(r.seller));
        }
    }
    @Test void paidUnshippedSliceRefundPreservesOtherReservationsAndLotRemainder()throws Exception{
        try(Rig r=rig()){
            Listing lot=r.list();Purchase p=r.partial(lot,r.buyer,r.route,7,1);Purchase other=r.partial(lot,r.buyer,r.route,5,1);
            for(var d:r.market.leaseDue(10,0))r.market.finish(d,r.paid(d),0);
            MarketRefundService refunds=new MarketRefundService(r.store,r.market);Refund request=consent(r,refunds,p);Dispatch work=refunds.leaseDue(10,0).getFirst();refunds.finish(work,proof(work),0);refunds.complete(request.id(),r.auth(4));
            assertEquals(987,r.stock(r.seller));assertTrue(r.logistics.order(p.shipmentId()).isEmpty());
            Listing after=r.market.listing(lot.id()).orElseThrow();assertEquals(8,after.availableUnits());assertEquals(5,after.reservedUnits());assertEquals(7,after.returnedUnits());assertEquals(ListingStatus.AVAILABLE,after.status());
            r.market.ship(other.id(),r.auth(4));r.market.cancelListing(lot.id(),r.auth(4,r.seller.cityId()));
            for(int i=5;i<10;i++)r.logistics.settle(r.auth(i),10);
            assertEquals(995,r.stock(r.seller));assertEquals(1005,r.stock(r.buyer));assertEquals(1,r.market.reconcileDeliveries(10));
        }
    }
    @Test void unknownOrWrongRefundReceiptRetainsGoodsAndExplicitRetryUsesOriginalId()throws Exception{
        try(Rig r=rig()){
            Purchase p=paid(r,true);MarketRefundService refunds=new MarketRefundService(r.store,r.market);Refund request=consent(r,refunds,p);
            Dispatch first=refunds.leaseDue(10,0).getFirst();refunds.finish(first,new TreasuryRefundPort.Delivery(first.request().transactionId(),TreasuryTransferPort.Status.RETRY,null,"unavailable"),0);
            assertTrue(refunds.leaseDue(10,1).isEmpty());assertThrows(SQLException.class,()->refunds.complete(request.id(),r.auth(4)));
            Dispatch second=refunds.leaseDue(10,10000).getFirst();var actual=proof(second).receipt();
            var wrong=new TreasuryRefundPort.Receipt(actual.transactionId(),"wrong-payment",actual.payer(),actual.credits(),actual.appliedAt());
            refunds.finish(second,new TreasuryRefundPort.Delivery(actual.transactionId(),TreasuryTransferPort.Status.CONFIRMED,wrong,"mismatch"),10000);
            assertEquals(Status.REVIEW,refunds.refund(request.id()).orElseThrow().status());
            refunds.retry(request.id(),r.auth(4,r.buyer.cityId()),r.buyer.nationId());Dispatch retry=refunds.leaseDue(10,10001).getFirst();assertEquals(first.request(),retry.request());
            refunds.finish(retry,proof(retry),10001);refunds.complete(request.id(),r.auth(5));assertEquals(1000,r.stock(r.seller));assertEquals(1000,r.stock(r.buyer));
        }
    }
    @Test void fullWarehouseOrChangedNationKeepsMoneyReturnedCargoHeldUntilOriginalOwnerCanReceive()throws Exception{
        try(Rig r=rig()){
            Purchase p=paid(r,true);MarketRefundService refunds=new MarketRefundService(r.store,r.market);Refund request=consent(r,refunds,p);Dispatch d=refunds.leaseDue(10,0).getFirst();refunds.finish(d,proof(d),0);
            r.store.cityCapacity(r.seller.cityId(),980);assertThrows(SQLException.class,()->refunds.complete(request.id(),r.auth(4)));
            assertEquals(Status.MONEY_RETURNED,refunds.refund(request.id()).orElseThrow().status());assertEquals(980,r.stock(r.seller));assertEquals(OrderStatus.REFUNDING,r.logistics.order(p.shipmentId()).orElseThrow().status());
            r.store.cityCapacity(r.seller.cityId(),10000);r.ownership.put(r.seller.cityId(),UUID.randomUUID());assertThrows(SQLException.class,()->refunds.complete(request.id(),r.auth(4)));
            r.ownership.put(r.seller.cityId(),r.seller.nationId());refunds.complete(request.id(),r.auth(5));assertEquals(1000,r.stock(r.seller));
        }
    }
    @Test void failedCargoReturnTransactionCannotPartiallyReleaseRefundedGoods()throws Exception{
        try(Rig r=rig()){
            Purchase p=paid(r,true);MarketRefundService refunds=new MarketRefundService(r.store,r.market);Refund request=consent(r,refunds,p);Dispatch d=refunds.leaseDue(10,0).getFirst();refunds.finish(d,proof(d),0);
            r.store.execute("CREATE TRIGGER reject_refund_complete BEFORE UPDATE ON market_refund WHEN NEW.state='COMPLETED' BEGIN SELECT RAISE(ABORT,'injected return failure'); END");
            assertThrows(SQLException.class,()->refunds.complete(request.id(),r.auth(4)));assertEquals(980,r.stock(r.seller));assertEquals(OrderStatus.REFUNDING,r.logistics.order(p.shipmentId()).orElseThrow().status());
            assertEquals(PurchaseStatus.REFUNDING,r.market.purchase(p.id()).orElseThrow().status());r.store.execute("DROP TRIGGER reject_refund_complete");
            refunds.complete(request.id(),r.auth(5));assertEquals(1000,r.stock(r.seller));
        }
    }
    @Test void completedDeliveryCannotBeRefundedAndUnacceptedRequestDoesNotBlockDelivery()throws Exception{
        try(Rig r=rig()){
            Purchase p=paid(r,true);MarketRefundService refunds=new MarketRefundService(r.store,r.market);Refund request=refunds.request(p.id(),r.auth(3,r.buyer.cityId()),r.buyer.nationId());
            for(int i=4;i<10;i++)r.logistics.settle(r.auth(i),10);
            assertEquals(OrderStatus.COMPLETED,r.logistics.order(p.shipmentId()).orElseThrow().status());assertThrows(SQLException.class,()->refunds.accept(request.id(),r.auth(10,r.seller.cityId()),r.seller.nationId()));
            assertTrue(refunds.leaseDue(10,999999).isEmpty());assertEquals(1020,r.stock(r.buyer));assertEquals(980,r.stock(r.seller));
            refunds.cancel(request.id(),r.auth(10,r.buyer.cityId()),r.buyer.nationId());assertThrows(SQLException.class,()->refunds.request(p.id(),r.auth(10,r.buyer.cityId()),r.buyer.nationId()));
        }
    }
    @Test void refundRequestCancellationIsSafeUntilSellerConsentAndStaleAuthorityIsRejected()throws Exception{
        try(Rig r=rig()){
            Purchase p=paid(r,false);MarketRefundService refunds=new MarketRefundService(r.store,r.market);
            assertThrows(SQLException.class,()->refunds.request(p.id(),r.auth(3,r.seller.cityId()),r.seller.nationId()));
            Refund request=refunds.request(p.id(),r.auth(3,r.buyer.cityId()),r.buyer.nationId());refunds.cancel(request.id(),r.auth(3,r.buyer.cityId()),r.buyer.nationId());
            assertEquals(PurchaseStatus.PAID,r.market.purchase(p.id()).orElseThrow().status());assertTrue(refunds.leaseDue(10,0).isEmpty());
            assertEquals(request.id(),refunds.request(p.id(),r.auth(4,r.buyer.cityId()),r.buyer.nationId()).id());
            assertThrows(SQLException.class,()->refunds.accept(request.id(),r.auth(2,r.seller.cityId()),r.seller.nationId()));
            refunds.accept(request.id(),r.auth(4,r.seller.cityId()),r.seller.nationId());
            assertThrows(SQLException.class,()->refunds.cancel(request.id(),r.auth(4,r.buyer.cityId()),r.buyer.nationId()));
        }
    }
    @Test void routeCountsCombineRefundHoldsAndOrdinaryPausedCargo()throws Exception{
        try(Rig r=rig()){
            Purchase held=r.buy(r.list()),other=r.buy(r.list());for(var dispatch:r.market.leaseDue(10,0))r.market.finish(dispatch,r.paid(dispatch),0);
            r.market.ship(held.id(),r.auth(2));r.market.ship(other.id(),r.auth(2));MarketRefundService refunds=new MarketRefundService(r.store,r.market);consent(r,refunds,held);
            var a=r.auth(4);r.logistics.settle(new AuthoritySnapshot(a.actor(),a.period(),a.cityNations(),a.managedCities(),a.controlledAnchors(),a.validNodes(),Set.of()),10);
            assertEquals(OrderStatus.REFUNDING,r.logistics.order(held.shipmentId()).orElseThrow().status());assertEquals(OrderStatus.PAUSED,r.logistics.order(other.shipmentId()).orElseThrow().status());
            var traffic=r.logistics.transportStatus(r.route,4);assertEquals(2,traffic.waitingOrders());assertEquals(2,traffic.pausedOrders());assertEquals(2,traffic.pausedReasons().values().stream().mapToInt(Integer::intValue).sum());
        }
    }
}

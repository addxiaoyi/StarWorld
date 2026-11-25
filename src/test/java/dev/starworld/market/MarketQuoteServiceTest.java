package dev.starworld.market;

import dev.starworld.integration.*;
import dev.starworld.market.MarketState.*;
import dev.starworld.market.MarketServiceTest.Rig;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarketQuoteServiceTest {
    @TempDir java.nio.file.Path temp;
    private Rig rig()throws Exception{MarketServiceTest fixture=new MarketServiceTest();fixture.temp=temp;return fixture.new Rig();}
    private MarketQuoteService.Quote quote(Rig r,MarketQuoteService quotes,Listing l,long units,String fee,long period,long now)throws Exception{
        return quotes.prepare(l.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),units,new MarketTaxPort.Quote(UUID.randomUUID(),r.seller.nationId(),r.actor,l.unitPrice().multiply(BigDecimal.valueOf(units)),new BigDecimal(fee)),r.auth(period,r.buyer.cityId()),now);
    }
    @Test void previewDoesNotReserveAndAcceptedTaxIsFrozenIntoOneExactPaymentAcrossRestart()throws Exception{
        try(Rig r=rig()){
            Listing l=r.list();MarketQuoteService quotes=new MarketQuoteService(r.store,r.market);var q=quote(r,quotes,l,7,"0.26",1,100);
            assertEquals(20,r.market.listing(l.id()).orElseThrow().availableUnits());assertTrue(r.market.leaseDue(10,1000).isEmpty());assertEquals(980,r.stock(r.seller));
            Purchase p=quotes.accept(q.id(),r.buyer.cityId(),r.buyer.nationId(),r.auth(2,r.buyer.cityId()),500);
            assertEquals(new BigDecimal("8.75"),p.goodsTotal());assertEquals(new BigDecimal("0.26"),p.taxAmount());assertEquals(new BigDecimal("9.01"),p.total());assertEquals(p.total(),p.request().amount());assertEquals(q.id(),p.taxQuote());
            assertEquals(13,r.market.listing(l.id()).orElseThrow().availableUnits());r.restart();quotes=new MarketQuoteService(r.store,r.market);
            assertEquals(p,quotes.accept(q.id(),r.buyer.cityId(),r.buyer.nationId(),r.auth(3,r.buyer.cityId()),999999));
            assertEquals(0,quotes.cleanup(999999,50));assertEquals(new BigDecimal("0.26"),quotes.quote(q.id()).orElseThrow().taxAmount());
        }
    }
    @Test void taxedPaymentRefundsTheExactCombinedAmountWhileOnlyReturningTheOriginalGoods()throws Exception{
        try(Rig r=rig()){
            Listing l=r.list();MarketQuoteService quotes=new MarketQuoteService(r.store,r.market);var q=quote(r,quotes,l,7,"0.26",1,0);
            Purchase p=quotes.accept(q.id(),r.buyer.cityId(),r.buyer.nationId(),r.auth(2,r.buyer.cityId()),10);var d=r.lease(0);r.market.finish(d,r.paid(d),0);r.market.ship(p.id(),r.auth(3));
            MarketRefundService refunds=new MarketRefundService(r.store,r.market);var request=refunds.request(p.id(),r.auth(4,r.buyer.cityId()),r.buyer.nationId());refunds.accept(request.id(),r.auth(4,r.seller.cityId()),r.seller.nationId());var work=refunds.leaseDue(10,0).getFirst();
            assertEquals(new BigDecimal("9.01"),work.request().original().amount());var reverse=new TreasuryRefundPort.Receipt(work.request().transactionId(),p.transactionId(),p.buyerNation(),Map.of(p.sellerNation(),p.total()),Instant.EPOCH);
            refunds.finish(work,new TreasuryRefundPort.Delivery(work.request().transactionId(),TreasuryTransferPort.Status.CONFIRMED,reverse,"durable reverse"),0);refunds.complete(request.id(),r.auth(5));
            assertEquals(987,r.stock(r.seller));assertEquals(1000,r.stock(r.buyer));assertEquals(new BigDecimal("0.26"),r.market.purchase(p.id()).orElseThrow().taxAmount());assertEquals(7,r.market.history(null,10,0).getFirst().units());assertEquals(0,r.market.reconcileDeliveries(10));
            assertEquals(0,quotes.cleanup(999999,50));
        }
    }
    @Test void expiredQuoteWrongNationAndStockCompetitionCannotCreateOrOverreserveAPurchase()throws Exception{
        try(Rig r=rig()){
            Listing l=r.list();MarketQuoteService quotes=new MarketQuoteService(r.store,r.market);var expired=quote(r,quotes,l,7,"0.26",1,0);
            assertThrows(SQLException.class,()->quotes.accept(expired.id(),r.buyer.cityId(),r.buyer.nationId(),r.auth(1,r.buyer.cityId()),60000));
            var first=quote(r,quotes,l,15,"0.56",1,60000);var second=quote(r,quotes,l,15,"0.56",1,60000);
            assertThrows(SQLException.class,()->quotes.accept(first.id(),r.seller.cityId(),r.seller.nationId(),r.auth(2,r.seller.cityId()),60001));
            quotes.accept(first.id(),r.buyer.cityId(),r.buyer.nationId(),r.auth(2,r.buyer.cityId()),60001);
            assertThrows(SQLException.class,()->quotes.accept(second.id(),r.buyer.cityId(),r.buyer.nationId(),r.auth(2,r.buyer.cityId()),60001));
            assertEquals(5,r.market.listing(l.id()).orElseThrow().availableUnits());assertEquals(15,r.market.listing(l.id()).orElseThrow().reservedUnits());assertEquals(1,r.market.purchases(r.buyer.cityId(),10,0).size());
        }
    }
    @Test void changedTaxAssessmentCannotReplaceAnExistingQuoteAndChangedOwnerCannotConfirmIt()throws Exception{
        try(Rig r=rig()){
            Listing l=r.list();MarketQuoteService quotes=new MarketQuoteService(r.store,r.market);var q=quote(r,quotes,l,7,"0.26",1,0);
            MarketTaxPort.Quote changed=new MarketTaxPort.Quote(q.id(),r.seller.nationId(),r.actor,q.goodsTotal(),new BigDecimal("1.00"));
            assertThrows(SQLException.class,()->quotes.prepare(l.id(),r.route,r.buyer.cityId(),r.buyer.nationId(),7,changed,r.auth(2,r.buyer.cityId()),100));
            assertEquals(new BigDecimal("0.26"),quotes.quote(q.id()).orElseThrow().taxAmount());r.ownership.put(r.seller.cityId(),UUID.randomUUID());
            assertThrows(SQLException.class,()->quotes.accept(q.id(),r.buyer.cityId(),r.buyer.nationId(),r.auth(2,r.buyer.cityId()),100));assertTrue(r.market.leaseDue(10,0).isEmpty());assertEquals(20,r.market.listing(l.id()).orElseThrow().availableUnits());
        }
    }
    @Test void quoteAcceptanceFailureRollsBackPurchaseEscrowAndFrozenTermsTogether()throws Exception{
        try(Rig r=rig()){
            Listing l=r.list();MarketQuoteService quotes=new MarketQuoteService(r.store,r.market);var q=quote(r,quotes,l,7,"0.26",1,0);
            r.store.execute("CREATE TRIGGER fail_quote_accept BEFORE UPDATE ON market_quote WHEN NEW.purchase IS NOT NULL BEGIN SELECT RAISE(ABORT,'injected quote failure'); END");
            assertThrows(SQLException.class,()->quotes.accept(q.id(),r.buyer.cityId(),r.buyer.nationId(),r.auth(2,r.buyer.cityId()),100));
            assertTrue(r.market.purchase(q.id()).isEmpty());assertNull(quotes.quote(q.id()).orElseThrow().purchaseId());assertEquals(20,r.market.listing(l.id()).orElseThrow().availableUnits());assertEquals(980,r.stock(r.seller));
            r.store.execute("DROP TRIGGER fail_quote_accept");quotes.accept(q.id(),r.buyer.cityId(),r.buyer.nationId(),r.auth(2,r.buyer.cityId()),100);assertEquals(7,r.market.listing(l.id()).orElseThrow().reservedUnits());
        }
    }
    @Test void quoteQueueIsBoundedAndCleanupNeverDeletesAcceptedPaymentTerms()throws Exception{
        try(Rig r=rig()){
            Listing l=r.list();MarketQuoteService quotes=new MarketQuoteService(r.store,r.market);List<MarketQuoteService.Quote> rows=new ArrayList<>();
            for(int i=0;i<20;i++)rows.add(quote(r,quotes,l,1,"0.00",1,0));
            assertThrows(SQLException.class,()->quote(r,quotes,l,1,"0.00",1,0));quotes.accept(rows.getFirst().id(),r.buyer.cityId(),r.buyer.nationId(),r.auth(2,r.buyer.cityId()),100);
            quote(r,quotes,l,1,"0.00",2,100);assertEquals(10,quotes.cleanup(60001,10));assertEquals(9,quotes.cleanup(60001,10));assertTrue(quotes.quote(rows.getFirst().id()).isPresent());assertEquals(1,quotes.cleanup(60100,10));assertEquals(0,quotes.cleanup(999999,50));
        }
    }
}

package dev.starworld.integration;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.integration.CoreExtensionPort.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResourceOutboxTest {
    @TempDir Path temp;
    private final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"城","国",1,"test");
    private static final Map<String,Long> COST=Map.of("minecraft:iron_ingot",4L);
    private static final Map<String,Long> RESOURCES=Map.of("strategic",2L);
    private void seed(WorldStore s)throws Exception{s.ensureCity(city);s.stock(city.cityId(),"minecraft:iron_ingot",20);}
    private ResourceOutbox.Entry enqueue(ResourceOutbox o)throws Exception{return o.enqueue("city/period/recipe/one",city.cityId(),city.nationId(),COST,RESOURCES,"mayor");}
    private ResourceReceipt receipt(ResourceRequest r){return new ResourceReceipt(r.transactionId(),r.nationId(),r.resources(),"GRANT","OTHER",Instant.parse("2026-11-19T00:00:00Z"));}

    @Test void localStockAndStableRequestSurviveRestartWithoutSecondDebit()throws Exception {
        Path path=temp.resolve("world.db");String id;
        try(WorldStore s=new WorldStore(path,1000)){seed(s);ResourceOutbox o=new ResourceOutbox(s);var first=enqueue(o);id=first.transactionId();assertEquals(16,s.stock(city.cityId()).get("minecraft:iron_ingot"));assertEquals(first,enqueue(o));}
        try(WorldStore s=new WorldStore(path,1000)){ResourceOutbox o=new ResourceOutbox(s);assertEquals(id,enqueue(o).transactionId());assertEquals(16,s.stock(city.cityId()).get("minecraft:iron_ingot"));assertEquals("PENDING",o.find(id).orElseThrow().state());}
    }
    @Test void failureAfterLocalDebitRollsBackOutboxAndDebitTogether()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("rollback.db"),1000)){seed(s);ResourceOutbox o=new ResourceOutbox(s);
            s.execute("CREATE TRIGGER reject_outbox BEFORE INSERT ON resource_outbox BEGIN SELECT RAISE(ABORT,'power loss'); END");
            assertThrows(SQLException.class,()->enqueue(o));assertEquals(20,s.stock(city.cityId()).get("minecraft:iron_ingot"));assertTrue(o.entries(10).isEmpty());}
    }
    @Test void sameBusinessKeyDifferentPayloadIsRejectedWithoutAnotherDebit()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("conflict.db"),1000)){seed(s);ResourceOutbox o=new ResourceOutbox(s);enqueue(o);
            assertThrows(SQLException.class,()->o.enqueue("city/period/recipe/one",city.cityId(),city.nationId(),COST,Map.of("strategic",3L),"mayor"));
            assertEquals(16,s.stock(city.cityId()).get("minecraft:iron_ingot"));assertEquals(1,o.entries(10).size());}
    }
    @Test void separateWorldInstallationsCannotCollideOnCoreTransactionId()throws Exception {
        try(WorldStore one=new WorldStore(temp.resolve("one.db"),1000);WorldStore two=new WorldStore(temp.resolve("two.db"),1000)){
            seed(one);seed(two);assertNotEquals(enqueue(new ResourceOutbox(one)).transactionId(),enqueue(new ResourceOutbox(two)).transactionId());}
    }
    @Test void coreCommitBeforeLocalAckCrashReusesReceiptAndNeverGrantsTwice()throws Exception {
        Path path=temp.resolve("crash.db"),ledger=temp.resolve("core.db");String id;
        try(WorldStore s=new WorldStore(path,1000)){seed(s);ResourceOutbox o=new ResourceOutbox(s);id=enqueue(o).transactionId();var dispatch=o.leaseDue(1,1000).getFirst();
            durableCoreGrant(ledger,dispatch.entry().request()); /* simulated process death before o.finish */ }
        try(WorldStore s=new WorldStore(path,1000)){ResourceOutbox o=new ResourceOutbox(s);var retry=o.leaseDue(1,1000+ResourceOutbox.LEASE_MILLIS).getFirst();assertEquals(id,retry.entry().transactionId());
            Delivery delivery=durableCoreGrant(ledger,retry.entry().request());assertTrue(o.finish(retry,delivery,40_000));assertEquals("ACKED",o.find(id).orElseThrow().state());assertTrue(o.leaseDue(1,1_000_000).isEmpty());assertEquals(16,s.stock(city.cityId()).get("minecraft:iron_ingot"));}
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+ledger);Statement st=c.createStatement();ResultSet r=st.executeQuery("SELECT SUM(amount),COUNT(*) FROM receipt")){assertTrue(r.next());assertEquals(2,r.getLong(1));assertEquals(1,r.getLong(2));}
    }
    private Delivery durableCoreGrant(Path ledger,ResourceRequest request)throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+ledger);Statement st=c.createStatement()){
            st.execute("CREATE TABLE IF NOT EXISTS receipt(id TEXT PRIMARY KEY,amount INTEGER NOT NULL)");
            try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO receipt(id,amount) VALUES(?,?)")){p.setString(1,request.transactionId());p.setLong(2,request.resources().get("strategic"));p.executeUpdate();}
            return new Delivery(request.transactionId(),DeliveryStatus.CONFIRMED,receipt(request),"durable receipt");
        }
    }
    @Test void staleLeaseCannotAcknowledgeOrOverwriteCurrentAttempt()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("lease.db"),1000)){seed(s);ResourceOutbox o=new ResourceOutbox(s);enqueue(o);var old=o.leaseDue(1,0).getFirst();assertTrue(o.leaseDue(1,1000).isEmpty());
            var next=o.leaseDue(1,ResourceOutbox.LEASE_MILLIS).getFirst();var ok=new Delivery(old.entry().transactionId(),DeliveryStatus.CONFIRMED,receipt(old.entry().request()),"ok");
            assertFalse(o.finish(old,ok,31_000));assertEquals("IN_FLIGHT",o.find(ok.transactionId()).orElseThrow().state());assertTrue(o.finish(next,ok,32_000));assertEquals("ACKED",o.find(ok.transactionId()).orElseThrow().state());}
    }
    @Test void receiptWithDifferentNationOrOperationIsQuarantinedWithoutRefund()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("receipt.db"),1000)){seed(s);ResourceOutbox o=new ResourceOutbox(s);var entry=enqueue(o);var dispatch=o.leaseDue(1,0).getFirst();
            var falseReceipt=new ResourceReceipt(entry.transactionId(),city.nationId(),RESOURCES,"CONSUME","OTHER",Instant.now());
            o.finish(dispatch,new Delivery(entry.transactionId(),DeliveryStatus.CONFIRMED,falseReceipt,"wrong operation"),1);
            assertEquals("REVIEW",o.find(entry.transactionId()).orElseThrow().state());assertEquals(16,s.stock(city.cityId()).get("minecraft:iron_ingot"));assertTrue(o.leaseDue(10,1_000_000).isEmpty());
            assertThrows(SQLException.class,()->o.cancelUnsent(entry.transactionId(),"admin"));o.retry(entry.transactionId(),"admin");assertEquals(1,o.leaseDue(1,2).size());}
    }
    @Test void rejectedExportRequiresAuditedRetryAndCannotBeBlindlyRefunded()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("reject.db"),1000)){seed(s);ResourceOutbox o=new ResourceOutbox(s);var e=enqueue(o);var d=o.leaseDue(1,0).getFirst();
            o.finish(d,new Delivery(e.transactionId(),DeliveryStatus.REJECTED,null,"core module rejected"),1);assertTrue(o.leaseDue(1,999999).isEmpty());
            assertThrows(SQLException.class,()->o.cancelUnsent(e.transactionId(),"admin"));o.retry(e.transactionId(),"admin");assertEquals(1,o.leaseDue(1,2).size());
            long audits=s.query("SELECT COUNT(*) FROM audit WHERE action LIKE 'CORE_EXPORT_%'",r->r.getLong(1)).getFirst();assertEquals(3,audits);}
    }
    @Test void unsentCancelReturnsCostExactlyOnceAndRefusesReenqueueReplay()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("cancel.db"),1000)){seed(s);ResourceOutbox o=new ResourceOutbox(s);var e=enqueue(o);o.cancelUnsent(e.transactionId(),"admin");assertEquals(20,s.stock(city.cityId()).get("minecraft:iron_ingot"));
            assertThrows(SQLException.class,()->o.cancelUnsent(e.transactionId(),"admin"));assertEquals("CANCELLED",enqueue(o).state());assertTrue(o.leaseDue(1,0).isEmpty());}
    }
    @Test void nationChangeAndInsufficientStockRejectWithoutCreatingOutbox()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("ownership.db"),1000)){seed(s);ResourceOutbox o=new ResourceOutbox(s);
            assertThrows(SQLException.class,()->o.enqueue("wrong-nation",city.cityId(),UUID.randomUUID(),COST,RESOURCES,"mayor"));
            assertThrows(SQLException.class,()->o.enqueue("too-costly",city.cityId(),city.nationId(),Map.of("minecraft:iron_ingot",21L),RESOURCES,"mayor"));assertTrue(o.entries(10).isEmpty());assertEquals(20,s.stock(city.cityId()).get("minecraft:iron_ingot"));}
    }
}

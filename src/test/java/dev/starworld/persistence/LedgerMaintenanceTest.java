package dev.starworld.persistence;

import dev.starworld.domain.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.evolution.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LedgerMaintenanceTest {
    @TempDir Path temp;
    WorldStore open()throws Exception {WorldStore s=new WorldStore(temp.resolve("ledger.db"),100000);s.execute("CREATE TABLE IF NOT EXISTS evolution_facility(city TEXT,building TEXT,period INTEGER,PRIMARY KEY(city,building,period))");return s;}
    @Test void auditArchiveIsBoundedLosslessAndDurableWithoutTouchingReceipts()throws Exception {
        var settings=new LedgerMaintenance.Settings(1,1000,2);UUID city=UUID.randomUUID(),nation=UUID.randomUUID();
        try(WorldStore s=open()){s.ensureCity(new CoreCity(city,nation,"城","国",1,"test"));s.execute("INSERT INTO transfer(id,player,city,direction,item,amount,state,created) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),UUID.randomUUID(),city,"DEPOSIT","minecraft:bread",1,"PREPARED",1);
            for(int i=0;i<5;i++)s.execute("INSERT INTO audit(at,actor,action) VALUES(?,?,?)",1,"玩家"+i,"中文\n事件\\"+i);s.execute("INSERT INTO audit(at,actor,action) VALUES(?,?,?)",200_000_000,"new","keep");
            LedgerMaintenance m=new LedgerMaintenance(s,settings);assertEquals(2,m.sweep(2000,200_000_000).auditsArchived());assertEquals(4,s.query("SELECT COUNT(*) FROM audit",r->r.getInt(1)).getFirst());var archive=m.archives(0,10).getFirst();var read=m.readArchive(archive.id());assertEquals(2,read.size());assertEquals("中文\n事件\\0",read.getFirst().action());assertEquals("玩家0",read.getFirst().actor());assertEquals(1,s.pending().size());}
        try(WorldStore s=open()){LedgerMaintenance m=new LedgerMaintenance(s,settings);assertEquals(2,m.readArchive(m.archives(0,10).getFirst().id()).size());m.sweep(2000,200_000_000);m.sweep(2000,200_000_000);assertEquals(5,m.archives(0,10).stream().mapToInt(LedgerMaintenance.Archive::rows).sum());assertEquals(0,m.sweep(2000,200_000_000).auditsArchived());assertEquals(1,s.pending().size());}
    }
    @Test void failedDeletionRollsBackArchiveAndPreservesCurrentFacilityProof()throws Exception {
        try(WorldStore s=open()){UUID city=UUID.randomUUID();s.ensureCity(new CoreCity(city,UUID.randomUUID(),"城","国",1,"test"));s.execute("UPDATE city_state SET last_period=2000 WHERE id=?",city);s.execute("INSERT INTO audit(at,actor,action) VALUES(1,'x','a')");s.execute("INSERT INTO settlement(city,period,report) VALUES(?,1,'old')",city);s.execute("INSERT INTO evolution_facility(city,building,period) VALUES(?,'old',1)",city);s.execute("INSERT INTO evolution_facility(city,building,period) VALUES(?,'current',2000)",city);
            LedgerMaintenance m=new LedgerMaintenance(s,new LedgerMaintenance.Settings(1,1000,10));s.execute("CREATE TRIGGER reject_archive_delete BEFORE DELETE ON audit BEGIN SELECT RAISE(ABORT,'disk fault'); END");assertThrows(Exception.class,()->m.sweep(4000,200_000_000));assertTrue(m.archives(0,10).isEmpty());assertEquals(1,s.query("SELECT COUNT(*) FROM audit",r->r.getInt(1)).getFirst());s.execute("DROP TRIGGER reject_archive_delete");var result=m.sweep(4000,200_000_000);assertEquals(1,result.settlementReportsPruned());assertEquals(1,result.facilityPeriodsPruned());assertEquals(1,s.query("SELECT COUNT(*) FROM evolution_facility",r->r.getInt(1)).getFirst());s.execute("UPDATE audit_archive SET payload=x'00'");assertThrows(Exception.class,()->m.readArchive(1));}
    }
    @Test void invalidRetentionAndPagesFailBeforeMutation()throws Exception{assertThrows(IllegalArgumentException.class,()->new LedgerMaintenance.Settings(1,2,100));try(WorldStore s=open()){LedgerMaintenance m=new LedgerMaintenance(s,new LedgerMaintenance.Settings(30,1000,100));assertThrows(IllegalArgumentException.class,()->m.sweep(-1,1));assertThrows(IllegalArgumentException.class,()->m.archives(0,101));}}
}

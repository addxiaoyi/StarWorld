package dev.starworld.life;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.life.LifeDefinitions.Feast;
import dev.starworld.life.LifeState.ContributionReceipt;
import dev.starworld.life.LifeState.EvolutionCredit;
import dev.starworld.persistence.WorldStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual committed LifeService actions, not SQL-created positive culture evidence. */
class LifeEvolutionCarryTest {
    @TempDir Path temp;
    final LifeDefinitions defaults=LifeDefinitions.defaults();
    final UUID actor=UUID.randomUUID();
    final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"city","nation",1,"fixture");
    LifeDefinitions limits(int cap){return new LifeDefinitions(defaults.professionCooldownPeriods(),cap,cap,defaults.cultureLimit(),defaults.maximumProductionBonus(),defaults.professions(),defaults.feasts(),defaults.decorations(),defaults.concerts());}
    LifeService open(WorldStore store,LifeDefinitions definitions)throws Exception {LifeService life=new LifeService(store,definitions);life.initialize();return life;}
    void seed(WorldStore store,LifeService life)throws Exception {store.ensureCity(city);for(String item:List.of("minecraft:bread","kaleidoscopecookery:baozi","kaleidoscopecookery:oil"))store.stock(city.cityId(),item,10000);life.chooseProfession(actor,"chef",1);}
    ContributionReceipt feast(LifeService life,String recipe,long period)throws Exception{return life.feast(UUID.randomUUID(),city,actor,recipe,period,life.definitions().itemIds());}
    long count(WorldStore store,String table)throws Exception{return store.query("SELECT COUNT(*) FROM "+table,r->r.getLong(1)).getFirst();}
    long credited(WorldStore store)throws Exception{return store.query("SELECT COALESCE(SUM(credited_culture),0) FROM life_evolution_action",r->r.getLong(1)).getFirst();}
    List<String> actions(WorldStore store)throws Exception{return store.query("SELECT id||'|'||city||'|'||nation||'|'||actor||'|'||kind||'|'||source||'|'||period||'|'||culture||'|'||bonus||'|'||until_period||'|'||fingerprint||'|'||eligible FROM life_action ORDER BY id",r->r.getString(1));}
    void legacy(WorldStore store)throws Exception {
        store.tx(s->{s.execute("DROP TABLE life_evolution_pending");s.execute("DROP TABLE life_evolution_budget");s.execute("ALTER TABLE life_evolution_action RENAME TO saved_v2_progress");
            s.execute("CREATE TABLE life_evolution_action(id TEXT PRIMARY KEY REFERENCES life_action(id),consumed_period INTEGER NOT NULL)");s.execute("INSERT INTO life_evolution_action(id,consumed_period) SELECT id,consumed_period FROM saved_v2_progress");s.execute("DROP TABLE saved_v2_progress");s.execute("UPDATE life_schema SET version=1 WHERE id=1");return null;});
    }
    @Test void exactActionDiagnosticReadsRemainImmutableAcrossPartialFullAndRestartStates()throws Exception {
        Path db=temp.resolve("credit-read.db");ContributionReceipt large,small;List<String> original;
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService old=open(store,defaults);assertTrue(old.evolutionCredit(UUID.randomUUID()).isEmpty());seed(store,old);
            large=feast(old,"cookery",10);small=feast(old,"community",11);LifeService life=open(store,limits(20));original=actions(store);
            EvolutionCredit queued=new EvolutionCredit(large.id(),city.cityId(),city.nationId(),25,0,-1,true);
            assertEquals(queued,life.evolutionCredit(large.id()).orElseThrow());assertEquals(queued,life.evolutionCredit(large.id()).orElseThrow());
            assertEquals(0,count(store,"life_evolution_action"));assertEquals(0,count(store,"life_evolution_budget"));assertEquals(original,actions(store));
            assertEquals(20,life.drainEvolutionCulture(city.cityId(),11));
            assertEquals(new EvolutionCredit(large.id(),city.cityId(),city.nationId(),25,20,11,true),life.evolutionCredit(large.id()).orElseThrow());
            assertEquals(new EvolutionCredit(small.id(),city.cityId(),city.nationId(),12,0,-1,true),life.evolutionCredit(small.id()).orElseThrow());
        }
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService life=open(store,limits(20));
            assertEquals(new EvolutionCredit(large.id(),city.cityId(),city.nationId(),25,20,11,true),life.evolutionCredit(large.id()).orElseThrow());
            assertEquals(17,life.drainEvolutionCulture(city.cityId(),12));
            EvolutionCredit complete=new EvolutionCredit(large.id(),city.cityId(),city.nationId(),25,25,12,false);
            assertEquals(complete,life.evolutionCredit(large.id()).orElseThrow());assertEquals(complete,life.evolutionCredit(large.id()).orElseThrow());
            assertEquals(new EvolutionCredit(small.id(),city.cityId(),city.nationId(),12,12,12,false),life.evolutionCredit(small.id()).orElseThrow());
            assertEquals(original,actions(store));assertEquals(37,credited(store));assertEquals(0,life.drainEvolutionCulture(city.cityId(),12));
        }
    }
    @Test void diagnosticReadsExposeMigratedCompletionAndRetiredPartialCreditWithoutReactivation()throws Exception {
        Path db=temp.resolve("credit-read-legacy.db");ContributionReceipt completed,pending;
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService life=open(store,defaults);seed(store,life);completed=feast(life,"community",10);assertEquals(12,life.drainEvolutionCulture(city.cityId(),10));pending=feast(life,"cookery",11);legacy(store);
        }
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService life=open(store,limits(20));
            assertEquals(new EvolutionCredit(completed.id(),city.cityId(),city.nationId(),12,12,10,false),life.evolutionCredit(completed.id()).orElseThrow());
            assertEquals(new EvolutionCredit(pending.id(),city.cityId(),city.nationId(),25,0,-1,true),life.evolutionCredit(pending.id()).orElseThrow());
            assertEquals(20,life.drainEvolutionCulture(city.cityId(),11));
            CoreCity conquered=new CoreCity(city.cityId(),UUID.randomUUID(),"city","new",1,"fixture");life.settleDecorations(conquered,12,Set.of());
            EvolutionCredit retired=new EvolutionCredit(pending.id(),city.cityId(),city.nationId(),25,20,11,false);
            assertEquals(retired,life.evolutionCredit(pending.id()).orElseThrow());life.settleDecorations(city,13,Set.of());
            assertEquals(retired,life.evolutionCredit(pending.id()).orElseThrow());assertEquals(0,life.drainEvolutionCulture(city.cityId(),13));assertEquals(32,credited(store));
        }
    }
    @Test void lowerConfiguredCapCarriesOneOriginalActionAcrossRestartWithoutBlockingNewerCulture()throws Exception {
        Path db=temp.resolve("carry.db");ContributionReceipt large,small;List<String> original;
        try(WorldStore store=new WorldStore(db,100000)){LifeService life=open(store,defaults);seed(store,life);large=feast(life,"cookery",10);assertEquals(25,large.culture());}
        try(WorldStore store=new WorldStore(db,100000)){LifeService life=open(store,limits(20));small=feast(life,"community",11);original=actions(store);assertEquals(20,life.drainEvolutionCulture(city.cityId(),11));assertEquals(0,life.drainEvolutionCulture(city.cityId(),11));assertEquals(20,credited(store));assertEquals(2,count(store,"life_evolution_pending"));assertEquals(37,life.profession(actor).cultureContributed());}
        try(WorldStore store=new WorldStore(db,100000)){LifeService life=open(store,limits(20));assertEquals(0,life.drainEvolutionCulture(city.cityId(),11));assertEquals(17,life.drainEvolutionCulture(city.cityId(),12));assertEquals(0,life.drainEvolutionCulture(city.cityId(),12));assertEquals(0,life.drainEvolutionCulture(city.cityId(),13));assertEquals(37,credited(store));assertEquals(0,count(store,"life_evolution_pending"));assertEquals(original,actions(store));assertEquals(25,store.query("SELECT credited_culture FROM life_evolution_action WHERE id=?",r->r.getInt(1),large.id()).getFirst());assertEquals(12,store.query("SELECT credited_culture FROM life_evolution_action WHERE id=?",r->r.getInt(1),small.id()).getFirst());assertEquals(37,life.view(city.cityId(),13).culture());assertEquals(1,count(store,"life_evolution_budget"));}
    }
    @Test void budgetIsSharedByRepeatedCallsNewActionsAndRestartRatherThanResetPerInvocation()throws Exception {
        Path db=temp.resolve("shared.db");
        try(WorldStore store=new WorldStore(db,100000)){LifeService life=open(store,defaults);seed(store,life);feast(life,"community",10);feast(life,"community",11);}
        try(WorldStore store=new WorldStore(db,100000)){LifeService life=open(store,limits(20));assertEquals(20,life.drainEvolutionCulture(city.cityId(),12));feast(life,"community",12);assertEquals(0,life.drainEvolutionCulture(city.cityId(),12));}
        try(WorldStore store=new WorldStore(db,100000)){LifeService life=open(store,limits(20));assertEquals(0,life.drainEvolutionCulture(city.cityId(),12));assertEquals(16,life.drainEvolutionCulture(city.cityId(),13));assertEquals(36,credited(store));assertThrows(SQLException.class,()->life.drainEvolutionCulture(city.cityId(),12));assertEquals(0,life.drainEvolutionCulture(city.cityId(),1000));assertEquals(1,count(store,"life_evolution_budget"));}
    }
    @Test void concurrentSamePeriodDrainsCannotExceedTheOneSharedBudget()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("concurrent.db"),100000)){LifeService old=open(store,defaults);seed(store,old);feast(old,"cookery",10);feast(old,"community",11);LifeService life=open(store,limits(20));ExecutorService executor=Executors.newFixedThreadPool(4);
            try{List<Future<Long>> results=new ArrayList<>();for(int i=0;i<12;i++)results.add(executor.submit(()->life.drainEvolutionCulture(city.cityId(),11)));long total=0;for(var result:results)total+=result.get(5,TimeUnit.SECONDS);assertEquals(20,total);assertEquals(20,credited(store));assertEquals(17,life.drainEvolutionCulture(city.cityId(),12));}finally{executor.shutdownNow();}}
    }
    @Test void outerRollbackRestoresPartialProgressPendingQueueAndPeriodBudget()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("outer.db"),100000)){LifeService old=open(store,defaults);seed(store,old);feast(old,"cookery",10);feast(old,"community",11);LifeService life=open(store,limits(20));
            assertThrows(SQLException.class,()->store.tx(s->{assertEquals(20,life.drainEvolutionCulture(city.cityId(),11));throw new SQLException("later settlement failed");}));assertEquals(0,credited(store));assertEquals(0,count(store,"life_evolution_budget"));assertEquals(2,count(store,"life_evolution_pending"));assertEquals(20,life.drainEvolutionCulture(city.cityId(),11));assertEquals(0,life.drainEvolutionCulture(city.cityId(),11));assertEquals(17,life.drainEvolutionCulture(city.cityId(),12));}
    }
    @Test void handledLateFailureUsesItsOwnSavepointAndKeepsUnrelatedOuterAuditWrites()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("handled.db"),100000)){LifeService old=open(store,defaults);seed(store,old);feast(old,"community",10);feast(old,"cookery",11);LifeService life=open(store,limits(20));store.execute("CREATE TRIGGER fail_culture_budget BEFORE INSERT ON life_evolution_budget BEGIN SELECT RAISE(ABORT,'injected late budget failure'); END");
            store.tx(s->{s.audit("test","KEEP_BEFORE");assertThrows(SQLException.class,()->life.drainEvolutionCulture(city.cityId(),11));s.audit("test","KEEP_AFTER");return null;});assertEquals(2,store.query("SELECT COUNT(*) FROM audit WHERE action IN('KEEP_BEFORE','KEEP_AFTER')",r->r.getInt(1)).getFirst());assertEquals(0,credited(store));assertEquals(0,count(store,"life_evolution_budget"));assertEquals(2,count(store,"life_evolution_pending"));store.execute("DROP TRIGGER fail_culture_budget");assertEquals(20,life.drainEvolutionCulture(city.cityId(),11));assertEquals(17,life.drainEvolutionCulture(city.cityId(),12));}
    }
    @Test void conquestRetiresUncreditedOldNationQueueWithoutDestroyingOriginalReceipts()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("nation.db"),100000)){LifeService old=open(store,defaults);seed(store,old);ContributionReceipt receipt=feast(old,"cookery",10);LifeService life=open(store,limits(20));assertEquals(20,life.drainEvolutionCulture(city.cityId(),11));CoreCity conquered=new CoreCity(city.cityId(),UUID.randomUUID(),"city","new",1,"fixture");life.settleDecorations(conquered,12,Set.of());assertEquals(0,count(store,"life_evolution_pending"));assertEquals(0,life.drainEvolutionCulture(city.cityId(),12));life.settleDecorations(city,13,Set.of());assertEquals(0,life.drainEvolutionCulture(city.cityId(),13));feast(life,"community",14);assertEquals(12,life.drainEvolutionCulture(city.cityId(),14));assertEquals(32,credited(store));assertEquals(25,store.query("SELECT culture FROM life_action WHERE id=?",r->r.getInt(1),receipt.id()).getFirst());assertEquals(2,count(store,"life_action"));}
    }
    @Test void legacyFullyConsumedRowsMigrateExactlyAndOneTimeBackfillDoesNotRestoreConsumedQueue()throws Exception {
        Path db=temp.resolve("legacy.db");List<String> original,oldProgress;UUID pending;
        try(WorldStore store=new WorldStore(db,100000)){LifeService life=open(store,defaults);seed(store,life);feast(life,"community",10);assertEquals(12,life.drainEvolutionCulture(city.cityId(),10));feast(life,"community",11);assertEquals(12,life.drainEvolutionCulture(city.cityId(),11));pending=feast(life,"cookery",12).id();original=actions(store);oldProgress=store.query("SELECT id||'|'||consumed_period FROM life_evolution_action ORDER BY id",r->r.getString(1));legacy(store);}
        try(WorldStore store=new WorldStore(db,100000)){LifeService life=open(store,limits(20));assertEquals(2,store.query("SELECT version FROM life_schema WHERE id=1",r->r.getInt(1)).getFirst());assertEquals(original,actions(store));assertEquals(oldProgress,store.query("SELECT id||'|'||consumed_period FROM life_evolution_action ORDER BY id",r->r.getString(1)));assertEquals(24,credited(store));assertEquals(List.of(pending.toString()),store.query("SELECT id FROM life_evolution_pending",r->r.getString(1)));assertEquals(List.of("11|12"),store.query("SELECT period||'|'||culture FROM life_evolution_budget",r->r.getString(1)));assertEquals(0,life.drainEvolutionCulture(city.cityId(),11));assertEquals(20,life.drainEvolutionCulture(city.cityId(),12));life.initialize();assertEquals(1,count(store,"life_evolution_pending"));assertEquals(44,credited(store));assertEquals(0,life.drainEvolutionCulture(city.cityId(),12));assertEquals(5,life.drainEvolutionCulture(city.cityId(),13));assertEquals(49,credited(store));assertTrue(store.query("PRAGMA foreign_key_check",r->r.getString(1)).isEmpty());}
    }
    @Test void failedLegacyBackfillRollsBackDdlAndVersionEvenWhenOuterCallerHandlesFailure()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("migration-fail.db"),100000)){LifeService life=open(store,defaults);seed(store,life);feast(life,"community",10);assertEquals(12,life.drainEvolutionCulture(city.cityId(),10));feast(life,"cookery",11);legacy(store);List<String> before=actions(store);store.execute("CREATE TABLE life_evolution_budget(city TEXT NOT NULL REFERENCES life_city(city),nation TEXT NOT NULL,period INTEGER NOT NULL,culture INTEGER NOT NULL,PRIMARY KEY(city,nation))");store.execute("CREATE TRIGGER fail_migration_budget BEFORE INSERT ON life_evolution_budget BEGIN SELECT RAISE(ABORT,'injected migration failure'); END");
            store.tx(s->{assertThrows(SQLException.class,life::initialize);s.audit("test","KEEP_MIGRATION_OUTER");return null;});assertEquals(1,store.query("SELECT version FROM life_schema WHERE id=1",r->r.getInt(1)).getFirst());assertEquals(List.of("id","consumed_period"),store.query("PRAGMA table_info(life_evolution_action)",r->r.getString("name")));assertTrue(store.query("SELECT name FROM sqlite_master WHERE name='life_evolution_pending'",r->r.getString(1)).isEmpty());assertEquals(before,actions(store));assertEquals(0,count(store,"life_evolution_budget"));store.execute("DROP TRIGGER fail_migration_budget");life.initialize();assertEquals(12,credited(store));assertEquals(1,count(store,"life_evolution_pending"));}
    }
    @Test void corruptPartialCreditCannotBeClampedReplayedOrPartlyCommitted()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("corrupt.db"),100000)){LifeService old=open(store,defaults);seed(store,old);UUID id=feast(old,"cookery",10).id();feast(old,"community",11);LifeService life=open(store,limits(20));assertEquals(20,life.drainEvolutionCulture(city.cityId(),11));List<String> immutable=actions(store);store.execute("UPDATE life_evolution_action SET credited_culture=26 WHERE id=?",id);assertThrows(SQLException.class,()->life.drainEvolutionCulture(city.cityId(),12));assertEquals(2,count(store,"life_evolution_pending"));assertEquals(List.of("11|20"),store.query("SELECT period||'|'||culture FROM life_evolution_budget",r->r.getString(1)));store.execute("UPDATE life_evolution_action SET credited_culture=20 WHERE id=?",id);store.execute("UPDATE life_action SET culture=24.5 WHERE id=?",id);assertThrows(SQLException.class,()->life.drainEvolutionCulture(city.cityId(),12));store.execute("UPDATE life_action SET culture=25 WHERE id=?",id);assertEquals(immutable,actions(store));assertEquals(17,life.drainEvolutionCulture(city.cityId(),12));assertEquals(37,credited(store));}
    }
    @Test void malformedQueueAndCompletionPeriodsFailClosedWithoutChangingStoredCredit()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("corrupt-period.db"),100000)) {
            LifeService old=open(store,defaults);seed(store,old);UUID id=feast(old,"cookery",10).id();LifeService life=open(store,limits(20));assertEquals(20,life.drainEvolutionCulture(city.cityId(),11));
            store.execute("UPDATE life_evolution_action SET consumed_period=11.5 WHERE id=?",id);
            assertThrows(SQLException.class,()->life.evolutionCredit(id));assertThrows(SQLException.class,()->life.drainEvolutionCulture(city.cityId(),12));
            store.execute("UPDATE life_evolution_action SET consumed_period=13 WHERE id=?",id);assertThrows(SQLException.class,()->life.drainEvolutionCulture(city.cityId(),12));
            store.execute("UPDATE life_evolution_action SET consumed_period=11 WHERE id=?",id);store.execute("UPDATE life_evolution_pending SET period=9 WHERE id=?",id);
            assertThrows(SQLException.class,()->life.drainEvolutionCulture(city.cityId(),12));assertEquals(20,credited(store));assertEquals(1,count(store,"life_evolution_pending"));
            store.execute("UPDATE life_evolution_pending SET period=10 WHERE id=?",id);assertEquals(5,life.drainEvolutionCulture(city.cityId(),12));
            assertEquals(new EvolutionCredit(id,city.cityId(),city.nationId(),25,25,12,false),life.evolutionCredit(id).orElseThrow());
        }
    }
    @Test void queueVisitsAtMostFiveHundredRowsAndDoesNotRescanPermanentConsumedHistory()throws Exception {
        Feast tiny=new Feast("tiny","tiny","chef",Map.of("minecraft:bread",1L),1,0,1);LifeDefinitions roomy=new LifeDefinitions(defaults.professionCooldownPeriods(),100000,100000,defaults.cultureLimit(),defaults.maximumProductionBonus(),defaults.professions(),Map.of("tiny",tiny),defaults.decorations(),defaults.concerts());
        try(WorldStore store=new WorldStore(temp.resolve("bounded.db"),100000)){LifeService life=open(store,roomy);seed(store,life);for(int i=0;i<501;i++)feast(life,"tiny",10);assertEquals(500,life.drainEvolutionCulture(city.cityId(),10));assertEquals(1,count(store,"life_evolution_pending"));assertEquals(1,life.drainEvolutionCulture(city.cityId(),10));assertEquals(0,count(store,"life_evolution_pending"));assertEquals(501,count(store,"life_action"));assertEquals(501,count(store,"life_evolution_action"));assertEquals(0,life.drainEvolutionCulture(city.cityId(),1000));assertEquals(1,count(store,"life_evolution_budget"));String plan=String.join(" ",store.query("EXPLAIN QUERY PLAN SELECT a.id,a.culture FROM life_evolution_pending p INDEXED BY life_evolution_pending_city JOIN life_action a ON a.id=p.id LEFT JOIN life_evolution_action e ON e.id=a.id WHERE p.city=? AND p.nation=? AND p.period<=? ORDER BY p.period,p.id LIMIT 500",r->r.getString("detail"),city.cityId(),city.nationId(),1000));assertTrue(plan.contains("SEARCH p USING COVERING INDEX life_evolution_pending_city"),plan);assertFalse(plan.contains("SCAN a"),plan);}
    }
}

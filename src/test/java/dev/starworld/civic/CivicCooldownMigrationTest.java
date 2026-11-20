package dev.starworld.civic;

import dev.starworld.civic.CivicNeedsDefinitions.Need;
import dev.starworld.civic.CivicNeedsState.*;
import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.evolution.*;
import dev.starworld.life.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CivicCooldownMigrationTest {
    @TempDir Path temp;
    final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"城市","国家",1,"test");
    final UUID chef=UUID.randomUUID();
    static CivicNeedsDefinitions cooldown(int value) {
        CivicNeedsDefinitions original=CivicNeedsDefinitions.defaults();Map<String,Need> needs=new HashMap<>(original.needs());Need meal=needs.get("community_meals");
        needs.put(meal.id(),new Need(meal.id(),meal.name(),meal.lifetimePeriods(),value,meal.costs(),meal.actionKind(),meal.actionCount(),meal.distinctSources(),meal.stabilityReward(),meal.prosperityReward()));
        return new CivicNeedsDefinitions(1,original.maximumPage(),original.maximumOffset(),original.historyLimit(),needs);
    }
    final class Fixture implements AutoCloseable {
        final WorldStore store;final LifeService life;final CivicNeedsService civic;
        Fixture(String name,int cooldown)throws Exception {this(name,cooldown,false);}
        Fixture(String name,int cooldown,boolean legacy)throws Exception {
            store=new WorldStore(temp.resolve(name+".db"),100000);store.ensureCity(city);
            life=new LifeService(store,CivicNeedsServiceTest.lifeDefinitions());life.initialize();
            new EvolutionService(store,CivicNeedsServiceTest.base(),new EvolutionDefinitions(Map.of("balanced",new EvolutionDefinitions.Plan("均衡",Map.of())),Map.of(),6,100,1,2,Map.of()));
            civic=new CivicNeedsService(store,cooldown(cooldown));if(legacy)legacySchema(store);else civic.initialize();
            life.chooseProfession(chef,"chef",1);
        }
        void seed()throws SQLException {store.stock(city.cityId(),"minecraft:wheat",200);store.stock(city.cityId(),"minecraft:bread",200);}
        Request request(long period)throws SQLException {return civic.request(UUID.randomUUID(),city,"community_meals",period,"mayor");}
        Receipt complete(Request request,long period)throws SQLException {life.feast(UUID.randomUUID(),city,chef,"community",period,life.definitions().itemIds());return civic.complete(request.id(),city,period,"mayor");}
        public void close()throws Exception {store.close();}
    }
    /** Exact pre-fix civic schema. Business records below are created through the real life/civic services. */
    static void legacySchema(WorldStore s)throws SQLException {
        s.execute("CREATE TABLE civic_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");s.execute("INSERT INTO civic_schema VALUES(1,1)");
        s.execute("CREATE TABLE civic_clock(city TEXT PRIMARY KEY REFERENCES city_state(id),period INTEGER NOT NULL CHECK(period>=0))");
        s.execute("CREATE TABLE civic_request(id TEXT PRIMARY KEY,city TEXT NOT NULL REFERENCES city_state(id),nation TEXT NOT NULL,type TEXT NOT NULL,name TEXT NOT NULL,actor TEXT NOT NULL,requested INTEGER NOT NULL,expires INTEGER NOT NULL,cooldown INTEGER NOT NULL,bucket INTEGER NOT NULL,lifetime INTEGER NOT NULL,action_kind TEXT NOT NULL,action_count INTEGER NOT NULL,distinct_sources INTEGER NOT NULL,stability_reward REAL NOT NULL,prosperity_reward REAL NOT NULL,state TEXT NOT NULL CHECK(state IN ('OPEN','COMPLETED','EXPIRED','INVALIDATED')),completed INTEGER NOT NULL DEFAULT -1,actual_stability REAL NOT NULL DEFAULT 0,actual_prosperity REAL NOT NULL DEFAULT 0,UNIQUE(city,nation,type,bucket))");
        s.execute("CREATE UNIQUE INDEX civic_one_open_city ON civic_request(city) WHERE state='OPEN'");
        s.execute("CREATE INDEX civic_request_city_requested ON civic_request(city,requested,id)");
        s.execute("CREATE TABLE civic_request_cost(request TEXT NOT NULL REFERENCES civic_request(id),item TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>0),PRIMARY KEY(request,item))");
        s.execute("CREATE TABLE civic_action_claim(action TEXT PRIMARY KEY,request TEXT NOT NULL REFERENCES civic_request(id),source TEXT NOT NULL,claimed_period INTEGER NOT NULL)");
        s.execute("CREATE INDEX civic_action_claim_request ON civic_action_claim(request,action)");
        s.execute("CREATE TABLE civic_history(id INTEGER PRIMARY KEY AUTOINCREMENT,city TEXT NOT NULL REFERENCES city_state(id),request TEXT NOT NULL REFERENCES civic_request(id),period INTEGER NOT NULL,kind TEXT NOT NULL,detail TEXT NOT NULL,UNIQUE(request,kind))");
        s.execute("CREATE INDEX civic_history_city_period ON civic_history(city,period,id)");s.execute("CREATE INDEX civic_history_city_id ON civic_history(city,id)");
        s.execute("CREATE INDEX civic_life_action_candidates ON life_action(city,nation,kind,period,id)");
    }
    static List<List<Object>> rows(WorldStore store,String table)throws SQLException {
        return store.query("SELECT * FROM "+table+" ORDER BY 1,2",r->{List<Object> values=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)values.add(r.getObject(i));return values;});
    }
    static Map<String,List<List<Object>>> durable(WorldStore store)throws SQLException {
        Map<String,List<List<Object>>> result=new TreeMap<>();for(String name:List.of("civic_request","civic_request_cost","civic_action_claim","civic_history","civic_clock","stock","life_action","evolution_city"))result.put(name,rows(store,name));return result;
    }
    static void valid(WorldStore s)throws SQLException {
        assertEquals(2,s.query("SELECT version FROM civic_schema WHERE id=1",r->r.getInt(1)).getFirst());
        assertEquals(1,s.query("PRAGMA foreign_keys",r->r.getInt(1)).getFirst());assertTrue(s.query("PRAGMA foreign_key_check",r->r.getString(1)).isEmpty());
        assertEquals("ok",s.query("PRAGMA quick_check",r->r.getString(1)).getFirst());
        assertTrue(s.query("SELECT name FROM sqlite_master WHERE name LIKE 'civic_%_v2'",r->r.getString(1)).isEmpty());
    }
    @Test void shorterCooldownCannotReopenAnOldAbsoluteWindowAfterRestart()throws Exception {
        UUID old;Need original;
        try(Fixture f=new Fixture("shorter",48)){f.seed();Request request=f.request(37);old=request.id();original=request.definition();f.complete(request,38);}
        try(Fixture f=new Fixture("shorter",12)){
            assertEquals(original,f.civic.request(old).orElseThrow().definition());assertEquals(0,f.civic.request(old).orElseThrow().cooldownBucket());
            assertThrows(SQLException.class,()->f.request(39));assertEquals(1,f.civic.requests(city.cityId(),8,0).size());
            Request next=f.request(48);assertEquals(12,next.definition().cooldownPeriods());assertEquals(4,next.cooldownBucket());valid(f.store);
        }
    }
    @Test void longerCooldownAllowsDisjointAbsoluteWindowsDespiteIdenticalBucketNumber()throws Exception {
        Request previous;
        try(Fixture f=new Fixture("disjoint",24)){f.seed();previous=f.request(24);assertEquals(1,previous.cooldownBucket());f.complete(previous,25);}
        try(Fixture f=new Fixture("disjoint",48)){
            Request next=f.request(48);assertEquals(previous.cooldownBucket(),next.cooldownBucket());
            assertEquals(24,f.civic.request(previous.id()).orElseThrow().definition().cooldownPeriods());
            f.complete(next,49);valid(f.store);
        }
        try(Fixture f=new Fixture("disjoint",12)){
            assertThrows(SQLException.class,()->f.request(60));assertNotNull(f.request(96));valid(f.store);
        }
    }
    @Test void longerWindowCannotEncloseAnEarlierRequestEvenAfterItsShortWindowEnds()throws Exception {
        try(Fixture f=new Fixture("longer",12)){f.seed();f.complete(f.request(60),61);}
        try(Fixture f=new Fixture("longer",48)){
            Map<String,List<List<Object>>> before=durable(f.store);
            assertThrows(SQLException.class,()->f.request(72));assertEquals(before,durable(f.store));
            assertNotNull(f.request(96));valid(f.store);
        }
    }
    @Test void schemaOneMigrationPreservesCompletedOpenRequestsClaimsHistoryAndSequenceAcrossRestart()throws Exception {
        Request completed,open;Receipt receipt;Map<String,List<List<Object>>> original;long sequence;
        try(Fixture f=new Fixture("legacy",24,true)){
            f.seed();completed=f.request(24);receipt=f.complete(completed,25);
            open=f.civic.request(UUID.randomUUID(),city,"festival",26,"mayor");
            // Simulate display-history pruning without resetting its already allocated IDs.
            f.store.execute("DELETE FROM civic_history WHERE id=(SELECT MAX(id) FROM civic_history)");
            sequence=f.store.query("SELECT seq FROM sqlite_sequence WHERE name='civic_history'",r->r.getLong(1)).getFirst();original=durable(f.store);
            assertTrue(sequence>f.store.query("SELECT MAX(id) FROM civic_history",r->r.getLong(1)).getFirst());
        }
        try(Fixture f=new Fixture("legacy",48)){
            assertEquals(original,durable(f.store));assertEquals(completed.definition(),f.civic.request(completed.id()).orElseThrow().definition());
            assertEquals(open,f.civic.request(open.id()).orElseThrow());Receipt saved=f.civic.receipt(completed.id()).orElseThrow();
            assertFalse(saved.applied());assertEquals(receipt.claimedActionUUIDs(),saved.claimedActionUUIDs());assertEquals(receipt.paidItems(),saved.paidItems());
            assertEquals(sequence,f.store.query("SELECT seq FROM sqlite_sequence WHERE name='civic_history'",r->r.getLong(1)).getFirst());
            f.civic.initialize();assertEquals(original,durable(f.store));valid(f.store);
            assertNotNull(f.request(48));assertEquals(Status.EXPIRED,f.civic.request(open.id()).orElseThrow().status());
            assertTrue(f.store.query("SELECT MAX(id) FROM civic_history",r->r.getLong(1)).getFirst()>sequence);
        }
        try(Fixture f=new Fixture("legacy",12)){
            assertFalse(f.civic.receipt(completed.id()).orElseThrow().applied());
            assertEquals(1,f.store.query("SELECT COUNT(*) FROM civic_action_claim",r->r.getInt(1)).getFirst());valid(f.store);
        }
    }
    @Test void failedMigrationCopyRollsBackAllRebuiltTablesAndRetainsOriginalVersion()throws Exception {
        try(Fixture f=new Fixture("migration-rollback",24,true)){
            f.seed();Request request=f.request(24);f.complete(request,25);
            // Negative corrupted-storage fixture: original v1 CHECK temporarily bypassed, then immediately restored.
            f.store.execute("PRAGMA ignore_check_constraints=ON");f.store.execute("UPDATE civic_request_cost SET amount=0 WHERE request=? AND item='minecraft:wheat'",request.id());f.store.execute("PRAGMA ignore_check_constraints=OFF");
            Map<String,List<List<Object>>> before=durable(f.store);assertThrows(SQLException.class,f.civic::initialize);
            assertEquals(before,durable(f.store));assertEquals(1,f.store.query("SELECT version FROM civic_schema WHERE id=1",r->r.getInt(1)).getFirst());
            assertEquals(1,f.store.query("PRAGMA foreign_keys",r->r.getInt(1)).getFirst());
            assertTrue(f.store.query("SELECT name FROM sqlite_master WHERE name LIKE 'civic_%_v2'",r->r.getString(1)).isEmpty());
            f.store.execute("UPDATE civic_request_cost SET amount=16 WHERE request=? AND item='minecraft:wheat'",request.id());
            f.civic.initialize();assertEquals(1,f.store.query("SELECT COUNT(*) FROM civic_action_claim",r->r.getInt(1)).getFirst());valid(f.store);
        }
    }
    @Test void cooldownOverlapQueryUsesBoundedIndexedRequestedPeriodRange()throws Exception {
        try(Fixture f=new Fixture("index",24)){
            assertEquals(List.of("city","nation","type","requested"),f.store.query("PRAGMA index_info(civic_request_cooldown)",r->r.getString("name")));
            String plan=String.join(" ",f.store.query("EXPLAIN QUERY PLAN SELECT 1 FROM civic_request WHERE city=? AND nation=? AND type=? AND requested>? AND requested<? AND bucket*cooldown<? AND (bucket+1)*cooldown>? LIMIT 1",r->r.getString("detail"),city.cityId(),city.nationId(),"community_meals",-216,48,48,24));
            assertTrue(plan.contains("SEARCH"));assertTrue(plan.contains("civic_request_cooldown"));assertTrue(plan.contains("requested>?"));assertTrue(plan.contains("requested<?"));valid(f.store);
        }
    }
}

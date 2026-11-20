package dev.starworld.civic;

import dev.starworld.civic.CivicNeedsDefinitions.Need;
import dev.starworld.civic.CivicNeedsState.*;
import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.*;
import dev.starworld.evolution.*;
import dev.starworld.life.*;
import dev.starworld.life.LifeState.*;
import dev.starworld.life.CivicEvidenceFixture;
import dev.starworld.persistence.WorldStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CivicNeedsServiceTest {
    @TempDir Path temp;
    final UUID chef=UUID.randomUUID(),artisan=UUID.randomUUID(),musician=UUID.randomUUID(),world=UUID.randomUUID();
    final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"城市","国家",1,"fixture");
    final CivicNeedsDefinitions defaults=CivicNeedsDefinitions.defaults();
    static Definitions base() {
        return new Definitions(10,8,20,100000,0,Map.of("plains",new Definitions.Terrain("plains","平原",1,1,1,Map.of(),Map.of())),
                Map.of("agrarian",new Definitions.Civilization("agrarian","农业",Map.of())),Map.of("farm",new Definitions.BuildingType("farm","农场","BARREL",Map.of())),List.of(),Map.of());
    }
    static LifeDefinitions lifeDefinitions() {
        LifeDefinitions d=LifeDefinitions.defaults();
        return new LifeDefinitions(d.professionCooldownPeriods(),d.playerCulturePerPeriod(),d.cityCulturePerPeriod(),d.cultureLimit(),d.maximumProductionBonus(),d.professions(),d.feasts(),d.decorations(),
                new LifeDefinitions.ConcertRules("musician",Set.of("minecraft:note_block"),Map.of("minecraft:bread",8L),5000,60000,200,60000,3,16,2,1,20,.06,4,4));
    }
    final class Fixture implements AutoCloseable {
        final WorldStore store; final LifeService life; final EvolutionService evolution; final CivicNeedsService civic;
        Fixture(String name) throws Exception {this(name,defaults);}
        Fixture(String name,CivicNeedsDefinitions config) throws Exception {
            store=new WorldStore(temp.resolve(name+".db"),100000);store.ensureCity(city);
            life=new LifeService(store,lifeDefinitions());life.initialize();
            evolution=new EvolutionService(store,base(),new EvolutionDefinitions(Map.of("balanced",new EvolutionDefinitions.Plan("均衡",Map.of())),Map.of(),6,100,1,2,Map.of()));
            civic=new CivicNeedsService(store,config);civic.initialize();
            life.chooseProfession(chef,"chef",1);life.chooseProfession(artisan,"artisan",1);life.chooseProfession(musician,"musician",1);
        }
        void seed() throws Exception {for(String item:List.of("minecraft:wheat","minecraft:bread","minecraft:oak_planks","minecraft:paper"))store.stock(city.cityId(),item,200);}
        Request need(String type,long period) throws Exception {return civic.request(UUID.randomUUID(),city,type,period,"mayor");}
        ContributionReceipt feast(long period) throws Exception {return life.feast(UUID.randomUUID(),city,chef,"community",period,life.definitions().itemIds());}
        UUID furniture(int x,long period) throws Exception {UUID id=UUID.randomUUID();life.registerDecoration(city,artisan,CivicEvidenceFixture.furniture(city,artisan,world,id,x,life.definitions()),period);return id;}
        UUID startConcert(long period) throws Exception {UUID id=UUID.randomUUID();life.startConcert(id,city,musician,new Site(world,"world",0,64,0),period,System.currentTimeMillis()-6000,life.definitions().itemIds());return id;}
        ContributionReceipt finishConcert(UUID id,long period) throws Exception {
            for(int i=0;i<3;i++){if(i>0)Thread.sleep(220);life.note(CivicEvidenceFixture.note(city,musician,world,life.concert(id),8+i,life.definitions()));}
            return life.finishConcert(id,city,musician,period,System.currentTimeMillis());
        }
        int count(String table) throws SQLException {return store.query("SELECT COUNT(*) FROM "+table,r->r.getInt(1)).getFirst();}
        public void close() throws Exception {store.close();}
    }
    @Test void realFeastWarehouseDebitAndSmallLocalRewardCommitExactlyOnceAcrossRestart() throws Exception {
        UUID request,action;
        try(Fixture f=new Fixture("restart")) {
            f.seed();Request need=f.need("community_meals",10);request=need.id();action=f.feast(11).id();
            Receipt done=f.civic.complete(request,city,11,"mayor");assertTrue(done.applied());assertEquals(List.of(action),done.claimedActionUUIDs());
            assertEquals(188,f.store.stock(city.cityId()).get("minecraft:bread"));assertEquals(184,f.store.stock(city.cityId()).get("minecraft:wheat"));
            assertEquals(52,f.evolution.profile(city.cityId()).stability());assertEquals(51,f.evolution.profile(city.cityId()).prosperity());
            assertFalse(f.civic.complete(request,city,12,"other-authorized-mayor").applied());assertEquals(1,f.count("civic_action_claim"));
            assertThrows(UnsupportedOperationException.class,()->done.claimedActionUUIDs().add(UUID.randomUUID()));
        }
        try(Fixture f=new Fixture("restart")) {Receipt replay=f.civic.complete(request,city,13,"mayor");assertFalse(replay.applied());assertEquals(List.of(action),replay.claimedActionUUIDs());assertEquals(188,f.store.stock(city.cityId()).get("minecraft:bread"));assertEquals(2,f.civic.history(city.cityId(),10,0).size());}
    }
    @Test void receiptQueryOnlyReadsCompletedProofAndCannotAdvanceOrRewardTheCity() throws Exception {
        UUID request;Receipt expected;
        try(Fixture f=new Fixture("query-receipt")) {
            f.seed();request=f.need("community_meals",10).id();assertTrue(f.civic.receipt(UUID.randomUUID()).isEmpty());assertTrue(f.civic.receipt(request).isEmpty());
            f.feast(11);Receipt applied=f.civic.complete(request,city,11,"mayor");Map<String,Long> stock=f.store.stock(city.cityId());var profile=f.evolution.profile(city.cityId());var histories=f.civic.history(city.cityId(),10,0);
            expected=f.civic.receipt(request).orElseThrow();assertFalse(expected.applied());assertEquals(applied.claimedActionUUIDs(),expected.claimedActionUUIDs());assertEquals(applied.paidItems(),expected.paidItems());
            f.store.invalidateCity(city.cityId());assertEquals(expected,f.civic.receipt(request).orElseThrow());assertEquals(stock,f.store.stock(city.cityId()));assertEquals(profile,f.evolution.profile(city.cityId()));assertEquals(histories,f.civic.history(city.cityId(),10,0));
            assertEquals(11,f.store.query("SELECT period FROM civic_clock WHERE city=?",r->r.getLong(1),city.cityId()).getFirst());
            assertThrows(UnsupportedOperationException.class,()->expected.claimedActionUUIDs().clear());assertThrows(UnsupportedOperationException.class,()->expected.paidItems().clear());
        }
        try(Fixture f=new Fixture("query-receipt")){assertEquals(expected,f.civic.receipt(request).orElseThrow());assertEquals(1,f.count("civic_action_claim"));}
    }
    @Test void requestPayloadIsStableAndConfigurationIsSnapshotted() throws Exception {
        UUID id=UUID.randomUUID();Need snapshot;
        try(Fixture f=new Fixture("snapshot")) {
            f.seed();snapshot=f.civic.request(id,city,"community_meals",10,"mayor").definition();assertEquals(snapshot,f.civic.request(id,city,"community_meals",10,"mayor").definition());
            assertThrows(SQLException.class,()->f.civic.request(id,city,"festival",10,"mayor"));assertThrows(SQLException.class,()->f.civic.request(id,city,"community_meals",10,"other"));
            f.feast(11);
        }
        Map<String,Need> changed=new HashMap<>(defaults.needs());changed.put("community_meals",new Need("community_meals","changed",24,48,Map.of("minecraft:bread",99L),"feast",4,false,1,3));
        try(Fixture f=new Fixture("snapshot",new CivicNeedsDefinitions(1,32,10000,changed))) {
            assertEquals(snapshot,f.civic.request(id,city,"community_meals",10,"mayor").definition());Receipt done=f.civic.complete(id,city,11,"mayor");assertEquals(snapshot.costs(),done.paidItems());assertEquals(2,done.actualStability());
        }
    }
    @Test void preRequestAndSamePeriodActivityCannotFulfilANewNeed() throws Exception {
        try(Fixture f=new Fixture("period")) {
            f.seed();f.feast(9);Request need=f.need("community_meals",10);f.feast(10);
            assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,10,"mayor"));assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,11,"mayor"));
            assertEquals(0,f.count("civic_action_claim"));UUID valid=f.feast(11).id();assertEquals(List.of(valid),f.civic.complete(need.id(),city,11,"mayor").claimedActionUUIDs());
        }
    }
    @Test void newRequestsCannotBeBackdatedBehindCommittedLifeActions() throws Exception {
        try(Fixture f=new Fixture("backdated")) {
            f.seed();f.feast(11);assertThrows(SQLException.class,()->f.need("community_meals",10));
            Request request=f.need("community_meals",11);assertThrows(SQLException.class,()->f.civic.complete(request.id(),city,12,"mayor"));
            assertEquals(0,f.count("civic_action_claim"));UUID action=f.feast(12).id();assertEquals(List.of(action),f.civic.complete(request.id(),city,12,"mayor").claimedActionUUIDs());
        }
    }
    @Test void futureIneligibleZeroCultureWrongKindAndForeignEvidenceAreRejected() throws Exception {
        try(Fixture f=new Fixture("bad-proof")) {
            f.seed();Request need=f.need("community_meals",10);UUID action=f.feast(11).id();
            for(String update:List.of("period=30","eligible=0","culture=0","kind='decoration'","nation='"+UUID.randomUUID()+"'")) {
                f.store.execute("UPDATE life_action SET "+update+" WHERE id=?",action);assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,11,"mayor"));
                f.store.execute("UPDATE life_action SET period=11,eligible=1,culture=12,kind='feast',nation=? WHERE id=?",city.nationId(),action);
            }
            CoreCity other=new CoreCity(UUID.randomUUID(),city.nationId(),"另一城市","国家",1,"fixture");f.store.ensureCity(other);f.store.stock(other.cityId(),"minecraft:bread",20);f.life.feast(UUID.randomUUID(),other,chef,"community",11,f.life.definitions().itemIds());
            f.store.execute("UPDATE life_action SET eligible=0 WHERE id=?",action);assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,11,"mayor"));f.store.execute("UPDATE life_action SET eligible=1 WHERE id=?",action);
            assertEquals(0,f.count("civic_action_claim"));assertTrue(f.civic.complete(need.id(),city,11,"mayor").applied());
        }
    }
    @Test void realVanillaConcertMustFinishBeforeItsCommittedActionCanCompleteFestival() throws Exception {
        try(Fixture f=new Fixture("concert")) {
            f.seed();Request need=f.need("festival",10);UUID concert=f.startConcert(11);
            assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,11,"mayor"));assertEquals(0,f.count("life_action"));
            ContributionReceipt played=f.finishConcert(concert,11);assertTrue(played.applied());Receipt reward=f.civic.complete(need.id(),city,11,"mayor");
            assertEquals(List.of(concert),reward.claimedActionUUIDs());assertEquals(184,f.store.stock(city.cityId()).get("minecraft:bread"));assertEquals(192,f.store.stock(city.cityId()).get("minecraft:paper"));
        }
    }
    @Test void cancelledOrUnfinishedConcertLedgerInjectionIsNotAnEligibleCompletedPerformance() throws Exception {
        try(Fixture f=new Fixture("bad-concert")) {
            f.seed();Request need=f.need("festival",10);UUID concert=f.startConcert(11);
            f.store.execute("INSERT INTO life_action(id,city,nation,actor,kind,source,period,culture,bonus,until_period,fingerprint,eligible) VALUES(?,?,?,?,'concert','performance',11,20,0,11,'negative-injected-unfinished',1)",concert,city.cityId(),city.nationId(),musician);
            assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,11,"mayor"));f.life.cancelConcert(concert,musician);assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,11,"mayor"));assertEquals(0,f.count("civic_action_claim"));
        }
    }
    @Test void twoDifferentRealFurnitureMaintenanceSourcesAreRequiredInsteadOfRegistrationOrRepeatedUpkeep() throws Exception {
        try(Fixture f=new Fixture("furniture")) {
            f.seed();Request need=f.need("public_spaces",10);UUID first=f.furniture(0,10);
            assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,11,"mayor"));f.life.settleDecorations(city,11,Set.of(first));f.life.settleDecorations(city,12,Set.of(first));
            assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,12,"mayor"));UUID second=f.furniture(2,12);f.life.settleDecorations(city,13,Set.of(first,second));
            Receipt done=f.civic.complete(need.id(),city,13,"mayor");assertEquals(2,done.claimedActionUUIDs().size());assertEquals(2,new HashSet<>(f.store.query("SELECT source FROM civic_action_claim WHERE request=?",r->r.getString(1),need.id())).size());
            assertEquals(176,f.store.stock(city.cityId()).get("minecraft:oak_planks"));assertEquals(196,f.store.stock(city.cityId()).get("minecraft:bread"));
        }
    }
    @Test void forgedReceiptObjectsAndLifeReplayCannotSupplyAnotherNeed() throws Exception {
        try(Fixture f=new Fixture("reuse")) {
            f.seed();Request first=f.need("community_meals",10);ContributionReceipt real=f.feast(11);f.civic.complete(first.id(),city,11,"mayor");
            Request second=f.need("community_meals",24);ContributionReceipt fake=new ContributionReceipt(real.id(),city.cityId(),chef,"feast","community",25,12,.04,28,true);assertEquals(25,fake.period());
            assertFalse(f.life.feast(real.id(),city,chef,"community",25,f.life.definitions().itemIds()).applied());assertThrows(SQLException.class,()->f.civic.complete(second.id(),city,25,"mayor"));assertEquals(1,f.count("civic_action_claim"));
        }
    }
    @Test void uniqueSpentActionTombstoneSurvivesLifeHistoryRemovalAndUuidRecreation() throws Exception {
        try(Fixture f=new Fixture("tombstone")) {
            f.seed();Request first=f.need("community_meals",10);UUID action=f.feast(11).id();f.civic.complete(first.id(),city,11,"mayor");
            f.store.execute("DELETE FROM life_evolution_pending WHERE id=?",action);f.store.execute("DELETE FROM life_action WHERE id=?",action);Request second=f.need("community_meals",24);
            assertTrue(f.life.feast(action,city,chef,"community",25,f.life.definitions().itemIds()).applied());assertThrows(SQLException.class,()->f.civic.complete(second.id(),city,25,"mayor"));assertEquals(1,f.count("civic_action_claim"));
        }
    }
    @Test void insufficientStockDoesNotClaimEvidenceOrChangeProfileAndCanBeRetried() throws Exception {
        try(Fixture f=new Fixture("stock")) {
            f.seed();Request need=f.need("community_meals",10);UUID action=f.feast(11).id();f.store.stock(city.cityId(),"minecraft:wheat",15);Map<String,Long> before=f.store.stock(city.cityId());
            assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,11,"mayor"));assertEquals(before,f.store.stock(city.cityId()));assertEquals(0,f.count("civic_action_claim"));assertEquals(50,f.evolution.profile(city.cityId()).stability());
            f.store.stock(city.cityId(),"minecraft:wheat",16);assertEquals(List.of(action),f.civic.complete(need.id(),city,11,"mayor").claimedActionUUIDs());
        }
    }
    @Test void lateHistoryFailureRollsBackDebitClaimRewardCompletionAndClock() throws Exception {
        try(Fixture f=new Fixture("rollback")) {
            f.seed();Request need=f.need("community_meals",10);f.feast(11);Map<String,Long> before=f.store.stock(city.cityId());
            f.store.execute("CREATE TRIGGER civic_fail_history BEFORE INSERT ON civic_history WHEN NEW.kind='COMPLETED' BEGIN SELECT RAISE(ABORT,'negative-injected-completion-failure'); END");
            assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,11,"mayor"));assertEquals(before,f.store.stock(city.cityId()));assertEquals(0,f.count("civic_action_claim"));assertEquals(Status.OPEN,f.civic.request(need.id()).orElseThrow().status());assertEquals(50,f.evolution.profile(city.cityId()).stability());assertEquals(10,f.store.query("SELECT period FROM civic_clock WHERE city=?",r->r.getLong(1),city.cityId()).getFirst());
            f.store.execute("DROP TRIGGER civic_fail_history");assertTrue(f.civic.complete(need.id(),city,11,"mayor").applied());
        }
    }
    @Test void enclosingWorldTransactionRollbackAlsoRestoresAllCivicChanges() throws Exception {
        try(Fixture f=new Fixture("outer")) {
            f.seed();Request need=f.need("community_meals",10);f.feast(11);Map<String,Long> before=f.store.stock(city.cityId());
            assertThrows(SQLException.class,()->f.store.tx(s->{assertTrue(f.civic.complete(need.id(),city,11,"mayor").applied());throw new IllegalStateException("outer rollback");}));
            assertEquals(before,f.store.stock(city.cityId()));assertEquals(0,f.count("civic_action_claim"));assertTrue(f.civic.complete(need.id(),city,11,"mayor").applied());
        }
    }
    @Test void handledNestedCompletionFailureStillRollsBackCivicWritesBeforeOuterCommit() throws Exception {
        try(Fixture f=new Fixture("nested-failure")) {
            f.seed();Request need=f.need("community_meals",10);f.feast(11);Map<String,Long> before=f.store.stock(city.cityId());
            f.store.execute("CREATE TRIGGER civic_nested_failure BEFORE INSERT ON civic_history WHEN NEW.kind='COMPLETED' BEGIN SELECT RAISE(ABORT,'negative-injected-nested-failure'); END");
            f.store.tx(s->{assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,11,"mayor"));s.audit("test","UNRELATED_OUTER_COMMIT");return null;});
            assertEquals(before,f.store.stock(city.cityId()));assertEquals(0,f.count("civic_action_claim"));assertEquals(Status.OPEN,f.civic.request(need.id()).orElseThrow().status());assertEquals(50,f.evolution.profile(city.cityId()).stability());
            f.store.execute("DROP TRIGGER civic_nested_failure");assertTrue(f.civic.complete(need.id(),city,11,"mayor").applied());
        }
    }
    @Test void currentNationAndActiveAuthorityAreRequiredEvenForCompletedRetries() throws Exception {
        try(Fixture f=new Fixture("authority")) {
            f.seed();Request old=f.need("community_meals",10);f.feast(11);CoreCity conquered=new CoreCity(city.cityId(),UUID.randomUUID(),"城市","新国家",1,"fixture");f.store.ensureCity(conquered);
            assertThrows(SQLException.class,()->f.civic.complete(old.id(),city,11,"mayor"));assertThrows(SQLException.class,()->f.civic.complete(old.id(),conquered,11,"mayor"));
            Request fresh=f.civic.request(UUID.randomUUID(),conquered,"festival",11,"new-mayor");assertEquals(Status.INVALIDATED,f.civic.request(old.id()).orElseThrow().status());assertEquals(conquered.nationId(),fresh.nationId());
        }
        try(Fixture f=new Fixture("inactive")) {
            f.seed();Request need=f.need("community_meals",10);f.feast(11);f.civic.complete(need.id(),city,11,"mayor");f.store.invalidateCity(city.cityId());
            assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,12,"mayor"));assertThrows(SQLException.class,()->f.civic.request(need.id(),city,"community_meals",10,"mayor"));
        }
    }
    @Test void expiryAndCooldownDoNotCatchUpOfflineOrAllowUnlimitedRequests() throws Exception {
        try(Fixture f=new Fixture("expiry")) {
            f.seed();Request old=f.need("community_meals",10);f.feast(11);assertThrows(SQLException.class,()->f.civic.complete(old.id(),city,23,"mayor"));
            Request next=f.need("festival",100);assertEquals(Status.EXPIRED,f.civic.request(old.id()).orElseThrow().status());assertThrows(SQLException.class,()->f.civic.complete(next.id(),city,101,"mayor"));assertEquals(0,f.count("civic_action_claim"));
        }
        try(Fixture f=new Fixture("cooldown")) {
            f.seed();Request first=f.need("community_meals",10);f.feast(11);f.civic.complete(first.id(),city,11,"mayor");assertThrows(SQLException.class,()->f.need("community_meals",12));assertEquals(1,f.civic.requests(city.cityId(),10,0).size());assertNotNull(f.need("community_meals",24));
        }
    }
    @Test void actualRewardsAreCappedAtHundredAndClockCannotGoBackwards() throws Exception {
        try(Fixture f=new Fixture("cap")) {
            f.seed();Request need=f.need("community_meals",10);f.feast(11);f.evolution.profile(city.cityId());f.store.execute("UPDATE evolution_city SET stability=99.5,prosperity=100 WHERE city=?",city.cityId());
            Receipt done=f.civic.complete(need.id(),city,12,"mayor");assertEquals(.5,done.actualStability());assertEquals(0,done.actualProsperity());assertEquals(100,f.evolution.profile(city.cityId()).stability());
            assertThrows(SQLException.class,()->f.civic.complete(need.id(),city,11,"mayor"));assertThrows(SQLException.class,()->f.need("festival",11));
        }
    }
    @Test void concurrentCompletionHasOneDebitOneClaimAndOneAppliedReceipt() throws Exception {
        try(Fixture f=new Fixture("concurrent")) {
            f.seed();Request need=f.need("community_meals",10);f.feast(11);ExecutorService executor=Executors.newFixedThreadPool(4);
            try {List<Future<Receipt>> futures=new ArrayList<>();for(int i=0;i<12;i++)futures.add(executor.submit(()->f.civic.complete(need.id(),city,11,"mayor")));int applied=0;for(Future<Receipt> result:futures)if(result.get(5,TimeUnit.SECONDS).applied())applied++;assertEquals(1,applied);assertEquals(1,f.count("civic_action_claim"));assertEquals(188,f.store.stock(city.cityId()).get("minecraft:bread"));}finally{executor.shutdownNow();}
        }
    }
    @Test void onlyOneOpenNeedAndBoundedPagesAndCandidateIndexAreEnforced() throws Exception {
        try(Fixture f=new Fixture("bounded")) {
            Request need=f.need("community_meals",10);assertThrows(SQLException.class,()->f.need("festival",10));assertEquals(need.id(),f.civic.requests(city.cityId(),1,0).getFirst().id());assertTrue(f.civic.requests(city.cityId(),1,1).isEmpty());
            assertThrows(IllegalArgumentException.class,()->f.civic.requests(city.cityId(),0,0));assertThrows(IllegalArgumentException.class,()->f.civic.history(city.cityId(),33,0));assertThrows(IllegalArgumentException.class,()->f.civic.requests(city.cityId(),1,-1));assertThrows(IllegalArgumentException.class,()->f.civic.history(city.cityId(),1,10001));
            assertEquals(List.of("city","nation","kind","period","id"),f.store.query("PRAGMA index_info(civic_life_action_candidates)",r->r.getString("name")));
            String plan=String.join(" ",f.store.query("EXPLAIN QUERY PLAN SELECT id FROM life_action INDEXED BY civic_life_action_candidates WHERE city=? AND nation=? AND kind='feast' AND period>=11 AND period<=22 ORDER BY period,id LIMIT 4",r->r.getString("detail"),city.cityId(),city.nationId()));assertTrue(plan.contains("SEARCH"));assertTrue(plan.contains("civic_life_action_candidates"));
        }
    }
    @Test void unsupportedSchemasFailClosedWithoutResettingExistingRequests() throws Exception {
        try(Fixture f=new Fixture("schema")) {Request need=f.need("community_meals",10);f.store.execute("UPDATE civic_schema SET version=999 WHERE id=1");assertThrows(SQLException.class,f.civic::initialize);assertEquals(need.id(),f.civic.request(need.id()).orElseThrow().id());}
        try(WorldStore store=new WorldStore(temp.resolve("dependencies.db"),100000)) {assertThrows(SQLException.class,()->new CivicNeedsService(store,defaults).initialize());}
        try(Fixture f=new Fixture("future-life")) {Request need=f.need("community_meals",10);f.store.execute("UPDATE life_schema SET version=3 WHERE id=1");assertThrows(SQLException.class,f.civic::initialize);assertEquals(need.id(),f.civic.request(need.id()).orElseThrow().id());}
    }
    @Test void displayHistoryPruningCannotReopenRequestsOrForgetSpentActionUuids() throws Exception {
        CivicNeedsDefinitions limited=new CivicNeedsDefinitions(1,32,10000,10,defaults.needs());UUID first=null,last=null;
        try(Fixture f=new Fixture("retention",limited)) {
            f.seed();for(int i=0;i<8;i++) {long period=10+24L*i;Request request=f.need("community_meals",period);if(first==null)first=request.id();last=request.id();f.feast(period+1);f.civic.complete(request.id(),city,period+1,"mayor");}
            assertEquals(10,f.civic.history(city.cityId(),32,0).size());assertEquals(8,f.count("civic_request"));assertEquals(8,f.count("civic_action_claim"));
            assertEquals(Status.COMPLETED,f.civic.request(first).orElseThrow().status());assertFalse(f.civic.complete(first,city,180,"mayor").applied());assertNotNull(last);
        }
        try(Fixture f=new Fixture("retention",limited)) {assertEquals(10,f.civic.history(city.cityId(),32,0).size());assertEquals(8,f.count("civic_action_claim"));assertFalse(f.civic.complete(first,city,181,"mayor").applied());}
    }
}

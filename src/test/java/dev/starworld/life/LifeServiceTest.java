package dev.starworld.life;

import dev.starworld.domain.WorldState.*;
import dev.starworld.life.LifeState.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LifeServiceTest {
    @TempDir Path temp;
    final LifeDefinitions definitions=LifeDefinitions.defaults();
    final UUID actor=UUID.randomUUID(),world=UUID.randomUUID(),audience=UUID.randomUUID();
    final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"城市","国家",1,"fixture");
    Site site(int x){return new Site(world,"world",x,64,0);}
    LifeService open(WorldStore store)throws Exception{LifeService service=new LifeService(store,definitions);service.initialize();return service;}
    void stock(WorldStore store,String item,long amount)throws Exception{store.ensureCity(city);store.stock(city.cityId(),item,amount);}
    @Test void feastConsumesCommittedGoodsExactlyOnceAndSurvivesRestart()throws Exception {
        Path db=temp.resolve("feast.db");UUID request=UUID.randomUUID();
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService life=open(store);stock(store,"minecraft:bread",40);life.chooseProfession(actor,"chef",1);
            assertTrue(life.feast(request,city,actor,"community",10,definitions.itemIds()).applied());
            assertFalse(life.feast(request,city,actor,"community",10,definitions.itemIds()).applied());
            assertEquals(32,store.stock(city.cityId()).get("minecraft:bread"));assertEquals(12,life.view(city.cityId(),10).culture());
            assertEquals(12,life.cultureInPeriod(city.cityId(),10));assertEquals(1.04,life.productionMultiplier(city.cityId(),10),0.00001);
        }
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService life=open(store);assertFalse(life.feast(request,city,actor,"community",11,definitions.itemIds()).applied());
            assertEquals(32,store.stock(city.cityId()).get("minecraft:bread"));assertEquals(12,life.profession(actor).cultureContributed());
            assertEquals(0,life.cultureInPeriod(city.cityId(),11));assertEquals(1,life.productionMultiplier(city.cityId(),13));
        }
    }
    @Test void missingCustomContentInsufficiencyAndRequestCollisionsCannotConsumeAnything()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("rollback.db"),100000)) {
            LifeService life=open(store);stock(store,"minecraft:bread",7);life.chooseProfession(actor,"chef",1);
            assertThrows(SQLException.class,()->life.feast(UUID.randomUUID(),city,actor,"community",10,definitions.itemIds()));
            assertEquals(7,store.stock(city.cityId()).get("minecraft:bread"));
            stock(store,"kaleidoscopecookery:baozi",4);stock(store,"kaleidoscopecookery:oil",1);
            Map<String,Long> before=store.stock(city.cityId());
            assertThrows(SQLException.class,()->life.feast(UUID.randomUUID(),city,actor,"cookery",10,Set.of("minecraft:bread")));assertEquals(before,store.stock(city.cityId()));
            stock(store,"minecraft:bread",16);UUID request=UUID.randomUUID();life.feast(request,city,actor,"community",10,definitions.itemIds());
            assertThrows(SQLException.class,()->life.feast(request,city,actor,"cookery",10,definitions.itemIds()));
            assertEquals(8,store.stock(city.cityId()).get("minecraft:bread"));assertEquals(4,store.stock(city.cityId()).get("kaleidoscopecookery:baozi"));
        }
    }
    @Test void concurrentDeliveryOfSameRequestHasOneDebitAndOneReceipt()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("concurrent.db"),100000)) {
            LifeService life=open(store);stock(store,"minecraft:bread",80);life.chooseProfession(actor,"chef",1);UUID request=UUID.randomUUID();
            ExecutorService executor=Executors.newFixedThreadPool(4);
            try {
                List<Future<ContributionReceipt>> work=new ArrayList<>();for(int i=0;i<12;i++)work.add(executor.submit(()->life.feast(request,city,actor,"community",10,definitions.itemIds())));
                int applied=0;for(Future<ContributionReceipt> future:work)if(future.get(5,TimeUnit.SECONDS).applied())applied++;
                assertEquals(1,applied);assertEquals(72,store.stock(city.cityId()).get("minecraft:bread"));assertEquals(12,life.cultureInPeriod(city.cityId(),10));
            }finally{executor.shutdownNow();}
        }
    }
    @Test void persistentPlayerAndCityCapsAndSameRecipeBonusCannotBeStacked()throws Exception {
        Path db=temp.resolve("caps.db");
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService life=open(store);stock(store,"minecraft:bread",1000);life.chooseProfession(actor,"chef",1);
            for(int i=0;i<6;i++)life.feast(UUID.randomUUID(),city,actor,"community",10,definitions.itemIds());
            assertEquals(72,life.cultureInPeriod(city.cityId(),10));assertEquals(1.04,life.productionMultiplier(city.cityId(),10),0.00001);
        }
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService life=open(store);assertThrows(SQLException.class,()->life.feast(UUID.randomUUID(),city,actor,"community",10,definitions.itemIds()));
            for(int p=0;p<2;p++){UUID chef=UUID.randomUUID();life.chooseProfession(chef,"chef",1);for(int i=0;i<6;i++)life.feast(UUID.randomUUID(),city,chef,"community",10,definitions.itemIds());}
            UUID fourth=UUID.randomUUID();life.chooseProfession(fourth,"chef",1);life.feast(UUID.randomUUID(),city,fourth,"community",10,definitions.itemIds());life.feast(UUID.randomUUID(),city,fourth,"community",10,definitions.itemIds());
            assertEquals(240,life.cultureInPeriod(city.cityId(),10));long bread=store.stock(city.cityId()).get("minecraft:bread");
            assertThrows(SQLException.class,()->life.feast(UUID.randomUUID(),city,fourth,"community",10,definitions.itemIds()));assertEquals(bread,store.stock(city.cityId()).get("minecraft:bread"));
        }
    }
    @Test void professionsHavePersistentCooldownAndRequireMatchingWork()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("profession.db"),100000)) {
            LifeService life=open(store);stock(store,"minecraft:bread",80);life.chooseProfession(actor,"chef",1);
            assertThrows(SQLException.class,()->life.chooseProfession(actor,"musician",2));assertEquals("chef",life.profession(actor).profession());
            life.chooseProfession(actor,"musician",7);assertThrows(SQLException.class,()->life.feast(UUID.randomUUID(),city,actor,"community",10,definitions.itemIds()));
            assertEquals(80,store.stock(city.cityId()).get("minecraft:bread"));assertThrows(IllegalArgumentException.class,()->life.chooseProfession(actor,"hacker",10));
        }
    }
    DecorationEvidence evidence(UUID furniture,UUID meta,int x){return new DecorationEvidence(furniture,meta,city.cityId(),city.nationId(),actor,"kaleidoscope_deco:chair/oak",site(x));}
    @Test void furnitureRegistrationHasNoRewardAndRequiresRealUpkeepAndCurrentVerification()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("decoration.db"),100000)) {
            LifeService life=open(store);stock(store,"minecraft:oak_planks",40);stock(store,"minecraft:bread",5);life.chooseProfession(actor,"artisan",1);
            UUID furniture=UUID.randomUUID(),meta=UUID.randomUUID();DecorationEvidence evidence=evidence(furniture,meta,0);
            Decoration decoration=life.registerDecoration(city,actor,evidence,10);assertEquals(decoration,life.registerDecoration(city,actor,evidence,10));
            assertEquals(36,store.stock(city.cityId()).get("minecraft:oak_planks"));assertEquals(0,life.view(city.cityId(),10).culture());
            life.settleDecorations(city,10,Set.of());assertEquals(5,store.stock(city.cityId()).get("minecraft:bread"));
            life.settleDecorations(city,10,Set.of(furniture));assertEquals(0,life.cultureInPeriod(city.cityId(),10));
            life.settleDecorations(city,11,Set.of(furniture));life.settleDecorations(city,11,Set.of(furniture));
            assertEquals(4,store.stock(city.cityId()).get("minecraft:bread"));assertEquals(1,life.cultureInPeriod(city.cityId(),11));assertEquals(1.005,life.productionMultiplier(city.cityId(),11),0.000001);
            assertEquals(1,life.productionMultiplier(city.cityId(),12));life.removeDecoration(city.cityId(),furniture);assertEquals(1,life.productionMultiplier(city.cityId(),11));
            life.registerDecoration(city,actor,evidence,11);life.settleDecorations(city,11,Set.of(furniture));assertEquals(1,life.cultureInPeriod(city.cityId(),11));
        }
    }
    @Test void duplicateFurnitureMetadataAndSiteRollbackRegistrationCost()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("duplicate-furniture.db"),100000)) {
            LifeService life=open(store);stock(store,"minecraft:oak_planks",100);life.chooseProfession(actor,"artisan",1);
            UUID first=UUID.randomUUID(),meta=UUID.randomUUID();life.registerDecoration(city,actor,evidence(first,meta,0),10);
            assertThrows(SQLException.class,()->life.registerDecoration(city,actor,evidence(UUID.randomUUID(),meta,2),10));
            assertThrows(SQLException.class,()->life.registerDecoration(city,actor,evidence(UUID.randomUUID(),UUID.randomUUID(),0),10));
            assertEquals(96,store.stock(city.cityId()).get("minecraft:oak_planks"));assertEquals(1,life.decorations(city.cityId()).size());
        }
    }
    @Test void furnitureWithoutSuppliesOrChangedProfessionGivesNoBonus()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("maintenance.db"),100000)) {
            LifeService life=open(store);stock(store,"minecraft:oak_planks",100);life.chooseProfession(actor,"artisan",1);UUID id=UUID.randomUUID();life.registerDecoration(city,actor,evidence(id,UUID.randomUUID(),0),10);
            life.settleDecorations(city,10,Set.of(id));assertEquals(0,life.cultureInPeriod(city.cityId(),10));assertEquals(1,life.productionMultiplier(city.cityId(),10));
            stock(store,"minecraft:bread",5);life.chooseProfession(actor,"chef",11);life.settleDecorations(city,11,Set.of(id));assertEquals(5,store.stock(city.cityId()).get("minecraft:bread"));assertEquals(0,life.cultureInPeriod(city.cityId(),11));
        }
    }
    ConcertView start(LifeService life,WorldStore store,UUID id)throws Exception {stock(store,"minecraft:bread",100);life.chooseProfession(actor,"musician",1);return life.startConcert(id,city,actor,site(0),10,100000,definitions.itemIds());}
    NoteReceipt note(UUID id,int sequence,int pitch,long millis){return new NoteReceipt(id,actor,city.cityId(),city.nationId(),sequence,pitch,millis,Set.of(audience));}
    void completeNotes(LifeService life,UUID id)throws Exception{for(int i=1;i<=12;i++)life.note(note(id,i,i%5,100000+i*5000L));}
    @Test void paidConcertRecordsActualNotesAndFinishesOnceAcrossRestart()throws Exception {
        UUID id=UUID.randomUUID();Path db=temp.resolve("concert.db");
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService life=open(store);ConcertView c=start(life,store,id);assertEquals(c,life.startConcert(id,city,actor,site(0),10,101000,definitions.itemIds()));assertEquals(92,store.stock(city.cityId()).get("minecraft:bread"));
            for(int i=1;i<=6;i++)life.note(note(id,i,i%5,100000+i*5000L));
        }
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService life=open(store);assertEquals(6,life.concert(id).notes());for(int i=7;i<=12;i++)life.note(note(id,i,i%5,100000+i*5000L));
            ContributionReceipt receipt=life.finishConcert(id,city,actor,10,160000);assertTrue(receipt.applied());assertFalse(life.finishConcert(id,city,actor,10,161000).applied());
            assertEquals(20,life.cultureInPeriod(city.cityId(),10));assertEquals(1.06,life.productionMultiplier(city.cityId(),10),0.00001);assertEquals("completed",life.concert(id).state());
            assertThrows(SQLException.class,()->life.startConcert(UUID.randomUUID(),city,actor,site(0),11,200000,definitions.itemIds()));assertEquals(92,store.stock(city.cityId()).get("minecraft:bread"));
        }
    }
    @Test void sequenceSpacingAudienceAndDiversityCannotBeForgedOrReplayed()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("notes.db"),100000)) {
            LifeService life=open(store);UUID id=UUID.randomUUID();start(life,store,id);
            assertThrows(SQLException.class,()->life.note(note(id,2,1,105000)));assertThrows(SQLException.class,()->life.note(note(id,1,1,100100)));
            assertThrows(SQLException.class,()->life.note(new NoteReceipt(id,actor,city.cityId(),city.nationId(),1,1,105000,Set.of())));
            assertThrows(SQLException.class,()->life.note(new NoteReceipt(id,actor,city.cityId(),city.nationId(),1,1,105000,Set.of(actor))));
            NoteReceipt first=note(id,1,1,105000);life.note(first);assertEquals(1,life.note(first).notes());assertThrows(SQLException.class,()->life.note(note(id,1,2,105000)));
            for(int i=2;i<=12;i++)life.note(note(id,i,1,100000+i*5000L));
            assertThrows(SQLException.class,()->life.finishConcert(id,city,actor,10,160000));assertEquals(0,life.cultureInPeriod(city.cityId(),10));assertEquals(92,store.stock(city.cityId()).get("minecraft:bread"));
        }
    }
    @Test void earlyExpiredCancelledAndConcurrentConcertsGiveNoRefundOrReward()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("expiry.db"),100000)) {
            LifeService life=open(store);UUID id=UUID.randomUUID();start(life,store,id);
            assertThrows(SQLException.class,()->life.startConcert(UUID.randomUUID(),city,actor,site(0),10,100001,definitions.itemIds()));
            assertThrows(SQLException.class,()->life.finishConcert(id,city,actor,10,101000));assertThrows(SQLException.class,()->life.note(note(id,1,1,120000)));
            life.cancelConcert(id,actor);assertThrows(SQLException.class,()->life.note(note(id,1,1,105000)));assertThrows(SQLException.class,()->life.finishConcert(id,city,actor,10,160000));
            UUID next=UUID.randomUUID();life.startConcert(next,city,actor,site(0),10,200000,definitions.itemIds());assertThrows(SQLException.class,()->life.finishConcert(next,city,actor,10,600000));
            assertEquals(84,store.stock(city.cityId()).get("minecraft:bread"));assertEquals(0,life.cultureInPeriod(city.cityId(),10));
        }
    }
    @Test void nationChangeInvalidatesOldSessionsDecorationsAndTemporaryBonuses()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("ownership.db"),100000)) {
            LifeService life=open(store);stock(store,"minecraft:bread",100);life.chooseProfession(actor,"chef",1);life.feast(UUID.randomUUID(),city,actor,"community",10,definitions.itemIds());
            CoreCity conquered=new CoreCity(city.cityId(),UUID.randomUUID(),"城市","新国",1,"fixture");
            life.settleDecorations(conquered,11,Set.of());assertEquals(1,life.productionMultiplier(city.cityId(),11));assertEquals(0,life.cultureInPeriod(city.cityId(),11));
            life.settleDecorations(city,12,Set.of());assertEquals(1,life.productionMultiplier(city.cityId(),12),"recapture cannot revive an old temporary bonus");
            life.chooseProfession(actor,"musician",12);UUID id=UUID.randomUUID();life.startConcert(id,city,actor,site(0),12,100000,definitions.itemIds());completeNotes(life,id);
            life.settleDecorations(conquered,13,Set.of());assertEquals("ownership_changed",life.concert(id).state());
            assertThrows(SQLException.class,()->life.finishConcert(id,conquered,actor,13,160000));assertEquals(0,life.cultureInPeriod(city.cityId(),13));
        }
    }
    @Test void serviceRejectsFutureSchemaAndClockRollback()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("schema.db"),100000)) {
            LifeService life=open(store);stock(store,"minecraft:bread",100);life.chooseProfession(actor,"chef",1);life.feast(UUID.randomUUID(),city,actor,"community",10,definitions.itemIds());
            assertThrows(SQLException.class,()->life.feast(UUID.randomUUID(),city,actor,"community",9,definitions.itemIds()));assertThrows(IllegalArgumentException.class,()->life.view(city.cityId(),-1));
            store.execute("UPDATE life_schema SET version=999");assertThrows(SQLException.class,life::initialize);
        }
    }
    @Test void lateLifeCultureDrainsInNextSimulationOnceAndRollbackRestoresReceipt()throws Exception {
        Path db=temp.resolve("evolution-drain.db");
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService life=open(store);stock(store,"minecraft:bread",100);life.chooseProfession(actor,"chef",1);
            assertEquals(0,life.drainEvolutionCulture(city.cityId(),10));
            life.feast(UUID.randomUUID(),city,actor,"community",10,definitions.itemIds());
            assertThrows(SQLException.class,()->store.tx(s->{assertEquals(12,life.drainEvolutionCulture(city.cityId(),11));throw new IllegalStateException("Simulated settlement failure before commit");}));
        }
        try(WorldStore store=new WorldStore(db,100000)) {
            LifeService life=open(store);assertEquals(12,life.drainEvolutionCulture(city.cityId(),11));assertEquals(0,life.drainEvolutionCulture(city.cityId(),11));assertEquals(0,life.drainEvolutionCulture(city.cityId(),12));
            assertEquals(12,life.cultureInPeriod(city.cityId(),10));
        }
    }
    @Test void pendingLifeCultureIsBoundedAndFormerNationCannotContributeAfterConquest()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("culture-ownership.db"),100000)) {
            LifeService life=open(store);stock(store,"minecraft:bread",10000);life.chooseProfession(actor,"chef",1);
            for(long period=10;period<=14;period++)for(int i=0;i<6;i++)life.feast(UUID.randomUUID(),city,actor,"community",period,definitions.itemIds());
            assertEquals(240,life.drainEvolutionCulture(city.cityId(),14));assertEquals(120,life.drainEvolutionCulture(city.cityId(),15));
            life.feast(UUID.randomUUID(),city,actor,"community",15,definitions.itemIds());
            CoreCity conquered=new CoreCity(city.cityId(),UUID.randomUUID(),"城市","新国",1,"fixture");life.settleDecorations(conquered,16,Set.of());
            assertEquals(0,life.drainEvolutionCulture(city.cityId(),16));life.settleDecorations(city,17,Set.of());assertEquals(0,life.drainEvolutionCulture(city.cityId(),17));
        }
    }
    @Test void expiredSessionsFromOtherPerformersCannotPermanentlyExhaustCitySlots()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("expired-city-slots.db"),100000)) {
            LifeService life=open(store);stock(store,"minecraft:bread",10000);UUID first=null;
            for(int i=0;i<32;i++){UUID musician=UUID.randomUUID(),session=UUID.randomUUID();if(first==null)first=session;life.chooseProfession(musician,"musician",1);life.startConcert(session,city,musician,site(0),10,1000000,definitions.itemIds());}
            life.chooseProfession(actor,"musician",1);assertThrows(SQLException.class,()->life.startConcert(UUID.randomUUID(),city,actor,site(0),10,1000001,definitions.itemIds()));
            UUID replacement=UUID.randomUUID();life.startConcert(replacement,city,actor,site(0),11,1400001,definitions.itemIds());
            assertEquals("expired",life.concert(first).state());assertEquals(List.of(replacement),life.concerts(city.cityId()).stream().map(ConcertView::id).toList());assertEquals(10000-33*8,store.stock(city.cityId()).get("minecraft:bread"));
        }
    }
}

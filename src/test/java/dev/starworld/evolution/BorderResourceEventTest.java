package dev.starworld.evolution;

import dev.starworld.domain.*;
import dev.starworld.config.ConfigLoaderTest;
import dev.starworld.domain.WorldState.*;
import dev.starworld.evolution.EvolutionState.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BorderResourceEventTest {
    @TempDir Path temp;
    final UUID peer=UUID.randomUUID(),world=UUID.randomUUID();
    final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"边城","本国",1,"");
    final Building farm=new Building(UUID.randomUUID(),city.cityId(),city.nationId(),"farm",new Site(world,"frontier",0,64,0),1);
    final Definitions definitions=new Definitions(10,12,20,10000,1,
            Map.of("plains",new Definitions.Terrain("plains","平原",1,1,1,Map.of("ore",20L,"fertility",100L),Map.of())),
            Map.of("agrarian",new Definitions.Civilization("agrarian","农耕",Map.of())),
            Map.of("farm",new Definitions.BuildingType("farm","农场","COMPOSTER",Map.of())),
            List.of(new Definitions.Recipe("harvest","farm",Map.of(),Map.of("minecraft:wheat",12L),"fertility",1)),
            Map.of("border_dispute",new Definitions.WorldEvent("border_dispute","边境资源争端",1,1,Map.of("farm",0.5),0.8)));
    final EvolutionDefinitions options=new EvolutionDefinitions(Map.of("balanced",new EvolutionDefinitions.Plan("均衡",Map.of())),Map.of(),6,100,1,2,Map.of("border_dispute",new EvolutionDefinitions.Rule("border",1,10)));
    EvolutionService initialize(WorldStore store)throws Exception{store.ensureCity(city);if(store.buildings(city.cityId()).isEmpty())store.addBuilding(farm,"plains",definitions);return new EvolutionService(store,definitions,options);}
    BorderWitness witness(long period){return new BorderWitness(city.cityId(),city.nationId(),world,"frontier",0,0,1,0,peer,"WAR",period);}
    Context context(BorderWitness... witnesses){return new Context("clear","1",true,true,0,0,1,List.of(witnesses));}
    boolean candidate(WorldStore store,EvolutionService evolution,long period,Set<UUID> valid,Context context)throws Exception{CityView before=store.view(city.cityId()).orElseThrow();return evolution.cycle(city,before,period,valid,context).eventWeights(before,period).containsKey("border_dispute");}
    Settlement settle(WorldStore store,EvolutionService evolution,long period,Context context)throws Exception{return store.tx(s->new Simulation(s,definitions).settle(city,period,Set.of(farm.id()),evolution.cycle(city,s.view(city.cityId()).orElseThrow(),period,Set.of(farm.id()),context)));}
    @Test void warAndPressureAloneDoNotTriggerButVerifiedFrontierWithPositiveReserveDoes()throws Exception{
        try(WorldStore store=new WorldStore(temp.resolve("local.db"),10000)){EvolutionService e=initialize(store);assertFalse(candidate(store,e,10,Set.of(farm.id()),context()));assertTrue(candidate(store,e,10,Set.of(farm.id()),context(witness(10))));assertFalse(candidate(store,e,10,Set.of(),context(witness(10))));assertFalse(candidate(store,e,10,Set.of(farm.id()),context(witness(9))));}
    }
    @Test void exhaustedUnregisteredOrWrongNationResourcesNeverQualify()throws Exception{
        try(WorldStore store=new WorldStore(temp.resolve("scarcity.db"),10000)){EvolutionService e=initialize(store);BorderWitness remote=new BorderWitness(city.cityId(),city.nationId(),world,"frontier",4,0,5,0,peer,"WAR",10);assertFalse(candidate(store,e,10,Set.of(farm.id()),context(remote)));BorderWitness wrong=new BorderWitness(city.cityId(),UUID.randomUUID(),world,"frontier",0,0,1,0,peer,"WAR",10);assertFalse(candidate(store,e,10,Set.of(farm.id()),context(wrong)));store.reserve(farm.site().regionKey(),"ore",0);store.reserve(farm.site().regionKey(),"fertility",0);assertFalse(candidate(store,e,10,Set.of(farm.id()),context(witness(10))));}
    }
    @Test void naturalEventConsumesProbabilityRuleChangesRealOutputAndPersistsExactFrontierResourceHistory()throws Exception{
        Path database=temp.resolve("history.db");String detail;
        try(WorldStore store=new WorldStore(database,10000)){EvolutionService e=initialize(store);Settlement receipt=settle(store,e,10,context(witness(10)));assertTrue(receipt.applied());assertEquals(6,receipt.produced().get("minecraft:wheat"));assertEquals("border_dispute",store.view(city.cityId()).orElseThrow().event().type());assertEquals(44,e.profile(city.cityId()).stability());History history=e.history(city.cityId()).stream().filter(h->h.kind().equals("event")).findFirst().orElseThrow();detail=history.detail();for(String evidence:List.of("evidence=1","region="+farm.site().regionKey(),"peer="+peer,"neighbor=1,0","relation=WAR","resource=ore","remaining=20","captured=10"))assertTrue(detail.contains(evidence),detail);assertFalse(settle(store,e,10,context(witness(10))).applied());}
        try(WorldStore store=new WorldStore(database,10000)){EvolutionService e=initialize(store);assertEquals(detail,e.history(city.cityId()).stream().filter(h->h.kind().equals("event")).findFirst().orElseThrow().detail());assertFalse(candidate(store,e,12,Set.of(farm.id()),context(witness(12))),"Persistent event cooldown survives restart");assertTrue(candidate(store,e,20,Set.of(farm.id()),context(witness(20))));assertEquals(6,store.stock(city.cityId()).get("minecraft:wheat"));}
    }
    @Test void failedCommitRollsBackEventHistoryResourceAndPeriodBeforeExactRetry()throws Exception{
        try(WorldStore store=new WorldStore(temp.resolve("rollback.db"),10000)){EvolutionService e=initialize(store);store.execute("CREATE TRIGGER fail_border_commit BEFORE INSERT ON settlement BEGIN SELECT RAISE(ABORT,'border crash boundary'); END");assertThrows(SQLException.class,()->settle(store,e,10,context(witness(10))));assertNull(store.view(city.cityId()).orElseThrow().event());assertTrue(e.history(city.cityId()).isEmpty());assertTrue(store.stock(city.cityId()).isEmpty());assertEquals(100,store.region(farm.site().regionKey()).reserves().get("fertility"));assertEquals(0,store.query("SELECT COUNT(*) FROM evolution_event",r->r.getInt(1)).getFirst());store.execute("DROP TRIGGER fail_border_commit");assertTrue(settle(store,e,10,context(witness(10))).applied());assertEquals(1,e.history(city.cityId()).stream().filter(h->h.kind().equals("event")).count());}
    }
    @Test void repeatedFacilityOrDuplicateWitnessCannotMultiplyResourceDisputeEvidence()throws Exception{
        try(WorldStore store=new WorldStore(temp.resolve("duplicates.db"),10000)){EvolutionService e=initialize(store);var values=e.resourceDisputes(city,store.view(city.cityId()).orElseThrow(),10,Set.of(farm.id()),context(witness(10),witness(10)));assertEquals(1,values.size());assertEquals("ore",values.getFirst().resource());assertEquals(20,values.getFirst().remaining());assertThrows(UnsupportedOperationException.class,()->values.clear());}
    }
    @Test void configuredNamespacedAndPathOnlyReservesProduceNaturalEventAndSurviveRestart()throws Exception{
        int number=0;
        for(String resource:List.of("custom:ore","deep/ore")){
            Path folder=temp.resolve("custom-"+number++);Files.createDirectories(folder);Path config=folder.resolve("config.yml"),data=folder.resolve("definitions.yml"),database=folder.resolve("world.db");
            Files.writeString(config,"config-version: 2\nsimulation:\n  event-chance: 1\n  period-seconds: 10\n  stock-limit: 10000\n");
            Files.writeString(data,"""
                definition-version: 1
                terrains:
                  plains:
                    name: 自定义产地
                    agriculture: 1
                    transport: 1
                    military: 1
                    reserves: {'%s': 20}
                    regeneration: {}
                civilizations:
                  agrarian: {name: 农耕, multipliers: {}}
                buildings:
                  farm: {name: 农场, anchor: COMPOSTER, upkeep: {}}
                recipes:
                  harvest:
                    building: farm
                    inputs: {}
                    outputs: {minecraft:wheat: 12}
                    reserve: '%s'
                    reserve-cost: 1
                events:
                  border_dispute:
                    name: 边境资源争端
                    duration: 1
                    aftermath: 1
                    multipliers: {farm: 0.5}
                    aftermath-multiplier: 0.8
                """.formatted(resource,resource));
            Definitions custom=ConfigLoaderTest.loadWithPlatformFixture(config,data);assertEquals(Set.of(resource),custom.terrains().get("plains").reserves().keySet());String detail;
            try(WorldStore store=new WorldStore(database,custom.stockLimit())){
                store.ensureCity(city);store.addBuilding(farm,"plains",custom);EvolutionService e=new EvolutionService(store,custom,options);
                Settlement result=store.tx(s->new Simulation(s,custom).settle(city,10,Set.of(farm.id()),e.cycle(city,s.view(city.cityId()).orElseThrow(),10,Set.of(farm.id()),context(witness(10)))));
                assertTrue(result.applied());assertEquals(6,result.produced().get("minecraft:wheat"));assertEquals(19,store.region(farm.site().regionKey()).reserves().get(resource));detail=e.history(city.cityId()).stream().filter(h->h.kind().equals("event")).findFirst().orElseThrow().detail();assertTrue(detail.contains("resource="+resource+" remaining=20"),detail);
            }
            try(WorldStore store=new WorldStore(database,custom.stockLimit())){EvolutionService e=new EvolutionService(store,custom,options);assertEquals(19,store.region(farm.site().regionKey()).reserves().get(resource));assertEquals(detail,e.history(city.cityId()).stream().filter(h->h.kind().equals("event")).findFirst().orElseThrow().detail());assertFalse(new Simulation(store,custom).settle(city,10,Set.of(farm.id())).applied());assertEquals(6,store.stock(city.cityId()).get("minecraft:wheat"));}
        }
    }
    @Test void resourceEvidenceUsesTheExistingAmountKeyGrammarWithoutDefinitionIdRestrictions(){
        for(String resource:List.of("ore","9ore","_ore","deep/ore","custom:ore","custom:deep/ore","ore.v2","x".repeat(64)))assertEquals(resource,new ResourceDispute(witness(10),resource,20).resource());
        for(String resource:List.of("","Ore","custom:",":ore","a:b:c","ore with space"))assertThrows(IllegalArgumentException.class,()->new ResourceDispute(witness(10),resource,20));assertThrows(IllegalArgumentException.class,()->new ResourceDispute(witness(10),"ore",0));
    }
}

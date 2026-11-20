package dev.starworld.evolution;

import dev.starworld.domain.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NationalEvolutionTest {
    @TempDir Path temp;
    Definitions definitions;Path config;
    @BeforeEach void config()throws Exception {
        config=temp.resolve("evolution.yml");try(var input=getClass().getResourceAsStream("/evolution.yml")){Files.copy(Objects.requireNonNull(input),config);}
        Map<String,Definitions.BuildingType> buildings=new HashMap<>();
        for(String id:List.of("farm","sawmill","workshop","kitchen","warehouse","academy","barracks","market","well"))buildings.put(id,new Definitions.BuildingType(id,id,"STONE",Map.of()));
        definitions=new Definitions(300,12,20,100000,0,Map.of("plains",new Definitions.Terrain("plains","平原",1,1,1,Map.of("fertility",1000L),Map.of())),Map.of("agrarian",new Definitions.Civilization("agrarian","农耕",Map.of())),buildings,List.of(new Definitions.Recipe("harvest","farm",Map.of(),Map.of("minecraft:wheat",12L),"fertility",12)),Map.of());
    }
    CoreCity city(UUID nation){return new CoreCity(UUID.randomUUID(),nation,"城市","国家",1,"fixture");}
    EvolutionService evolution(WorldStore store)throws Exception{return new EvolutionService(store,definitions,new EvolutionDefinitions(Map.of("balanced",new EvolutionDefinitions.Plan("均衡",Map.of())),Map.of(),6,100,1,2,Map.of()));}
    NationalEvolution national(WorldStore store)throws Exception{return new NationalEvolution(store,definitions,config.toFile());}
    void cityScores(WorldStore store,EvolutionService e,CoreCity city,long agriculture,long industry,long commerce,long military,long knowledge,long culture)throws Exception {store.ensureCity(city);e.profile(city.cityId());store.execute("UPDATE evolution_city SET agriculture=?,industry=?,commerce=?,military=?,knowledge=?,culture=? WHERE city=?",agriculture,industry,commerce,military,knowledge,culture,city.cityId());}
    @Test void aggregatesCurrentNationCitiesOnlyAndReturnsImmutableDirectionScores()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("aggregate.db"),100000)){EvolutionService e=evolution(store);UUID nation=UUID.randomUUID();CoreCity a=city(nation),b=city(nation),foreign=city(UUID.randomUUID());
            cityScores(store,e,a,8,2,1,0,3,4);cityScores(store,e,b,15,4,2,1,5,6);cityScores(store,e,foreign,10000,10000,10000,10000,10000,10000);
            NationalEvolution n=national(store);var view=n.view(nation);assertEquals("agriculture",view.direction());assertEquals(23,view.tendencies().get("agriculture"));assertEquals(8,view.knowledge());assertEquals(10,view.culture());assertEquals("agrarian",view.civilization());assertEquals("",view.specialty());assertTrue(n.multipliers(nation).isEmpty());assertThrows(UnsupportedOperationException.class,()->view.tendencies().clear());
        }
    }
    @Test void realSettlementCreatesQualifyingAgricultureThenEducationUnlocksSpecialty()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("actual.db"),100000)){UUID nation=UUID.randomUUID();CoreCity c=city(nation);store.ensureCity(c);EvolutionService e=evolution(store);NationalEvolution n=national(store);Building f=new Building(UUID.randomUUID(),c.cityId(),nation,"farm",new Site(UUID.randomUUID(),"world",0,64,0),1),academy=new Building(UUID.randomUUID(),c.cityId(),nation,"academy",new Site(UUID.randomUUID(),"world",16,64,0),1);store.addBuilding(f,"plains",definitions);store.addBuilding(academy,"plains",definitions);
            assertThrows(SQLException.class,()->n.choose(nation,"agriculture",1,"founder"));Simulation simulation=new Simulation(store,definitions);Set<UUID> valid=Set.of(f.id(),academy.id());
            for(long period=1;period<=5;period++){CityView view=store.view(c.cityId()).orElseThrow();simulation.settle(c,period,valid,e.cycle(c,view,period,valid,EvolutionState.Context.empty()));}
            assertEquals(5,n.view(nation).knowledge());assertTrue(n.view(nation).tendencies().get("agriculture")>=20);assertEquals("agriculture",n.choose(nation,"agriculture",5,"founder").specialty());assertEquals(1.15,n.multipliers(nation).get("farm"));assertEquals(.9,n.multipliers(nation).get("workshop"));
        }
    }
    @Test void choiceAndCooldownPersistAcrossRestartAndSameChoiceDoesNotResetCooldown()throws Exception {
        Path db=temp.resolve("restart.db");UUID nation=UUID.randomUUID();CoreCity city=city(nation);
        try(WorldStore store=new WorldStore(db,100000)){EvolutionService e=evolution(store);cityScores(store,e,city,100,100,100,100,100,100);NationalEvolution n=national(store);n.choose(nation,"agriculture",10,"founder");assertEquals(10,n.choose(nation,"agriculture",11,"founder").changedPeriod());assertThrows(SQLException.class,()->n.choose(nation,"industry",15,"founder"));}
        try(WorldStore store=new WorldStore(db,100000)){evolution(store);NationalEvolution n=national(store);assertEquals("agriculture",n.view(nation).specialty());assertThrows(SQLException.class,()->n.choose(nation,"culture",9,"founder"));assertEquals("industry",n.choose(nation,"industry",16,"founder").specialty());assertEquals(16,n.view(nation).changedPeriod());assertEquals(1.15,n.multipliers(nation).get("workshop"));}
    }
    @Test void losingContributingCityImmediatelySuspendsNationalBonusWithoutDestroyingChoice()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("ownership.db"),100000)){EvolutionService e=evolution(store);UUID nation=UUID.randomUUID();CoreCity city=city(nation);cityScores(store,e,city,30,0,0,0,8,3);NationalEvolution n=national(store);n.choose(nation,"agriculture",10,"founder");assertFalse(n.multipliers(nation).isEmpty());
            store.ensureCity(new CoreCity(city.cityId(),UUID.randomUUID(),city.cityName(),"新国家",1,"core ownership snapshot"));assertTrue(n.multipliers(nation).isEmpty());assertEquals("agriculture",n.view(nation).specialty());assertEquals("balanced",n.view(nation).direction());
            store.ensureCity(city);assertFalse(n.multipliers(nation).isEmpty());assertEquals(10,n.view(nation).changedPeriod());
        }
    }
    @Test void allFiveSpecialtiesRequireRealDirectionAndKnowledgeConditions()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("five.db"),100000)){EvolutionService e=evolution(store);UUID nation=UUID.randomUUID();CoreCity city=city(nation);cityScores(store,e,city,100,100,100,100,0,100);NationalEvolution n=national(store);assertEquals(Set.of("agriculture","industry","commerce","military","culture"),n.specialties().keySet());
            for(String id:n.specialties().keySet())assertThrows(SQLException.class,()->n.choose(nation,id,10,"founder"));store.execute("UPDATE evolution_city SET knowledge=100 WHERE city=?",city.cityId());long period=10;
            for(String id:n.specialties().keySet().stream().sorted().toList()){assertEquals(id,n.choose(nation,id,period,"founder").specialty());assertFalse(n.multipliers(nation).isEmpty());period+=6;}
            assertThrows(UnsupportedOperationException.class,()->n.specialties().clear());
        }
    }
    @Test void absentCoreCityStopsContributingAcrossRestartAndValidSnapshotCanReactivateIt()throws Exception{
        Path db=temp.resolve("absent-city.db");UUID nation=UUID.randomUUID();CoreCity missing=city(nation),remaining=city(nation);
        try(WorldStore store=new WorldStore(db,100000)){EvolutionService e=evolution(store);cityScores(store,e,missing,30,30,20,20,10,20);cityScores(store,e,remaining,1,1,1,1,1,1);store.stock(missing.cityId(),"minecraft:bread",7);NationalEvolution n=national(store);n.choose(nation,"industry",10,"founder");assertFalse(n.multipliers(nation).isEmpty());
            store.invalidateCity(missing.cityId());var after=n.view(nation);assertEquals(1,after.knowledge());assertEquals(1,after.culture());for(long score:after.tendencies().values())assertEquals(1,score);assertTrue(n.multipliers(nation).isEmpty());assertEquals("industry",after.specialty());assertEquals(7,store.stock(missing.cityId()).get("minecraft:bread"));assertEquals(30,e.profile(missing.cityId()).tendencies().get("industry"),"Historical development remains intact");
        }
        try(WorldStore store=new WorldStore(db,100000)){evolution(store);NationalEvolution n=national(store);assertEquals(1,n.view(nation).knowledge());assertTrue(n.multipliers(nation).isEmpty());assertTrue(store.view(missing.cityId()).isPresent());
            store.ensureCity(missing);assertEquals(11,n.view(nation).knowledge());assertEquals(31,n.view(nation).tendencies().get("industry"));assertEquals(1.15,n.multipliers(nation).get("workshop"));assertEquals(10,n.view(nation).changedPeriod());assertEquals(7,store.stock(missing.cityId()).get("minecraft:bread"));
        }
    }
    @Test void legacyCitiesMigrateAsActiveWithoutRevivingPreviouslyInvalidatedCities()throws Exception{
        Path db=temp.resolve("authority-migration.db");UUID nation=UUID.randomUUID();CoreCity city=city(nation);
        try(WorldStore store=new WorldStore(db,100000)){cityScores(store,evolution(store),city,30,0,0,0,8,3);store.execute("DROP TABLE city_authority");}
        try(WorldStore store=new WorldStore(db,100000)){evolution(store);NationalEvolution n=national(store);assertEquals(30,n.view(nation).tendencies().get("agriculture"));assertEquals(8,n.view(nation).knowledge());store.invalidateCity(city.cityId());}
        try(WorldStore store=new WorldStore(db,100000)){evolution(store);NationalEvolution n=national(store);assertEquals(0,n.view(nation).knowledge());assertEquals("balanced",n.view(nation).direction());}
    }
    @Test void failedAuditWriteRollsBackNationalChoiceAndCanRetry()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("rollback.db"),100000)){EvolutionService e=evolution(store);UUID nation=UUID.randomUUID();cityScores(store,e,city(nation),100,0,0,0,10,0);NationalEvolution n=national(store);store.execute("CREATE TRIGGER reject_national_audit BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'disk write boundary'); END");assertThrows(SQLException.class,()->n.choose(nation,"agriculture",10,"founder"));assertEquals("",n.view(nation).specialty());assertTrue(n.multipliers(nation).isEmpty());store.execute("DROP TRIGGER reject_national_audit");assertEquals("agriculture",n.choose(nation,"agriculture",10,"founder").specialty());}
    }
    @Test void malformedDefinitionsAndFutureSchemaFailWithoutErasingSavedChoice()throws Exception {
        try(WorldStore store=new WorldStore(temp.resolve("validation.db"),100000)){evolution(store);String shipped=Files.readString(config);Files.writeString(config,shipped.replace("farm: 1.15, kitchen", "farm: 1.16, kitchen"));assertThrows(IllegalArgumentException.class,()->national(store));Files.writeString(config,shipped.replace("min-score: 20","min-score: 1.5"));assertThrows(IllegalArgumentException.class,()->national(store));Files.writeString(config,shipped.replace("farm: 1.15, kitchen", "ghost: 1.15, kitchen"));assertThrows(IllegalArgumentException.class,()->national(store));Files.writeString(config,shipped);NationalEvolution n=national(store);UUID nation=UUID.randomUUID();store.ensureCity(city(nation));assertEquals("",n.view(nation).specialty());store.execute("UPDATE national_evolution_schema SET version=99");assertThrows(SQLException.class,()->national(store));assertEquals(1,store.query("SELECT COUNT(*) FROM national_evolution",r->r.getInt(1)).getFirst());}
    }
    @Test void additiveDefaultsKeepExistingEvolutionFileUsableAndUnknownNationDoesNotInventCoreState()throws Exception {
        Files.writeString(config,"expansion-version: 1\n");try(WorldStore store=new WorldStore(temp.resolve("defaults.db"),100000)){evolution(store);NationalEvolution n=national(store);assertEquals(5,n.specialties().size());assertThrows(SQLException.class,()->n.view(UUID.randomUUID()));assertEquals(0,store.query("SELECT COUNT(*) FROM nation_state",r->r.getInt(1)).getFirst());}
    }
}


package dev.starworld.evolution;

import dev.starworld.config.ConfigLoader;
import dev.starworld.domain.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.persistence.WorldStore;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EvolutionServiceTest {
    @TempDir Path temp;Definitions definitions;EvolutionDefinitions options;
    @BeforeEach void config()throws Exception {
        for(String file:List.of("config.yml","definitions.yml","evolution.yml"))try(var in=getClass().getResourceAsStream("/"+file)){Files.copy(Objects.requireNonNull(in),temp.resolve(file));}
        Files.writeString(temp.resolve("config.yml"),"config-version: 2\nsimulation:\n  event-chance: 0\n  stock-limit: 1000\n");
        try(var mock=mockStatic(Material.class)){Material block=mock(Material.class);when(block.isBlock()).thenReturn(true);mock.when(()->Material.matchMaterial(anyString())).thenReturn(block);definitions=ConfigLoader.load(temp.resolve("config.yml").toFile(),temp.resolve("definitions.yml").toFile(),temp.resolve("evolution.yml").toFile());}
        options=EvolutionDefinitions.load(temp.resolve("evolution.yml").toFile(),definitions);
    }
    CoreCity city(){return new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"城市","国家",1.1,"tech");}
    Building add(WorldStore store,CoreCity c,String type,int chunk)throws Exception {store.ensureCity(c);Building b=new Building(UUID.randomUUID(),c.cityId(),c.nationId(),type,new Site(UUID.nameUUIDFromBytes("world".getBytes()),"world",chunk*16,64,0),1);store.addBuilding(b,"plains",definitions);return b;}
    Settlement settle(WorldStore s,EvolutionService e,CoreCity c,long p,Set<UUID> valid)throws Exception {CityView view=s.view(c.cityId()).orElseThrow();return new Simulation(s,definitions).settle(c,p,valid,e.cycle(c,view,p,valid,EvolutionState.Context.empty()));}
    @Test void additiveExpansionContainsFacilitiesDirectionsAndAllEventsButPreservesBaseOverrides()throws Exception {
        assertEquals(9,definitions.buildings().size());assertEquals(5,definitions.civilizations().size());assertEquals(11,definitions.events().size());assertEquals(6,options.plans().size());
        String old=Files.readString(temp.resolve("definitions.yml"));Files.writeString(temp.resolve("definitions.yml"),old.replace("agriculture: 1.3","agriculture: 1.7"));
        try(var mock=mockStatic(Material.class)){Material b=mock(Material.class);when(b.isBlock()).thenReturn(true);mock.when(()->Material.matchMaterial(anyString())).thenReturn(b);assertEquals(1.7,ConfigLoader.load(temp.resolve("config.yml").toFile(),temp.resolve("definitions.yml").toFile(),temp.resolve("evolution.yml").toFile()).terrains().get("plains").agriculture());}
    }
    @Test void educationMilitarySupplyAndCultureConsumeActualMaintainedCityResourcesOnce()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("city.db"),1000)){CoreCity c=city();Building academy=add(s,c,"academy",0),barracks=add(s,c,"barracks",1);s.stock(c.cityId(),"minecraft:bread",30);s.stock(c.cityId(),"minecraft:paper",10);s.stock(c.cityId(),"minecraft:arrow",20);EvolutionService e=new EvolutionService(s,definitions,options);
            assertTrue(settle(s,e,c,10,Set.of(academy.id(),barracks.id())).applied());assertEquals(1,e.profile(c.cityId()).knowledge());assertEquals(4,e.profile(c.cityId()).supply());assertEquals(16,s.stock(c.cityId()).get("minecraft:arrow"));assertFalse(e.cultures(c.cityId()).isEmpty());
            assertFalse(settle(s,e,c,10,Set.of(academy.id(),barracks.id())).applied());assertEquals(1,e.profile(c.cityId()).knowledge());assertEquals(16,s.stock(c.cityId()).get("minecraft:arrow"));
            settle(s,e,c,11,Set.of());assertEquals(1,e.profile(c.cityId()).knowledge());assertEquals(0,e.profile(c.cityId()).supply());
        }
    }
    @Test void unfundedAcademyCannotProduceKnowledgeAndForeignTerritoryCannotContribute()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("unfunded.db"),1000)){CoreCity c=city();Building a=add(s,c,"academy",0);EvolutionService e=new EvolutionService(s,definitions,options);settle(s,e,c,10,Set.of(a.id()));assertEquals(0,e.profile(c.cityId()).knowledge());assertTrue(e.cultures(c.cityId()).isEmpty());assertTrue(e.profile(c.cityId()).prosperity()<50);}
    }
    @Test void planningCooldownSurvivesRestartAndSpecialtyRequiresActualEducationAndVerifiedFacility()throws Exception {
        Path db=temp.resolve("plans.db");CoreCity c=city();Building farm;
        try(WorldStore s=new WorldStore(db,1000)){farm=add(s,c,"farm",0);EvolutionService e=new EvolutionService(s,definitions,options);e.plan(c,"industry",10,"mayor");assertThrows(Exception.class,()->e.plan(c,"agriculture",11,"mayor"));assertThrows(Exception.class,()->e.specialize(c,"granary",16,Set.of(farm.id()),"mayor"));s.execute("UPDATE evolution_city SET knowledge=5 WHERE city=?",c.cityId());assertThrows(Exception.class,()->e.specialize(c,"granary",16,Set.of(),"mayor"));e.specialize(c,"granary",16,Set.of(farm.id()),"mayor");}
        try(WorldStore s=new WorldStore(db,1000)){EvolutionService e=new EvolutionService(s,definitions,options);assertEquals("industry",e.profile(c.cityId()).plan());assertEquals("granary",e.profile(c.cityId()).specialty());assertThrows(Exception.class,()->e.plan(c,"balanced",21,"mayor"));assertEquals("balanced",e.plan(c,"balanced",22,"mayor").plan());}
    }
    @Test void tradeReceiptsDiffuseBothCulturesExactlyOnceAcrossRestart()throws Exception {
        Path db=temp.resolve("culture.db");CoreCity a=city(),b=city();Set<String> regionsA,regionsB;
        try(WorldStore s=new WorldStore(db,1000)){regionsA=Set.of(add(s,a,"farm",0).site().regionKey());regionsB=Set.of(add(s,b,"farm",1).site().regionKey());s.civilization(b.cityId(),"martial",1);EvolutionService e=new EvolutionService(s,definitions,options);assertTrue(e.exchange("trade-1",a.cityId(),b.cityId(),10,regionsA,regionsB));assertEquals(2,e.cultures(a.cityId()).getFirst().influence());assertEquals("martial",e.cultures(a.cityId()).getFirst().tradition());}
        try(WorldStore s=new WorldStore(db,1000)){EvolutionService e=new EvolutionService(s,definitions,options);assertFalse(e.exchange("trade-1",a.cityId(),b.cityId(),11,regionsA,regionsB));assertEquals(2,e.cultures(a.cityId()).getFirst().influence());}
    }
    @Test void cultureOnlyReachesExplicitlyVerifiedRegionsOnceEvenWithSeveralFacilities()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("verified-culture.db"),1000)){CoreCity a=city(),b=city();Building farm=add(s,a,"farm",0);add(s,b,"farm",1);
            Building workshop=new Building(UUID.randomUUID(),a.cityId(),a.nationId(),"workshop",new Site(farm.site().worldId(),"world",farm.site().x()+1,64,0),1);s.addBuilding(workshop,"plains",definitions);
            EvolutionService e=new EvolutionService(s,definitions,options);String region=farm.site().regionKey();
            assertThrows(Exception.class,()->e.exchange("bad-evidence",a.cityId(),b.cityId(),10,Set.of("unregistered"),Set.of()));assertTrue(e.cultures(a.cityId()).isEmpty());
            assertTrue(e.exchange("trade-verified",a.cityId(),b.cityId(),10,Set.of(region),Set.of()));assertEquals(2,e.cultures(a.cityId()).getFirst().influence());assertTrue(e.cultures(b.cityId()).isEmpty());
            assertTrue(e.exchange("trade-unloaded",a.cityId(),b.cityId(),11,Set.of(),Set.of()));assertEquals(2,e.cultures(a.cityId()).getFirst().influence());
            s.ensureCity(new CoreCity(a.cityId(),UUID.randomUUID(),a.cityName(),a.nationName(),1,""));
            assertThrows(Exception.class,()->e.exchange("trade-after-transfer",a.cityId(),b.cityId(),12,Set.of(region),Set.of()));assertEquals(2,e.cultures(a.cityId()).getFirst().influence());
        }
    }
    @Test void contextualEventsRespectConditionsAndPersistentCooldowns()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("events.db"),1000)){CoreCity c=city();Building b=add(s,c,"farm",0);EvolutionService e=new EvolutionService(s,definitions,options);CityView view=s.view(c.cityId()).orElseThrow();var hooks=e.cycle(c,view,10,Set.of(b.id()),EvolutionState.Context.empty());var candidates=hooks.eventWeights(view,10);assertTrue(candidates.containsKey("famine"));assertFalse(candidates.containsKey("flood"));assertFalse(candidates.containsKey("blizzard"));assertFalse(candidates.containsKey("commerce_boom"));
            var context=new EvolutionState.Context("drought","3",true,true,1,2,1);assertTrue(e.cycle(c,view,10,Set.of(b.id()),context).eventWeights(view,10).keySet().containsAll(Set.of("drought","border_dispute","commerce_boom","festival","resource_crisis")));
            s.execute("INSERT INTO evolution_event(city,type,period) VALUES(?,?,?)",c.cityId(),"famine",9);assertFalse(hooks.eventWeights(view,10).containsKey("famine"));
        }
    }
    @Test void failingExtensionRollsBackProductionMaintenanceCultureAndReceipt()throws Exception {
        try(WorldStore s=new WorldStore(temp.resolve("atomic.db"),1000)){CoreCity c=city();Building f=add(s,c,"farm",0);EvolutionService e=new EvolutionService(s,definitions,options);s.execute("CREATE TRIGGER reject_history BEFORE INSERT ON evolution_history BEGIN SELECT RAISE(ABORT,'extension failed'); END");assertThrows(Exception.class,()->settle(s,e,c,10,Set.of(f.id())));assertTrue(s.stock(c.cityId()).isEmpty());assertTrue(e.cultures(c.cityId()).isEmpty());assertFalse(s.settled(c.cityId(),10));assertEquals(-1,e.profile(c.cityId()).lastPeriod());assertTrue(s.query("SELECT building FROM evolution_facility",r->r.getString(1)).isEmpty());}
    }
    @Test void warehouseAddsBoundedCapacityAndEventHistoryKeepsLongTermConsequences()throws Exception {
        Path db=temp.resolve("capacity.db");CoreCity c=city();Building warehouse;
        try(WorldStore s=new WorldStore(db,1000)){warehouse=add(s,c,"warehouse",0);EvolutionService e=new EvolutionService(s,definitions,options);CityView v=s.view(c.cityId()).orElseThrow();assertEquals(250,e.capacity(v,Set.of()));assertEquals(250,e.capacity(v,Set.of(warehouse.id())),"Unfunded registration cannot expand storage");
            s.stock(c.cityId(),"minecraft:oak_planks",1);s.stock(c.cityId(),"minecraft:bread",10);settle(s,e,c,10,Set.of(warehouse.id()));assertEquals(500,e.capacity(s.view(c.cityId()).orElseThrow(),Set.of(warehouse.id())));assertEquals(500,s.cityCapacity(c.cityId()));assertEquals(0,s.stock(c.cityId()).get("minecraft:oak_planks"));}
        try(WorldStore s=new WorldStore(db,1000)){EvolutionService e=new EvolutionService(s,definitions,options);assertEquals(500,e.capacity(s.view(c.cityId()).orElseThrow(),Set.of(warehouse.id())),"Paid upkeep receipt survives restart");
            settle(s,e,c,11,Set.of(warehouse.id()));assertEquals(250,e.capacity(s.view(c.cityId()).orElseThrow(),Set.of(warehouse.id())),"Missed upkeep removes the next period bonus");assertEquals(250,s.cityCapacity(c.cityId()));
            s.stock(c.cityId(),"minecraft:oak_planks",1);settle(s,e,c,12,Set.of(warehouse.id()));assertEquals(500,e.capacity(s.view(c.cityId()).orElseThrow(),Set.of(warehouse.id())));assertEquals(500,s.cityCapacity(c.cityId()));assertEquals(250,e.capacity(s.view(c.cityId()).orElseThrow(),Set.of()),"Lost/unloaded anchors cannot use a former upkeep receipt");
            double before=e.profile(c.cityId()).prosperity();new Simulation(s,definitions).trigger(c.cityId(),"decline",13);settle(s,e,c,13,Set.of());assertTrue(e.profile(c.cityId()).prosperity()<before);assertEquals(250,e.capacity(s.view(c.cityId()).orElseThrow(),Set.of(warehouse.id())));assertTrue(e.history(c.cityId()).stream().anyMatch(h->h.kind().equals("city")));}
    }
}

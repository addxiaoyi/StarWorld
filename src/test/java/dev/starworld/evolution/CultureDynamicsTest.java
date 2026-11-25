package dev.starworld.evolution;

import dev.starworld.domain.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CultureDynamicsTest {
    @TempDir Path temp;
    Definitions definitions(){return new Definitions(300,64,20,100000,0,
            Map.of("plains",new Definitions.Terrain("plains","平原",1,1,1,Map.of("fertility",10000L),Map.of())),
            Map.of("agrarian",new Definitions.Civilization("agrarian","农耕",Map.of()),"industrial",new Definitions.Civilization("industrial","工业",Map.of())),
            Map.of("farm",new Definitions.BuildingType("farm","农场","COMPOSTER",Map.of()),"academy",new Definitions.BuildingType("academy","学院","LECTERN",Map.of("minecraft:paper",1L))),
            List.of(new Definitions.Recipe("harvest","farm",Map.of(),Map.of("minecraft:wheat",1L),"fertility",1)),Map.of());}
    EvolutionDefinitions options(){return new EvolutionDefinitions(Map.of("balanced",new EvolutionDefinitions.Plan("均衡",Map.of())),Map.of(),6,100,1,2,Map.of());}
    CoreCity city(){return new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"城市","国家",1,"fixture");}
    Building add(WorldStore s,Definitions d,CoreCity c,String type,int x)throws Exception{s.ensureCity(c);Building b=new Building(UUID.randomUUID(),c.cityId(),c.nationId(),type,new Site(UUID.nameUUIDFromBytes("world".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"world",x,64,0),1);s.addBuilding(b,"plains",d);return b;}
    long influence(WorldStore s,String region,String tradition)throws Exception{return s.query("SELECT influence FROM region_culture WHERE region=? AND tradition=?",r->r.getLong(1),region,tradition).stream().findFirst().orElse(0L);}
    void seed(WorldStore s,String region,String tradition,long amount)throws Exception{s.execute("INSERT INTO region_culture(region,tradition,influence) VALUES(?,?,?)",region,tradition,amount);}
    Settlement settle(WorldStore s,EvolutionService e,Definitions d,CoreCity c,long p,Set<UUID> valid,String policy)throws Exception{CityView v=s.view(c.cityId()).orElseThrow();return new Simulation(s,d).settle(c,p,valid,e.cycle(c,v,p,valid,EvolutionState.Context.empty(),policy));}

    @Test void agingIsRegionWideBoundedAndRestartSafeWithoutOfflineCatchUp()throws Exception {
        Definitions d=definitions();Path db=temp.resolve("aging.db");CoreCity c=city();Building f;String region;
        try(WorldStore s=new WorldStore(db,100000)){f=add(s,d,c,"farm",0);region=f.site().regionKey();EvolutionService e=new EvolutionService(s,d,options());seed(s,region,"industrial",100);settle(s,e,d,c,10,Set.of(f.id()),"");assertEquals(100,influence(s,region,"industrial"));settle(s,e,d,c,21,Set.of(f.id()),"");assertEquals(100,influence(s,region,"industrial"));settle(s,e,d,c,22,Set.of(f.id()),"");assertEquals(95,influence(s,region,"industrial"));assertFalse(settle(s,e,d,c,22,Set.of(f.id()),"").applied());assertEquals(95,influence(s,region,"industrial"));}
        try(WorldStore s=new WorldStore(db,100000)){EvolutionService e=new EvolutionService(s,d,options());settle(s,e,d,c,1000000,Set.of(f.id()),"");assertEquals(90,influence(s,region,"industrial"),"A long absence ages once, not hundreds of thousands of periods");assertFalse(settle(s,e,d,c,23,Set.of(f.id()),"").applied());assertEquals(90,influence(s,region,"industrial"));}
    }
    @Test void policiesChangeNewCultureOnlyForActuallyMaintainedFacilities()throws Exception {
        Definitions d=definitions();try(WorldStore s=new WorldStore(temp.resolve("policy.db"),100000)){CoreCity c=city();Building farm=add(s,d,c,"farm",0),a=add(s,d,c,"academy",16),b=add(s,d,c,"academy",32);EvolutionService e=new EvolutionService(s,d,options());s.stock(c.cityId(),"minecraft:paper",20);
            Set<UUID> valid=Set.of(farm.id(),a.id(),b.id());settle(s,e,d,c,10,valid,"open_diplomacy");assertEquals(4,influence(s,farm.site().regionKey(),"agrarian"),"Base three culture becomes four under audited open diplomacy");
            settle(s,e,d,c,11,valid,"fortified_borders");assertEquals(6,influence(s,farm.site().regionKey(),"agrarian"));settle(s,e,d,c,12,valid,"unknown_future_policy");assertEquals(9,influence(s,farm.site().regionKey(),"agrarian"));
            settle(s,e,d,c,13,Set.of(),"open_diplomacy");assertEquals(9,influence(s,farm.site().regionKey(),"agrarian"));assertEquals(9,influence(s,a.site().regionKey(),"agrarian"));
        }
    }
    @Test void failedSettlementRollsBackDecayMarkerCultureAndGoodsAndRetryAgesExactlyOnce()throws Exception {
        Definitions d=definitions();try(WorldStore s=new WorldStore(temp.resolve("rollback.db"),100000)){CoreCity c=city();Building f=add(s,d,c,"farm",0);String region=f.site().regionKey();EvolutionService e=new EvolutionService(s,d,options());seed(s,region,"industrial",100);s.stock(c.cityId(),"minecraft:bread",100);settle(s,e,d,c,10,Set.of(f.id()),"");long wheat=s.stock(c.cityId()).get("minecraft:wheat");
            s.execute("CREATE TRIGGER reject_aged_receipt BEFORE INSERT ON settlement BEGIN SELECT RAISE(ABORT,'culture aging crash'); END");assertThrows(Exception.class,()->settle(s,e,d,c,22,Set.of(f.id()),""));assertEquals(100,influence(s,region,"industrial"));assertEquals(10,s.query("SELECT last_period FROM culture_decay WHERE region=?",r->r.getLong(1),region).getFirst());assertEquals(wheat,s.stock(c.cityId()).get("minecraft:wheat"));assertFalse(s.settled(c.cityId(),22));
            s.execute("DROP TRIGGER reject_aged_receipt");settle(s,e,d,c,22,Set.of(f.id()),"");assertEquals(95,influence(s,region,"industrial"));assertEquals(22,s.query("SELECT last_period FROM culture_decay WHERE region=?",r->r.getLong(1),region).getFirst());assertEquals(wheat+1,s.stock(c.cityId()).get("minecraft:wheat"));
        }
    }
    @Test void sharedRegionAgesOnceForTwoCitiesAndUnmaintainedForeignSitesDoNotAdvanceClock()throws Exception {
        Definitions d=definitions();try(WorldStore s=new WorldStore(temp.resolve("shared.db"),100000)){CoreCity a=city(),b=city();Building first=add(s,d,a,"farm",0),second=add(s,d,b,"academy",1);String region=first.site().regionKey();EvolutionService e=new EvolutionService(s,d,options());seed(s,region,"industrial",100);s.stock(b.cityId(),"minecraft:paper",2);settle(s,e,d,a,10,Set.of(first.id()),"");settle(s,e,d,a,22,Set.of(first.id()),"");settle(s,e,d,b,22,Set.of(second.id()),"");assertEquals(95,influence(s,region,"industrial"));
            settle(s,e,d,a,100,Set.of(),"");assertEquals(22,s.query("SELECT last_period FROM culture_decay WHERE region=?",r->r.getLong(1),region).getFirst());assertEquals(95,influence(s,region,"industrial"));
        }
    }
    @Test void settingsPreserveLegacyFilesRejectInvalidBoundsAndFutureSchemaKeepsData()throws Exception {
        Path file=temp.resolve("evolution.yml");Files.writeString(file,"expansion-version: 1\n");assertEquals(CultureDynamics.Settings.defaults(),CultureDynamics.Settings.load(file.toFile()));
        Files.writeString(file,"culture:\n  decay-interval: 3\n  decay-basis-points: 1000\n  policy-influence: {open_diplomacy: 2}\n");var custom=CultureDynamics.Settings.load(file.toFile());assertEquals(3,custom.decayInterval());assertEquals(2,custom.policyInfluence().get("open_diplomacy"));
        Files.writeString(file,"culture:\n  decay-interval: 1.5\n");assertThrows(IllegalArgumentException.class,()->CultureDynamics.Settings.load(file.toFile()));Files.writeString(file,"culture: invalid\n");assertThrows(IllegalArgumentException.class,()->CultureDynamics.Settings.load(file.toFile()));Files.writeString(file,"culture:\n  policy-influence: invalid\n");assertThrows(IllegalArgumentException.class,()->CultureDynamics.Settings.load(file.toFile()));assertThrows(IllegalArgumentException.class,()->new CultureDynamics.Settings(1,1001,Map.of()));assertThrows(IllegalArgumentException.class,()->new CultureDynamics.Settings(1,0,Map.of("x",Double.NaN)));
        Definitions d=definitions();try(WorldStore s=new WorldStore(temp.resolve("future.db"),100000)){CoreCity c=city();Building f=add(s,d,c,"farm",0);new EvolutionService(s,d,options());seed(s,f.site().regionKey(),"industrial",7);s.execute("UPDATE culture_dynamics_schema SET version=99");assertThrows(java.sql.SQLException.class,()->new CultureDynamics(s,custom));assertEquals(7,influence(s,f.site().regionKey(),"industrial"));}
    }
}

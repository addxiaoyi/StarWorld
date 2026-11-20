package dev.starworld.persistence;

import dev.starworld.domain.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.evolution.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Run with -Dtest=WorldScaleTest: real SQLite/production paths, no world scanning or simulated timing. */
@Tag("scale")
class WorldScaleTest {
    @TempDir Path temp;
    @Test void thousandCitiesKeepPagedProductionAndRestartIdempotent()throws Exception {
        int cities=1000;UUID world=UUID.randomUUID();List<CoreCity> towns=new ArrayList<>();Map<UUID,Set<UUID>> valid=new HashMap<>();
        Definitions d=new Definitions(300,12,20,1_000_000,0,
            Map.of("plains",new Definitions.Terrain("plains","平原",1.3,.8,.9,Map.of("fertility",700L),Map.of("fertility",18L))),
            Map.of("agrarian",new Definitions.Civilization("agrarian","农耕",Map.of("farm",1.25))),
            Map.of("farm",new Definitions.BuildingType("farm","农场","COMPOSTER",Map.of())),
            List.of(new Definitions.Recipe("harvest","farm",Map.of(),Map.of("minecraft:wheat",12L),"fertility",12)),Map.of());
        EvolutionDefinitions rules=new EvolutionDefinitions(Map.of("balanced",new EvolutionDefinitions.Plan("均衡",Map.of())),Map.of(),6,100,1,2,Map.of());
        Path path=temp.resolve("scale.db");long seedStart=System.nanoTime(),seedNanos,settleNanos,queryNanos;long total;
        try(WorldStore store=new WorldStore(path,d.stockLimit())){
            EvolutionService evolution=new EvolutionService(store,d,rules);
            store.tx(s->{for(int i=0;i<cities;i++){
                CoreCity c=new CoreCity(UUID.randomUUID(),new UUID(0,1+i/20),"city"+i,"nation"+(i/20),1,"scale");towns.add(c);s.ensureCity(c);Set<UUID> ids=new HashSet<>();
                for(int n=0;n<2;n++){Building b=new Building(UUID.randomUUID(),c.cityId(),c.nationId(),"farm",new Site(world,"scale",i*32+n*16,64,0),1);s.addBuilding(b,"plains",d);ids.add(b.id());}valid.put(c.cityId(),Set.copyOf(ids));s.stock(c.cityId(),"minecraft:bread",100);
            }return null;});seedNanos=System.nanoTime()-seedStart;
            Simulation simulation=new Simulation(store,d);long start=System.nanoTime();
            for(long period=1;period<=3;period++)for(CoreCity c:towns){CityView before=store.view(c.cityId()).orElseThrow();assertTrue(simulation.settle(c,period,valid.get(c.cityId()),evolution.cycle(c,before,period,valid.get(c.cityId()),EvolutionState.Context.empty())).applied());}
            settleNanos=System.nanoTime()-start;start=System.nanoTime();Set<UUID> found=new HashSet<>();for(int offset=0;;offset+=20){var page=store.cities(20,offset);assertTrue(page.size()<=20);if(page.isEmpty())break;assertTrue(found.addAll(page));}assertEquals(cities,found.size());
            RegionAtlas atlas=new RegionAtlas(store,d);for(int i=0;i<towns.size();i+=10){var page=atlas.page(towns.get(i).cityId(),"",1);assertEquals(1,page.regions().size());assertFalse(page.nextCursor().isEmpty());assertEquals(2,atlas.summary(towns.get(i).cityId()).regions());}
            queryNanos=System.nanoTime()-start;total=store.query("SELECT SUM(amount) FROM stock WHERE item='minecraft:wheat'",r->r.getLong(1)).getFirst();assertTrue(total>0);assertEquals(3000,store.query("SELECT COUNT(*) FROM settlement",r->r.getInt(1)).getFirst());
        }
        try(WorldStore store=new WorldStore(path,d.stockLimit())){Simulation sim=new Simulation(store,d);for(CoreCity c:towns)assertFalse(sim.settle(c,3,valid.get(c.cityId())).applied());assertEquals(total,store.query("SELECT SUM(amount) FROM stock WHERE item='minecraft:wheat'",r->r.getLong(1)).getFirst());}
        String report="{\"schema\":1,\"cities\":1000,\"facilities\":2000,\"settlements\":3000,\"replayedAfterRestart\":1000,\"failures\":0,\"seedMillis\":"+seedNanos/1_000_000+",\"settlementMillis\":"+settleNanos/1_000_000+",\"pagedQueryMillis\":"+queryNanos/1_000_000+",\"databaseBytes\":"+Files.size(path)+",\"scope\":\"real SQLite and pure production paths; not Paper tick or world load throughput\"}\n";
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/scale-results.json"),report);System.out.println(report);
    }
}

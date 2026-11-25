package dev.starworld.evolution;

import dev.starworld.evolution.RegionAtlas.AtlasRegion;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionalMapTest {
    final UUID world=UUID.fromString("10000000-0000-0000-0000-000000000000"),other=UUID.fromString("20000000-0000-0000-0000-000000000000");
    AtlasRegion region(UUID dimension,int x,int z,String terrain,long ore){return new AtlasRegion(dimension+":"+x+":"+z,dimension,"world-"+dimension,x,z,terrain,terrain,1,1,1,4,Map.of("ore",ore),Map.of("ore",100L),Map.of("ore",2L),List.of(),List.of());}
    @Test void connectedDistrictsNeverBridgeDiagonalsDifferentTerrainsWorldsOrUnknownGaps(){
        List<AtlasRegion> input=List.of(region(world,-1,-1,"plains",7),region(world,0,-1,"plains",8),region(world,0,0,"plains",9),
                region(world,1,1,"plains",10),region(world,0,1,"forest",11),region(other,0,0,"plains",12),region(world,5,0,"plains",13));
        var groups=RegionalMap.districts(input);assertEquals(5,groups.size());var combined=groups.getFirst();
        assertEquals(3,combined.regions().size());assertEquals(Map.of("ore",24L),combined.reserves());assertEquals(300,combined.capacities().get("ore"));assertEquals(6,combined.regeneration().get("ore"));
        assertEquals(-1,combined.minChunkX());assertEquals(0,combined.maxChunkX());assertEquals(-1,combined.centerX());
        assertEquals(70,groups.stream().mapToLong(g->g.reserves().get("ore")).sum());assertEquals(7,groups.stream().mapToInt(g->g.regions().size()).sum());
        var shuffled=new ArrayList<>(input);Collections.shuffle(shuffled,new Random(17));assertEquals(groups,RegionalMap.districts(shuffled));
        assertThrows(UnsupportedOperationException.class,()->groups.clear());assertThrows(UnsupportedOperationException.class,()->combined.reserves().clear());assertThrows(UnsupportedOperationException.class,()->combined.regions().clear());
    }
    @Test void viewportPreservesNorthEastOrientationAndLeavesUnknownCellsAbsent(){
        List<AtlasRegion> regions=List.of(region(world,-4,-2,"plains",1),region(world,0,0,"plains",2),region(world,4,2,"forest",3),
                region(world,5,0,"plains",4),region(other,0,0,"plains",5));
        var view=RegionalMap.view(UUID.randomUUID(),UUID.randomUUID(),true,7,world,0,0,regions);
        assertEquals(List.of(0,22,44),view.cells().stream().map(RegionalMap.Cell::slot).toList());assertEquals(2,view.worlds().size());
        assertEquals(5,view.districts().size());assertTrue(view.cells().stream().allMatch(c->c.region().worldId().equals(world)));
        assertEquals(5,RegionalMap.districts(regions).size());
        assertThrows(UnsupportedOperationException.class,()->view.cells().clear());
        var inactive=RegionalMap.view(view.city(),view.nation(),false,7,world,0,0,regions);assertTrue(inactive.cells().isEmpty());assertTrue(inactive.worlds().isEmpty());assertTrue(inactive.districts().isEmpty());
    }
    @Test void fullBoundedAtlasGroupsWithoutReadingUnknownChunksAndRejectsInvalidInputs(){
        List<AtlasRegion> regions=new ArrayList<>();for(int i=0;i<128;i++)regions.add(region(world,i,0,"plains",1));
        assertEquals(128,RegionalMap.districts(regions).getFirst().regions().size());
        assertEquals(9,RegionalMap.view(UUID.randomUUID(),UUID.randomUUID(),true,0,world,64,0,regions).cells().size());
        var overflow=new ArrayList<>(regions);overflow.add(region(world,128,0,"plains",1));assertThrows(IllegalArgumentException.class,()->RegionalMap.districts(overflow));
        assertThrows(IllegalArgumentException.class,()->RegionalMap.districts(List.of(regions.getFirst(),regions.getFirst())));
        assertThrows(IllegalArgumentException.class,()->RegionalMap.coordinates(Integer.MIN_VALUE,0));assertThrows(IllegalArgumentException.class,()->RegionalMap.coordinates(0,RegionalMap.MAX_CHUNK+1));
        assertThrows(IllegalArgumentException.class,()->RegionalMap.districts(List.of(region(world,Integer.MAX_VALUE,0,"plains",1))));
    }
}

package dev.starworld.evolution;

import dev.starworld.evolution.RegionAtlas.AtlasRegion;
import java.util.*;

/** Geographic projection of at most 128 registered regions; never discovers or loads world chunks. */
public final class RegionalMap {
    public static final int WIDTH=9, HEIGHT=5, MAX_CHUNK=1_875_000;
    private RegionalMap() {}
    public record District(String id,UUID worldId,String worldName,String terrain,String terrainName,
                           int minChunkX,int minChunkZ,int maxChunkX,int maxChunkZ,List<String> regions,int facilities,
                           Map<String,Long> reserves,Map<String,Long> capacities,Map<String,Long> regeneration) {
        public District {regions=List.copyOf(regions);reserves=Map.copyOf(reserves);capacities=Map.copyOf(capacities);regeneration=Map.copyOf(regeneration);}
        public int centerX(){return (int)Math.floorDiv((long)minChunkX+maxChunkX,2);}
        public int centerZ(){return (int)Math.floorDiv((long)minChunkZ+maxChunkZ,2);}
    }
    public record WorldRef(UUID id,String name) {}
    public record Cell(int column,int row,AtlasRegion region,String districtId) {
        public Cell {if(column<0||column>=WIDTH||row<0||row>=HEIGHT)throw new IllegalArgumentException("Cell outside map");Objects.requireNonNull(region);Objects.requireNonNull(districtId);}
        public int slot(){return row*WIDTH+column;}
    }
    public record DistrictReport(UUID city,UUID nation,boolean authorityActive,long cityLastPeriod,List<District> districts) {
        public DistrictReport {districts=List.copyOf(districts);}
    }
    /** North is negative Z at the top; east is positive X at the right. Empty cells are unknown. */
    public record View(UUID city,UUID nation,boolean authorityActive,long cityLastPeriod,UUID worldId,String worldName,
                       int centerX,int centerZ,List<Cell> cells,List<WorldRef> worlds,List<District> districts) {
        public View {cells=List.copyOf(cells);worlds=List.copyOf(worlds);districts=List.copyOf(districts);}
    }
    private record Position(UUID world,int x,int z) {}
    private static final Comparator<AtlasRegion> ORDER=Comparator.comparing((AtlasRegion r)->r.worldId().toString())
            .thenComparingInt(AtlasRegion::chunkZ).thenComparingInt(AtlasRegion::chunkX);
    public static void coordinates(int x,int z){if(x < -MAX_CHUNK||x>MAX_CHUNK||z < -MAX_CHUNK||z>MAX_CHUNK)throw new IllegalArgumentException("地图区块坐标超出世界范围");}

    /** Four-neighbor, same-terrain components. Grouping sums each original reserve exactly once. */
    public static List<District> districts(List<AtlasRegion> regions){
        if(regions.size()>RegionAtlas.MAX_REGIONS_PER_CITY)throw new IllegalArgumentException("Too many regions for map");
        Map<Position,AtlasRegion> remaining=new HashMap<>();Set<String> ids=new HashSet<>();
        for(AtlasRegion r:regions){coordinates(r.chunkX(),r.chunkZ());
            if(!ids.add(r.id())||remaining.put(new Position(r.worldId(),r.chunkX(),r.chunkZ()),r)!=null)throw new IllegalArgumentException("Duplicate atlas region");}
        List<District> result=new ArrayList<>();
        for(AtlasRegion seed:regions.stream().sorted(ORDER).toList()){
            if(remaining.remove(new Position(seed.worldId(),seed.chunkX(),seed.chunkZ()))==null)continue;
            List<AtlasRegion> members=new ArrayList<>();ArrayDeque<AtlasRegion> queue=new ArrayDeque<>();queue.add(seed);
            while(!queue.isEmpty()){
                AtlasRegion current=queue.removeFirst();members.add(current);
                for(int[] direction:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}){
                    Position neighbor=new Position(current.worldId(),current.chunkX()+direction[0],current.chunkZ()+direction[1]);
                    AtlasRegion candidate=remaining.get(neighbor);
                    if(candidate!=null&&candidate.terrain().equals(seed.terrain())){remaining.remove(neighbor);queue.addLast(candidate);}
                }
            }
            members.sort(ORDER);Map<String,Long> reserves=new TreeMap<>(),capacities=new TreeMap<>(),regeneration=new TreeMap<>();int facilities=0;
            int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for(AtlasRegion r:members){sum(reserves,r.reserves());sum(capacities,r.initialCapacities());sum(regeneration,r.regeneration());facilities+=r.facilities().size();minX=Math.min(minX,r.chunkX());minZ=Math.min(minZ,r.chunkZ());maxX=Math.max(maxX,r.chunkX());maxZ=Math.max(maxZ,r.chunkZ());}
            result.add(new District(seed.id(),seed.worldId(),seed.worldName(),seed.terrain(),seed.terrainName(),minX,minZ,maxX,maxZ,members.stream().map(AtlasRegion::id).toList(),facilities,reserves,capacities,regeneration));
        }
        return List.copyOf(result);
    }
    public static View view(UUID city,UUID nation,boolean active,long period,UUID world,int centerX,int centerZ,List<AtlasRegion> regions){
        Objects.requireNonNull(world);coordinates(centerX,centerZ);
        List<AtlasRegion> visible=active?List.copyOf(regions):List.of();List<District> groups=districts(visible);
        Map<String,String> membership=new HashMap<>();for(District group:groups)for(String id:group.regions())membership.put(id,group.id());
        Map<UUID,WorldRef> worlds=new TreeMap<>();for(AtlasRegion region:visible)worlds.putIfAbsent(region.worldId(),new WorldRef(region.worldId(),region.worldName()));
        List<Cell> cells=new ArrayList<>();
        for(AtlasRegion region:visible){
            if(!region.worldId().equals(world))continue;
            int column=region.chunkX()-centerX+WIDTH/2,row=region.chunkZ()-centerZ+HEIGHT/2;
            if(column>=0&&column<WIDTH&&row>=0&&row<HEIGHT)cells.add(new Cell(column,row,region,membership.get(region.id())));
        }
        cells.sort(Comparator.comparingInt(Cell::slot));
        return new View(city,nation,active,period,world,worlds.containsKey(world)?worlds.get(world).name():"未登记世界",centerX,centerZ,cells,new ArrayList<>(worlds.values()),groups);
    }
    private static void sum(Map<String,Long> into,Map<String,Long> values){values.forEach((key,value)->into.merge(key,value,Math::addExact));}
}

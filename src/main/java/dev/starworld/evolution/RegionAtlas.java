package dev.starworld.evolution;

import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.Building;
import dev.starworld.domain.WorldState.Site;
import dev.starworld.persistence.WorldStore;
import java.sql.SQLException;
import java.util.*;

/** Bounded read-only geography snapshots. No Bukkit calls, chunk loads or world discovery happen here. */
public final class RegionAtlas {
    public static final int MAX_PAGE_SIZE=64;
    public static final int MAX_REGIONS_PER_CITY=WorldStore.MAX_CITY_REGIONS;
    private final WorldStore store;
    private final Definitions definitions;

    public RegionAtlas(WorldStore store,Definitions definitions) {
        this.store=Objects.requireNonNull(store);this.definitions=Objects.requireNonNull(definitions);
    }
    public record Facility(UUID id,String type,int level) {}
    public record AtlasRegion(String id,UUID worldId,String worldName,int chunkX,int chunkZ,
                              String terrain,String terrainName,double agriculture,double transport,double defense,
                              long lastRecoveryPeriod,Map<String,Long> reserves,Map<String,Long> initialCapacities,
                              Map<String,Long> regeneration,List<EvolutionState.Culture> culture,List<Facility> facilities) {
        public AtlasRegion {
            reserves=Map.copyOf(reserves);initialCapacities=Map.copyOf(initialCapacities);regeneration=Map.copyOf(regeneration);
            culture=List.copyOf(culture);facilities=List.copyOf(facilities);
        }
    }
    /** Empty nextCursor means the final page; a cursor is only an ordering key, never an authority token. */
    public record Page(UUID city,UUID nation,boolean authorityActive,long cityLastPeriod,List<AtlasRegion> regions,String nextCursor) {
        public Page {regions=List.copyOf(regions);Objects.requireNonNull(nextCursor);}
    }
    public record Summary(UUID city,UUID nation,boolean authorityActive,long cityLastPeriod,int regions,int facilities,
                          Map<String,Integer> terrains,Map<String,Long> reserves,Map<String,Long> initialCapacities,
                          Map<String,Long> regeneration) {
        public Summary {
            terrains=Map.copyOf(terrains);reserves=Map.copyOf(reserves);
            initialCapacities=Map.copyOf(initialCapacities);regeneration=Map.copyOf(regeneration);
        }
    }
    private record CityIdentity(UUID nation,boolean active,long period) {}
    private record RegionKey(String id,String worldName) {}

    public RegionalMap.DistrictReport districts(UUID city)throws SQLException {
        return store.tx(s->{CityIdentity identity=identity(city);return new RegionalMap.DistrictReport(city,identity.nation(),identity.active(),identity.period(),RegionalMap.districts(all(city,identity)));});
    }
    public RegionalMap.View map(UUID city,UUID world,int centerX,int centerZ)throws SQLException {
        RegionalMap.coordinates(centerX,centerZ);Objects.requireNonNull(world);
        return store.tx(s->{CityIdentity identity=identity(city);return RegionalMap.view(city,identity.nation(),identity.active(),identity.period(),world,centerX,centerZ,all(city,identity));});
    }
    private List<AtlasRegion> all(UUID city,CityIdentity identity)throws SQLException {
        if(!identity.active())return List.of();
        List<RegionKey> keys=store.query("SELECT region,world_name FROM city_region WHERE city=? AND nation=? ORDER BY region LIMIT ?",
                r->new RegionKey(r.getString(1),r.getString(2)),city,identity.nation(),MAX_REGIONS_PER_CITY+1);
        if(keys.size()>MAX_REGIONS_PER_CITY)throw new IllegalStateException("Region atlas refuses more than 128 regions in one city");
        Map<String,List<Facility>> facilities=facilities(city,identity.nation());List<AtlasRegion> result=new ArrayList<>();
        for(RegionKey key:keys)result.add(region(key,facilities.getOrDefault(key.id(),List.of())));
        return List.copyOf(result);
    }

    public Page page(UUID city,String after,int limit)throws SQLException {
        Objects.requireNonNull(city);if(limit<1||limit>MAX_PAGE_SIZE)throw new IllegalArgumentException("Region page size must be 1–64");
        String cursor=after==null?"":after;
        if(!cursor.isEmpty()&&!cursor.matches("[0-9a-fA-F-]{36}:-?[0-9]{1,10}:-?[0-9]{1,10}"))throw new IllegalArgumentException("Invalid region cursor");
        return store.tx(s->{
            CityIdentity identity=identity(city);
            if(!identity.active())return new Page(city,identity.nation(),false,identity.period(),List.of(),"");
            List<RegionKey> keys=s.query("SELECT region,world_name FROM city_region WHERE city=? AND nation=? AND region>? ORDER BY region LIMIT ?",
                    r->new RegionKey(r.getString(1),r.getString(2)),city,identity.nation(),cursor,limit+1);
            boolean hasMore=keys.size()>limit;
            List<RegionKey> visible=hasMore?keys.subList(0,limit):keys;
            Map<String,List<Facility>> facilities=facilities(city,identity.nation());
            List<AtlasRegion> regions=new ArrayList<>(visible.size());
            for(RegionKey key:visible)regions.add(region(key,facilities.getOrDefault(key.id(),List.of())));
            String next=hasMore?visible.getLast().id():"";
            return new Page(city,identity.nation(),true,identity.period(),regions,next);
        });
    }
    /** Aggregation visits at most the hard per-city region limit. */
    public Summary summary(UUID city)throws SQLException {
        Objects.requireNonNull(city);
        return store.tx(s->{
            CityIdentity identity=identity(city);
            if(!identity.active())return new Summary(city,identity.nation(),false,identity.period(),0,0,Map.of(),Map.of(),Map.of(),Map.of());
            List<RegionKey> keys=s.query("SELECT region,world_name FROM city_region WHERE city=? AND nation=? ORDER BY region LIMIT ?",
                    r->new RegionKey(r.getString(1),r.getString(2)),city,identity.nation(),MAX_REGIONS_PER_CITY+1);
            if(keys.size()>MAX_REGIONS_PER_CITY)throw new IllegalStateException("Region atlas refuses more than 128 regions in one city");
            Map<String,List<Facility>> facilities=facilities(city,identity.nation());
            Map<String,Integer> terrains=new TreeMap<>();Map<String,Long> reserves=new TreeMap<>(),capacities=new TreeMap<>(),regeneration=new TreeMap<>();int facilityCount=0;
            for(RegionKey key:keys){AtlasRegion region=region(key,facilities.getOrDefault(key.id(),List.of()));terrains.merge(region.terrain(),1,Integer::sum);facilityCount+=region.facilities().size();sum(reserves,region.reserves());sum(capacities,region.initialCapacities());sum(regeneration,region.regeneration());}
            return new Summary(city,identity.nation(),true,identity.period(),keys.size(),facilityCount,terrains,reserves,capacities,regeneration);
        });
    }
    private CityIdentity identity(UUID city)throws SQLException {
        return store.query("SELECT c.nation,COALESCE(a.active,0),c.last_period FROM city_state c LEFT JOIN city_authority a ON a.city=c.id WHERE c.id=?",
                r->new CityIdentity(UUID.fromString(r.getString(1)),r.getInt(2)==1,r.getLong(3)),city).stream().findFirst().orElseThrow(()->new IllegalArgumentException("城市尚未登记"));
    }
    private Map<String,List<Facility>> facilities(UUID city,UUID nation)throws SQLException {
        List<Building> buildings=store.query("SELECT * FROM building WHERE city=? AND nation=? ORDER BY world,x,z,id LIMIT 65",
                r->new Building(UUID.fromString(r.getString("id")),city,nation,r.getString("type"),new Site(UUID.fromString(r.getString("world")),r.getString("world_name"),r.getInt("x"),r.getInt("y"),r.getInt("z")),r.getInt("level")),city,nation);
        if(buildings.size()>64)throw new IllegalStateException("Region atlas refuses more than 64 facilities in one city");
        Map<String,List<Facility>> grouped=new HashMap<>();
        for(Building building:buildings)grouped.computeIfAbsent(building.site().regionKey(),ignored->new ArrayList<>()).add(new Facility(building.id(),building.type(),building.level()));
        return grouped;
    }
    private AtlasRegion region(RegionKey key,List<Facility> facilities)throws SQLException {
        String[] parts=key.id().split(":",3);
        if(parts.length!=3)throw new IllegalStateException("Invalid saved region key");
        UUID world=UUID.fromString(parts[0]);int chunkX=Integer.parseInt(parts[1]),chunkZ=Integer.parseInt(parts[2]);
        Object[] saved=store.query("SELECT terrain,last_regen FROM region WHERE id=?",r->new Object[]{r.getString(1),r.getLong(2)},key.id()).stream().findFirst().orElseThrow(()->new IllegalStateException("Registered region missing"));
        String terrainId=(String)saved[0];Definitions.Terrain terrain=definitions.terrains().get(terrainId);
        if(terrain==null)throw new IllegalStateException("Unknown saved terrain "+terrainId);
        Map<String,Long> reserves=new TreeMap<>();store.query("SELECT resource,amount FROM reserve WHERE region=? ORDER BY resource",r->{reserves.put(r.getString(1),r.getLong(2));return 0;},key.id());
        List<EvolutionState.Culture> culture=store.query("SELECT tradition,influence FROM region_culture WHERE region=? ORDER BY influence DESC,tradition LIMIT 64",r->new EvolutionState.Culture(key.id(),r.getString(1),r.getLong(2)),key.id());
        return new AtlasRegion(key.id(),world,key.worldName(),chunkX,chunkZ,terrainId,terrain.name(),terrain.agriculture(),terrain.transport(),terrain.military(),(long)saved[1],reserves,terrain.reserves(),terrain.regeneration(),culture,facilities);
    }
    private static void sum(Map<String,Long> target,Map<String,Long> values){values.forEach((key,value)->target.merge(key,value,Math::addExact));}
}

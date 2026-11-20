package dev.starworld.evolution;

import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.Building;
import dev.starworld.domain.WorldState.Site;
import dev.starworld.persistence.WorldStore;
import java.sql.SQLException;
import java.util.*;

/**
 * Read-only geography from a city's registered extension sites. No Bukkit calls,
 * chunk loads or world discovery happen here. Authority is the most recent
 * bounded StarCore reconciliation, not a fresh claim or physical-anchor proof.
 */
public final class RegionAtlas {
    public static final int MAX_PAGE_SIZE=64;
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

    public Page page(UUID city,String after,int limit)throws SQLException {
        Objects.requireNonNull(city);if(limit<1||limit>MAX_PAGE_SIZE)throw new IllegalArgumentException("Region page size must be 1–64");
        String cursor=after==null?"":after;
        if(!cursor.isEmpty()&&!cursor.matches("[0-9a-fA-F-]{36}:-?[0-9]{1,10}:-?[0-9]{1,10}"))throw new IllegalArgumentException("Invalid region cursor");
        return store.tx(s->{
            CityIdentity identity=identity(city);List<AtlasRegion> all=regions(city,identity);
            List<AtlasRegion> remaining=all.stream().filter(r->r.id().compareTo(cursor)>0).toList();
            List<AtlasRegion> visible=remaining.subList(0,Math.min(limit,remaining.size()));
            String next=remaining.size()>visible.size()?visible.getLast().id():"";
            return new Page(city,identity.nation(),identity.active(),identity.period(),visible,next);
        });
    }
    /** Per-region resources are counted once even if several facility types share the same site chunk. */
    public Summary summary(UUID city)throws SQLException {
        Objects.requireNonNull(city);
        return store.tx(s->{
            CityIdentity identity=identity(city);List<AtlasRegion> regions=regions(city,identity);
            Map<String,Integer> terrains=new TreeMap<>();Map<String,Long> reserves=new TreeMap<>(),capacities=new TreeMap<>(),regeneration=new TreeMap<>();
            int facilities=0;
            for(AtlasRegion region:regions){terrains.merge(region.terrain(),1,Integer::sum);facilities+=region.facilities().size();sum(reserves,region.reserves());sum(capacities,region.initialCapacities());sum(regeneration,region.regeneration());}
            return new Summary(city,identity.nation(),identity.active(),identity.period(),regions.size(),facilities,terrains,reserves,capacities,regeneration);
        });
    }
    private CityIdentity identity(UUID city)throws SQLException {
        return store.query("SELECT c.nation,COALESCE(a.active,0),c.last_period FROM city_state c LEFT JOIN city_authority a ON a.city=c.id WHERE c.id=?",
                r->new CityIdentity(UUID.fromString(r.getString(1)),r.getInt(2)==1,r.getLong(3)),city).stream().findFirst().orElseThrow(()->new IllegalArgumentException("城市尚未登记"));
    }
    private List<AtlasRegion> regions(UUID city,CityIdentity identity)throws SQLException {
        if(!identity.active())return List.of();
        // The public configuration caps a city at 64 facilities. Keep the bound
        // explicit even for hand-edited/corrupt legacy databases.
        List<Building> buildings=store.query("SELECT * FROM building WHERE city=? AND nation=? ORDER BY world,x,z,id LIMIT 65",
                r->new Building(UUID.fromString(r.getString("id")),city,identity.nation(),r.getString("type"),new Site(UUID.fromString(r.getString("world")),r.getString("world_name"),r.getInt("x"),r.getInt("y"),r.getInt("z")),r.getInt("level")),city,identity.nation());
        if(buildings.size()>64)throw new IllegalStateException("Region atlas refuses more than 64 facilities in one city");
        Map<String,List<Building>> grouped=new TreeMap<>();for(Building building:buildings)grouped.computeIfAbsent(building.site().regionKey(),ignored->new ArrayList<>()).add(building);
        List<AtlasRegion> result=new ArrayList<>();
        for(var group:grouped.entrySet()){
            String key=group.getKey();Site site=group.getValue().getFirst().site();
            Object[] saved=store.query("SELECT terrain,last_regen FROM region WHERE id=?",r->new Object[]{r.getString(1),r.getLong(2)},key).stream().findFirst().orElseThrow(()->new IllegalStateException("Registered region missing"));
            String terrainId=(String)saved[0];Definitions.Terrain terrain=definitions.terrains().get(terrainId);
            if(terrain==null)throw new IllegalStateException("Unknown saved terrain "+terrainId);
            Map<String,Long> reserves=new TreeMap<>();store.query("SELECT resource,amount FROM reserve WHERE region=? ORDER BY resource",r->{reserves.put(r.getString(1),r.getLong(2));return 0;},key);
            List<EvolutionState.Culture> culture=store.query("SELECT tradition,influence FROM region_culture WHERE region=? ORDER BY influence DESC,tradition LIMIT 64",r->new EvolutionState.Culture(key,r.getString(1),r.getLong(2)),key);
            result.add(new AtlasRegion(key,site.worldId(),site.worldName(),site.x()>>4,site.z()>>4,terrainId,terrain.name(),terrain.agriculture(),terrain.transport(),terrain.military(),(long)saved[1],reserves,terrain.reserves(),terrain.regeneration(),culture,
                    group.getValue().stream().map(b->new Facility(b.id(),b.type(),b.level())).toList()));
        }
        return List.copyOf(result);
    }
    private static void sum(Map<String,Long> target,Map<String,Long> values){values.forEach((key,value)->target.merge(key,value,Math::addExact));}
}

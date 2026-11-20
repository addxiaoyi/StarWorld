package dev.starworld.evolution;

import java.util.*;

public final class EvolutionState {
    private EvolutionState(){}
    public record Profile(UUID city,String plan,String specialty,long changed,long lastPeriod,
                          double prosperity,double stability,long knowledge,long supply,long culture,
                          Map<String,Long> tendencies) {
        public Profile {tendencies=Map.copyOf(tendencies);}
    }
    public record Context(String weather,String era,boolean atWar,boolean borderPressure,
                          long successfulTrades,long lifeCulture,double lifeMultiplier,List<BorderWitness> borderWitnesses) {
        public Context {Objects.requireNonNull(weather);Objects.requireNonNull(era);borderWitnesses=List.copyOf(borderWitnesses);if(successfulTrades<0||lifeCulture<0||!Double.isFinite(lifeMultiplier)||lifeMultiplier<0||lifeMultiplier>3||borderWitnesses.size()>256)throw new IllegalArgumentException("Invalid evolution context");}
        public Context(String weather,String era,boolean atWar,boolean borderPressure,long successfulTrades,long lifeCulture,double lifeMultiplier){this(weather,era,atWar,borderPressure,successfulTrades,lifeCulture,lifeMultiplier,List.of());}
        public static Context empty(){return new Context("unknown","unknown",false,false,0,0,1);}
    }
    /** Immutable main-thread evidence about a verified facility region and exactly one neighbouring foreign claim. */
    public record BorderWitness(UUID city,UUID nation,UUID worldId,String worldName,int chunkX,int chunkZ,
                                int neighborChunkX,int neighborChunkZ,UUID peerNation,String relation,long period) {
        public BorderWitness {Objects.requireNonNull(city);Objects.requireNonNull(nation);Objects.requireNonNull(worldId);Objects.requireNonNull(worldName);Objects.requireNonNull(peerNation);if(nation.equals(peerNation)||period<0||!Set.of("WAR","HOSTILE").contains(relation)||Math.abs((long)chunkX-neighborChunkX)+Math.abs((long)chunkZ-neighborChunkZ)!=1)throw new IllegalArgumentException("Invalid border witness");}
        public String regionKey(){return worldId+":"+chunkX+":"+chunkZ;}
    }
    /** The local resource association is resolved from current SQLite reserves inside the settlement transaction. */
    public record ResourceDispute(BorderWitness border,String resource,long remaining) {
        // Reserve keys follow ConfigLoader.amounts (namespaced/path keys included),
        // rather than the narrower civilization/terrain definition-ID grammar.
        public ResourceDispute {Objects.requireNonNull(border);if(resource==null||!resource.matches("[a-z0-9_./-]+(:[a-z0-9_./-]+)?")||remaining<=0)throw new IllegalArgumentException("Resource dispute requires a positive local reserve");}
    }
    public record History(long period,String kind,String detail){}
    public record Culture(String region,String tradition,long influence){}
    public record Readiness(UUID city,UUID nation,long period,long provided,long required,double terrainDefense,double readiness){}
}

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
                          long successfulTrades,long lifeCulture,double lifeMultiplier) {
        public Context {Objects.requireNonNull(weather);Objects.requireNonNull(era);if(successfulTrades<0||lifeCulture<0||!Double.isFinite(lifeMultiplier)||lifeMultiplier<0||lifeMultiplier>3)throw new IllegalArgumentException("Invalid evolution context");}
        public static Context empty(){return new Context("unknown","unknown",false,false,0,0,1);}
    }
    public record History(long period,String kind,String detail){}
    public record Culture(String region,String tradition,long influence){}
    public record Readiness(UUID city,UUID nation,long period,long provided,long required,double terrainDefense,double readiness){}
}

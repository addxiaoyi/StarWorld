package dev.starworld.api;

import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.CityView;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Registered through Bukkit ServicesManager. No Bukkit objects in asynchronous results. */
public interface StarWorldApi {
    String version();
    Definitions definitions();
    CompletableFuture<Optional<CityView>> city(UUID cityId);
    Map<String,String> integrationStatus();
    default CompletableFuture<Optional<dev.starworld.evolution.EvolutionState.Profile>> development(UUID cityId){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<List<dev.starworld.evolution.EvolutionState.Culture>> culture(UUID cityId){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<Optional<dev.starworld.life.LifeState.LifeView>> life(UUID cityId){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<List<dev.starworld.logistics.LogisticsState.Route>> routes(UUID cityId,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<List<dev.starworld.logistics.LogisticsState.Order>> orders(UUID cityId,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<Optional<dev.starworld.evolution.EvolutionState.Readiness>> readiness(UUID cityId){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<Optional<dev.starworld.evolution.NationalEvolution.NationDevelopment>> nationDevelopment(UUID nationId){return CompletableFuture.completedFuture(Optional.empty());}
}

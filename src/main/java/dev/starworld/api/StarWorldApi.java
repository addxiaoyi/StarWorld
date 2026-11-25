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
    record ClimateExposure(UUID buildingId,String buildingType,String worldName,String weatherId,
                           boolean controlledByNation,boolean irrigationOperating,Map<String,Double> recipeFactors) {
        public ClimateExposure {Objects.requireNonNull(buildingId);Objects.requireNonNull(buildingType);Objects.requireNonNull(worldName);weatherId=weatherId==null?"unknown":weatherId;recipeFactors=Map.copyOf(recipeFactors);}
    }
    record ClimateReport(UUID cityId,UUID nationId,List<ClimateExposure> exposures) {
        public ClimateReport {Objects.requireNonNull(cityId);Objects.requireNonNull(nationId);exposures=List.copyOf(exposures);if(exposures.size()>64)throw new IllegalArgumentException("Too many climate exposures");}
    }
    /** Per-world StarCore weather projected onto verified city recipes; irrigation reflects its last settled paid period. Querying never advances production. */
    default CompletableFuture<Optional<ClimateReport>> climate(UUID cityId){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<Optional<dev.starworld.evolution.EvolutionState.Profile>> development(UUID cityId){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<List<dev.starworld.evolution.EvolutionState.Culture>> culture(UUID cityId){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<Optional<dev.starworld.life.LifeState.LifeView>> life(UUID cityId){return CompletableFuture.completedFuture(Optional.empty());}
    /** Exact committed contribution credit; never drains culture or exposes the acting player's identity. */
    default CompletableFuture<Optional<dev.starworld.life.LifeState.EvolutionCredit>> lifeEvolutionCredit(UUID actionId){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<List<dev.starworld.logistics.LogisticsState.Route>> routes(UUID cityId,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<List<dev.starworld.logistics.LogisticsState.RoutePlan>> routePlans(UUID originCity,UUID targetCity,dev.starworld.logistics.LogisticsState.Mode mode,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<List<dev.starworld.logistics.LogisticsState.Order>> orders(UUID cityId,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<Optional<dev.starworld.evolution.EvolutionState.Readiness>> readiness(UUID cityId){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<Optional<dev.starworld.evolution.NationalEvolution.NationDevelopment>> nationDevelopment(UUID nationId){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<Optional<dev.starworld.evolution.RegionAtlas.Page>> regions(UUID cityId,String after,int limit){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<Optional<dev.starworld.evolution.RegionAtlas.Summary>> geography(UUID cityId){return CompletableFuture.completedFuture(Optional.empty());}
    /** Four-neighbor groups of current-nation registered regions, without moving any original reserve. */
    default CompletableFuture<Optional<dev.starworld.evolution.RegionalMap.DistrictReport>> districts(UUID cityId){return CompletableFuture.completedFuture(Optional.empty());}
    /** Fixed 9 by 5 chunk viewport. Missing cells remain unknown; this call does not inspect Bukkit worlds. */
    default CompletableFuture<Optional<dev.starworld.evolution.RegionalMap.View>> regionMap(UUID cityId,UUID worldId,int centerChunkX,int centerChunkZ){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<Optional<dev.starworld.logistics.LogisticsState.TransportStatus>> transport(UUID routeId){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<List<dev.starworld.market.MarketState.Listing>> marketListings(int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<List<dev.starworld.market.MarketState.Purchase>> marketPurchases(UUID cityId,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<List<dev.starworld.market.MarketState.DeliveryStats>> marketDeliveryStats(UUID cityId,String item,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    /** Immutable response receipts only; gameplay requests remain at the authorized player boundary. */
    default CompletableFuture<Optional<dev.starworld.disaster.DisasterState.Response>> disasterResponse(UUID requestId){return CompletableFuture.completedFuture(Optional.empty());}
    default CompletableFuture<List<dev.starworld.disaster.DisasterState.Response>> disasterResponses(UUID cityId,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<List<dev.starworld.disaster.DisasterState.History>> disasterHistory(UUID cityId,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<List<dev.starworld.works.PublicWorksState.Project>> publicWorks(UUID cityId,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<List<dev.starworld.works.PublicWorksState.History>> publicWorksHistory(UUID cityId,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<List<dev.starworld.civic.CivicNeedsState.Request>> civicNeeds(UUID cityId,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    default CompletableFuture<List<dev.starworld.civic.CivicNeedsState.History>> civicHistory(UUID cityId,int limit,int offset){return CompletableFuture.completedFuture(List.of());}
    /** Completed receipt snapshot with applied=false; querying never completes a request or claims an action. */
    default CompletableFuture<Optional<dev.starworld.civic.CivicNeedsState.Receipt>> civicReceipt(UUID requestId){return CompletableFuture.completedFuture(Optional.empty());}
}

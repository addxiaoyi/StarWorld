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
}

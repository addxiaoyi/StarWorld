package dev.starworld.integration;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.domain.WorldState.Site;
import java.util.*;

/** Read-only StarCore boundary. Implementations must fail closed when ownership cannot be proven. */
public interface CoreBridge {
    Optional<CoreCity> playerCity(UUID player);
    Optional<CoreCity> city(UUID city);
    boolean canManage(UUID player, UUID city);
    boolean canGovern(UUID player, UUID city);
    boolean ownsSite(UUID city, Site site);
    String status();
}

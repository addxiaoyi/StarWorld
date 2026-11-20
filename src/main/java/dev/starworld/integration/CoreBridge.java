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
    /** Main-thread public claim reads only; never loads any neighbouring chunk. */
    default List<BorderNeighbor> foreignNeighbors(CoreCity city,Site site){return List.of();}
    record BorderNeighbor(Site source,int chunkX,int chunkZ,UUID nation) {
        public BorderNeighbor {Objects.requireNonNull(source);Objects.requireNonNull(nation);if(Math.abs((long)chunkX-(source.x()>>4))+Math.abs((long)chunkZ-(source.z()>>4))!=1)throw new IllegalArgumentException("Border claim must be cardinally adjacent");}
    }
    String status();
}

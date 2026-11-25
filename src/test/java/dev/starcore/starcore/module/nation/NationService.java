package dev.starcore.starcore.module.nation;

import dev.starcore.starcore.foundation.territory.model.TerritoryClaim;
import dev.starcore.starcore.module.nation.model.Nation;
import dev.starcore.starcore.module.nation.model.NationId;
import java.util.Optional;
import java.util.UUID;

/** Test-only public signature fixture. */
public interface NationService {
    Optional<Nation> nationById(NationId nationId);
    Optional<Nation> nationOf(UUID playerId);
    Optional<TerritoryClaim> claimAt(String world, int x, int z);
}

package dev.starcore.starcore.module.city.model;

import dev.starcore.starcore.module.nation.model.NationId;
import java.util.UUID;

/** Test-only public signature fixture; StarCore City.canManageCity currently means mayor. */
public final class City {
    public UUID id() { throw new UnsupportedOperationException(); }
    public NationId nationId() { throw new UnsupportedOperationException(); }
    public String name() { throw new UnsupportedOperationException(); }
    public boolean isResident(UUID playerId) { throw new UnsupportedOperationException(); }
    public boolean canManageCity(UUID playerId) { throw new UnsupportedOperationException(); }
    public boolean hasClaim(String world, int x, int z) { throw new UnsupportedOperationException(); }
}

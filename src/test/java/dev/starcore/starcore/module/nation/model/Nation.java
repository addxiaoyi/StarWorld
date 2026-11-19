package dev.starcore.starcore.module.nation.model;

import java.util.UUID;

/** Test-only public signature fixture. */
public final class Nation {
    public NationId id() { throw new UnsupportedOperationException(); }
    public String name() { throw new UnsupportedOperationException(); }
    public boolean hasMember(UUID playerId) { throw new UnsupportedOperationException(); }
    public boolean isFounder(UUID playerId) { throw new UnsupportedOperationException(); }
}

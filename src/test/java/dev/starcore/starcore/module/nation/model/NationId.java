package dev.starcore.starcore.module.nation.model;

import java.util.UUID;

/** Test-only public signature fixture. */
public record NationId(UUID value) {
    public static NationId of(UUID value) { return new NationId(value); }
    @Override public String toString() { return value.toString(); }
}

package net.momirealms.craftengine.core.util;

/** Test-only public CE API shape, never a production dependency or CE implementation. */
public record Key(String asString) {
    public static Key of(String value) { return new Key(value); }
}

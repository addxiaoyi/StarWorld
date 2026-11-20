package dev.starcore.starcore.core.extension;

/** Test-only public-contract fixture, excluded from production JARs. */
public interface ExtensionContributor<T, R> {
    String getId(); String getName(); T contribute(R request);
    default int getPriority() { return 100; }
    default boolean isApplicable(R request) { return true; }
}

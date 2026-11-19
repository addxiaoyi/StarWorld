package dev.starcore.starcore.core.extension;

import java.util.*;

/** Audited registry registration/aggregation contract. No production plugin links this fixture. */
public final class ExtensionRegistry {
    private static final ExtensionRegistry INSTANCE = new ExtensionRegistry();
    private final Map<String, ExtensionPoint<?, ?>> points = new HashMap<>();
    private final Map<String, List<ExtensionContributor<?, ?>>> contributors = new HashMap<>();
    public static ExtensionRegistry getInstance() { return INSTANCE; }
    public <T, R> void registerExtensionPoint(ExtensionPoint<T, R> point) { points.put(point.getName(), point); contributors.put(point.getName(), new ArrayList<>()); }
    public <T, R> void registerContributor(String name, ExtensionContributor<T, R> contributor) { var list = contributors.computeIfAbsent(name, ignored -> new ArrayList<>()); list.removeIf(c -> c.getId().equals(contributor.getId())); list.add(contributor); }
    public <T, R> void unregisterContributor(ExtensionPoint<T, R> point, ExtensionContributor<T, R> contributor) { contributors.getOrDefault(point.getName(), new ArrayList<>()).remove(contributor); }
    public ExtensionPoint<?, ?> getExtensionPoint(String name) { return points.get(name); }
    public List<ExtensionContributor<?, ?>> getContributors(String name) { return contributors.getOrDefault(name, List.of()); }
    public void unregisterExtensionPoint(String name) { points.remove(name); contributors.remove(name); }
    public void clear() { points.clear(); contributors.clear(); }
}

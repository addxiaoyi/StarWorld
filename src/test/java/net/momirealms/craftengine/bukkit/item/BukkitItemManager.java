package net.momirealms.craftengine.bukkit.item;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.momirealms.craftengine.core.item.BuildableItem;
import net.momirealms.craftengine.core.util.Key;

/** Test double for the audited public CE registry; no server or upstream state. */
public final class BukkitItemManager {
    private static final BukkitItemManager INSTANCE = new BukkitItemManager();
    private final Map<Key, BuildableItem> definitions = new HashMap<>();
    public static BukkitItemManager instance() { return INSTANCE; }
    public Optional<BuildableItem> getBuildableItem(Key id) { return Optional.ofNullable(definitions.get(id)); }
    public void register(String id, BuildableItem definition) { definitions.put(Key.of(id), definition); }
    public void clear() { definitions.clear(); }
}

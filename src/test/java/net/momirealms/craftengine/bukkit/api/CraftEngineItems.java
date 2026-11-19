package net.momirealms.craftengine.bukkit.api;

import java.util.IdentityHashMap;
import java.util.Map;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

/** Test-only public CE item identity adapter. */
public final class CraftEngineItems {
    private static final Map<ItemStack, Key> IDENTITIES = new IdentityHashMap<>();
    public static boolean failIdentity;
    public static Key getCustomItemId(ItemStack stack) {
        if (failIdentity) throw new IllegalStateException("Fixture identity unavailable");
        return IDENTITIES.get(stack);
    }
    public static void identify(ItemStack stack, String id) { IDENTITIES.put(stack, Key.of(id)); }
    public static void clear() { IDENTITIES.clear(); failIdentity = false; }
}

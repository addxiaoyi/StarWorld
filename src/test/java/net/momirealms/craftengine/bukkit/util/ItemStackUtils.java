package net.momirealms.craftengine.bukkit.util;

import net.momirealms.craftengine.core.item.Item;
import org.bukkit.inventory.ItemStack;

/** Both real CE overload signatures; the raw native-stack overload must never receive a CE Item. */
public final class ItemStackUtils {
    public static int nativeOverloadCalls;
    public static int itemOverloadCalls;
    public static ItemStack getBukkitStack(Object nativeStack) {
        nativeOverloadCalls++;
        throw new AssertionError("CE wrapper sent to the native Minecraft stack overload");
    }
    public static ItemStack getBukkitStack(Item item) {
        itemOverloadCalls++;
        return item.stack();
    }
    public static void clear() { nativeOverloadCalls = 0; itemOverloadCalls = 0; }
}

package dev.starworld.integration;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.BuildableItem;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the actual reflection boundary against public-signature fixtures, not an Access mock. */
class CustomItemsReflectionTest {
    static final String ID = "kaleidoscopecookery:baozi";
    JavaPlugin owner;
    CustomItems.ReflectiveAccess access;
    CustomItems items;

    @BeforeEach void setup() throws Exception {
        BukkitItemManager.instance().clear(); CraftEngineItems.clear(); ItemStackUtils.clear();
        access = new CustomItems.ReflectiveAccess(getClass().getClassLoader());
        owner = mock(JavaPlugin.class); Server server = mock(Server.class); PluginManager plugins = mock(PluginManager.class);
        when(owner.getServer()).thenReturn(server); when(server.getPluginManager()).thenReturn(plugins);
        when(plugins.isPluginEnabled("CraftEngine")).thenReturn(true);
        items = new CustomItems(owner, access);
    }

    @Test void exactPublicItemConversionNeverCallsBroadNativeObjectOverload() throws Exception {
        ItemStack stack = mock(ItemStack.class);
        // A nonpublic definition proves invocation goes through the accessible public API interface.
        BukkitItemManager.instance().register(ID, new HiddenDefinition(() -> stack));
        assertTrue(access.exists(ID)); assertSame(stack, access.create(ID));
        assertEquals(1, ItemStackUtils.itemOverloadCalls); assertEquals(0, ItemStackUtils.nativeOverloadCalls);
    }

    @Test void absentNullAndFailingDefinitionsRemainUnavailableWithoutNativeFallback() throws Exception {
        assertFalse(access.exists(ID)); assertNull(items.create(ID, 1));
        BukkitItemManager.instance().register(ID, player -> null); assertNull(items.create(ID, 1));
        BukkitItemManager.instance().register(ID, player -> { throw new IllegalStateException("Fixture reload boundary"); });
        assertNull(items.create(ID, 1)); assertTrue(items.status().get("item:" + ID).contains("CE 创建失败"));
        assertEquals(0, ItemStackUtils.nativeOverloadCalls); assertEquals(0, ItemStackUtils.itemOverloadCalls);
    }

    @Test void realReflectionPreservesExactIdentityAndReturnsIndependentWarehouseStacks() throws Exception {
        ItemStack template = mock(ItemStack.class), copy = mock(ItemStack.class);
        when(template.clone()).thenReturn(copy);
        BukkitItemManager.instance().register(ID, new HiddenDefinition(() -> template));
        CraftEngineItems.identify(template, ID); CraftEngineItems.identify(copy, ID);
        assertEquals(ID, access.id(template)); assertSame(copy, items.create(ID, 3)); verify(copy).setAmount(3);
        verify(template, never()).setAmount(anyInt()); assertTrue(items.matches(copy, ID));
        assertFalse(items.matches(copy, "kaleidoscopecookery:oil"));
        CraftEngineItems.failIdentity = true; assertFalse(items.matches(copy, ID));
        assertEquals(0, ItemStackUtils.nativeOverloadCalls);
    }

    private static final class HiddenDefinition implements BuildableItem {
        private final Item item;
        HiddenDefinition(Item item) { this.item = item; }
        @Override public Item buildItem(Player player) { assertNull(player); return item; }
    }
}

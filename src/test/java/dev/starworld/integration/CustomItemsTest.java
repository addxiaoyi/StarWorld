package dev.starworld.integration;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomItemsTest {
    JavaPlugin owner;
    PluginManager plugins;
    CustomItems.Access access;
    CustomItems items;

    @BeforeEach void setup() {
        owner = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        plugins = mock(PluginManager.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(Logger.getLogger("CustomItemsTest"));
        when(server.getPluginManager()).thenReturn(plugins);
        access = mock(CustomItems.Access.class);
        items = new CustomItems(owner, access);
    }

    @Test void missingCraftEngineDegradesCustomDefinitionsWithoutHidingInvalidVanilla() {
        assertDoesNotThrow(() -> items.validate(List.of("kaleidoscopecookery:baozi", "kaleidoscope_tavern:barrel/wine")));
        assertNull(items.create("kaleidoscopecookery:baozi", 1));
        assertFalse(items.matches(mock(ItemStack.class), "kaleidoscopecookery:baozi"));
        assertEquals(2, items.status().size());
        assertThrows(IllegalArgumentException.class, () -> items.validate(List.of("minecraft:imaginary_sword")));
        assertThrows(IllegalArgumentException.class, () -> items.validate(List.of("missing_namespace")));
        assertThrows(IllegalArgumentException.class, () -> items.create("minecraft:wheat", 0));
    }

    @Test void pristineVanillaInputRejectsAllSpecialMetadataAndCustomIdentity() throws Exception {
        try (var materials = mockStatic(Material.class)) {
        Material wheat = mock(Material.class);
        when(wheat.isItem()).thenReturn(true);
        materials.when(() -> Material.matchMaterial("minecraft:wheat")).thenReturn(wheat);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(wheat);
        assertTrue(items.matches(stack, "minecraft:wheat"));
        when(stack.hasItemMeta()).thenReturn(true);
        assertFalse(items.matches(stack, "minecraft:wheat"), "Named/enchanted/PDC/damaged stacks must survive deposits");
        when(stack.hasItemMeta()).thenReturn(false);
        when(plugins.isPluginEnabled("CraftEngine")).thenReturn(true);
        when(access.id(stack)).thenReturn("custom:valuable_wheat");
        assertFalse(items.matches(stack, "minecraft:wheat"));
        when(access.id(stack)).thenReturn(null);
        assertTrue(items.matches(stack, "minecraft:wheat"));
        when(access.id(stack)).thenThrow(new InvocationTargetException(new IllegalStateException("CE reloading")));
        assertFalse(items.matches(stack, "minecraft:wheat"), "Identity failures must never consume items");
        }
    }

    @Test void incompatibleCraftEngineCannotConsumeUnidentifiedItems() {
        try (var materials = mockStatic(Material.class)) {
        Material wheat = mock(Material.class);
        when(wheat.isItem()).thenReturn(true);
        materials.when(() -> Material.matchMaterial("minecraft:wheat")).thenReturn(wheat);
        when(plugins.isPluginEnabled("CraftEngine")).thenReturn(true);
        CustomItems incompatible = new CustomItems(owner, null);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(wheat);
        assertFalse(incompatible.matches(stack, "minecraft:wheat"));
        }
    }

    @Test void customMatchingUsesExactIdentityAndReturnedStackIsCloned() throws Exception {
        when(plugins.isPluginEnabled("CraftEngine")).thenReturn(true);
        ItemStack input = mock(ItemStack.class);
        when(access.id(input)).thenReturn("kaleidoscopecookery:baozi");
        assertTrue(items.matches(input, "kaleidoscopecookery:baozi"));
        assertFalse(items.matches(input, "kaleidoscopecookery:oil"));
        ItemStack template = mock(ItemStack.class);
        ItemStack copy = mock(ItemStack.class);
        when(template.clone()).thenReturn(copy);
        when(access.create("kaleidoscopecookery:baozi")).thenReturn(template);
        assertSame(copy, items.create("kaleidoscopecookery:baozi", 3));
        verify(copy).setAmount(3);
        verify(template, never()).setAmount(anyInt());
    }

    @Test void unavailableContentAndApiFailuresReturnNullWithImmutableDiagnostics() throws Exception {
        when(plugins.isPluginEnabled("CraftEngine")).thenReturn(true);
        when(access.exists("custom:missing")).thenReturn(false);
        items.validate(List.of("custom:missing"));
        assertTrue(items.status().get("item:custom:missing").contains("不可用"));
        assertThrows(UnsupportedOperationException.class, () -> items.status().put("test", "test"));
        when(access.create("custom:missing")).thenThrow(new InvocationTargetException(new IllegalArgumentException()));
        assertNull(items.create("custom:missing", 1));
        when(access.exists("custom:missing")).thenReturn(true);
        items.validate(List.of("custom:missing"));
        assertFalse(items.status().containsKey("item:custom:missing"));
    }

    @Test void absentOptionalModulesHaveExplicitDegradationAndNoInventedBonuses() {
        CustomItems vanillaOnly = new CustomItems(owner);
        assertTrue(vanillaOnly.status().get("CraftEngine").contains("原版生产链可用"));
        assertTrue(vanillaOnly.status().get("KaleidoscopeCookeryPlugin").contains("停用"));
        assertTrue(vanillaOnly.status().get("KaleidoscopeTavernPlugin").contains("停用"));
        assertTrue(vanillaOnly.status().get("KaleidoscopeDecoPlugin").contains("不可用"));
        assertTrue(vanillaOnly.status().get("KaleidoscopeMusicPlugin").contains("不可用"));
        when(plugins.isPluginEnabled("KaleidoscopeDecoPlugin")).thenReturn(true);
        when(plugins.isPluginEnabled("KaleidoscopeMusicPlugin")).thenReturn(true);
        CustomItems installed = new CustomItems(owner);
        assertTrue(installed.status().get("KaleidoscopeDecoPlugin").contains("无公开"));
        assertTrue(installed.status().get("KaleidoscopeMusicPlugin").contains("无公开"));
    }
}

package dev.starworld.logistics;

import dev.starworld.integration.CoreExtensionPort.NationSnapshot;
import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.CoreBridge;
import dev.starworld.integration.CoreExtensionPort;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.runtime.WorldRuntime;
import java.time.Instant;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LogisticsControllerTest {
    private NationSnapshot nation(UUID nation, UUID peer, String relation, boolean war, boolean capability) {
        return new NationSnapshot(nation, "国家", Instant.EPOCH, "clear", Map.of(), null, Set.of(), Map.of(), "", Map.of(), Map.of(), 1,
                war ? Set.of(peer) : Set.of(), relation == null ? Map.of() : Map.of(peer, relation), List.of(), 0,
                capability ? Map.of("war", "available", "diplomacy", "available") : Map.of("war", "unavailable"));
    }
    @Test void diplomacyMustBeKnownPeacefulOnBothSidesAndNoActiveWar() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(LogisticsController.permitsTrade(nation(a, b, "NEUTRAL", false, true), nation(b, a, "ALLIED", false, true)));
        for (String relation : new String[]{"HOSTILE", "WAR", "UNKNOWN"}) assertFalse(LogisticsController.permitsTrade(nation(a, b, relation, false, true), nation(b, a, "NEUTRAL", false, true)));
        assertFalse(LogisticsController.permitsTrade(nation(a, b, "NEUTRAL", true, true), nation(b, a, "NEUTRAL", false, true)));
        assertFalse(LogisticsController.permitsTrade(nation(a, b, "NEUTRAL", false, true), nation(b, a, "NEUTRAL", true, true)));
        assertFalse(LogisticsController.permitsTrade(nation(a, b, "NEUTRAL", false, true), nation(b, a, "NEUTRAL", false, false)));
        assertFalse(LogisticsController.permitsTrade(nation(a, b, null, false, true), nation(b, a, "NEUTRAL", false, true)));
        assertFalse(LogisticsController.permitsTrade(null, nation(b, a, "NEUTRAL", false, true)));
    }
    @Test void barterSyntaxPreservesNamespacesAndRejectsAmbiguousOrNegativeCargo() {
        assertEquals(Map.of("minecraft:bread", 16L, "cookery:rice", 8L), LogisticsController.parseCargo("minecraft:bread=16,cookery:rice=8"));
        for (String argument : new String[]{"minecraft:bread:16", "minecraft:bread=16,", "minecraft:bread=1,minecraft:bread=2", "minecraft:bread=-1", "bread=1", "minecraft:bread=1.5"})
            assertThrows(IllegalArgumentException.class, () -> LogisticsController.parseCargo(argument), argument);
    }
    @Test void authorityCaptureChecksMainThreadNationClaimsLoadedChunksAndPhysicalAnchor() {
        JavaPlugin plugin = mock(JavaPlugin.class); Server server = mock(Server.class); World world = mock(World.class); Block block = mock(Block.class);
        CoreBridge core = mock(CoreBridge.class); CoreExtensionPort extensions = mock(CoreExtensionPort.class); WorldRuntime runtime = mock(WorldRuntime.class); LogisticsService service = mock(LogisticsService.class); Player player = mock(Player.class);
        when(plugin.getServer()).thenReturn(server); when(server.isPrimaryThread()).thenReturn(true); when(service.definitions()).thenReturn(LogisticsDefinitions.defaults());
        UUID cityId = UUID.randomUUID(), nationId = UUID.randomUUID(), worldId = UUID.randomUUID(), actor = UUID.randomUUID();
        CoreCity city = new CoreCity(cityId, nationId, "城", "国", 1, "fixture"); Site site = new Site(worldId, "world", 0, 64, 0);
        Node node = new Node(UUID.randomUUID(), cityId, nationId, NodeType.ROAD, site, "plains", 1, true);
        when(core.city(cityId)).thenReturn(Optional.of(city)); when(core.ownsSite(cityId, site)).thenReturn(true); when(runtime.currentPeriod()).thenReturn(42L);
        when(player.isOnline()).thenReturn(true); when(runtime.manage(player, cityId)).thenReturn(true);
        when(server.getWorld(worldId)).thenReturn(world); when(world.getName()).thenReturn("world"); when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getBlockAt(0, 64, 0)).thenReturn(block); when(block.getType()).thenReturn(Material.LODESTONE);
        LogisticsController controller = new LogisticsController(plugin, runtime, core, extensions, service);
        var valid = controller.capture(actor, List.of(node), List.of(), Set.of(), player).authority();
        assertEquals(Set.of(node.id()), valid.validNodes()); assertEquals(Set.of(cityId), valid.managedCities()); assertEquals(42, valid.period());
        when(world.isChunkLoaded(0, 0)).thenReturn(false);
        assertTrue(controller.capture(actor, List.of(node), List.of(), Set.of(), player).authority().validNodes().isEmpty());
        verify(world, times(1)).getBlockAt(anyInt(), anyInt(), anyInt()); verify(world, never()).getChunkAt(anyInt(), anyInt());
        when(server.isPrimaryThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> controller.capture(actor, List.of(node), List.of(), Set.of(), player));
        verifyNoInteractions(extensions);
    }
}

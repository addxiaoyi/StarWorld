package dev.starworld.life;

import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.*;
import dev.starworld.life.*;
import dev.starworld.life.LifeState.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import static org.mockito.Mockito.*;

/** Test-only platform boundary: proofs are created by LifePlatform after real identity/ownership/sound checks. */
public final class CivicEvidenceFixture {
    private CivicEvidenceFixture() {}
    private static final Map<UUID,Furniture> FURNITURE=new ConcurrentHashMap<>();
    public static Furniture lookup(Entity meta) { return FURNITURE.get(meta.getUniqueId()); }
    public record Key(String asString) {}
    public record Furniture(UUID uuid,Key id) { public boolean isValid(){return true;} }
    public static DecorationEvidence furniture(CoreCity city,UUID actor,UUID worldId,UUID furniture,int x,LifeDefinitions definitions) throws Exception {
        JavaPlugin plugin=mock(JavaPlugin.class);Server server=mock(Server.class);PluginManager plugins=mock(PluginManager.class);CoreBridge core=mock(CoreBridge.class);CustomItems items=mock(CustomItems.class);
        when(plugin.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(plugins);when(server.isPrimaryThread()).thenReturn(true);when(plugins.isPluginEnabled("CraftEngine")).thenReturn(true);
        World world=mock(World.class);Player player=mock(Player.class);Entity meta=mock(Entity.class);Location location=new Location(world,x+.5,64,.5);UUID metaId=UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);when(world.getName()).thenReturn("world");when(player.getUniqueId()).thenReturn(actor);when(player.isOnline()).thenReturn(true);when(player.getWorld()).thenReturn(world);when(player.getLocation()).thenReturn(location);
        when(meta.getUniqueId()).thenReturn(metaId);when(meta.isValid()).thenReturn(true);when(meta.getWorld()).thenReturn(world);when(meta.getLocation()).thenReturn(location);when(core.playerCity(actor)).thenReturn(Optional.of(city));when(core.ownsSite(eq(city.cityId()),any())).thenReturn(true);
        FURNITURE.put(metaId,new Furniture(furniture,new Key("kaleidoscope_deco:chair/oak")));
        try {
            LifePlatform platform=new LifePlatform(plugin,core,items,definitions,CivicEvidenceFixture.class.getMethod("lookup",Entity.class));
            return platform.inspectDecoration(player,city.cityId(),meta).orElseThrow();
        } finally { FURNITURE.remove(metaId); }
    }
    public static NoteReceipt note(CoreCity city,UUID performerId,UUID worldId,ConcertView session,int pitch,LifeDefinitions definitions) {
        JavaPlugin plugin=mock(JavaPlugin.class);Server server=mock(Server.class);PluginManager plugins=mock(PluginManager.class);CoreBridge core=mock(CoreBridge.class);CustomItems items=mock(CustomItems.class);
        when(plugin.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(plugins);when(server.isPrimaryThread()).thenReturn(true);
        World world=mock(World.class);Player performer=mock(Player.class),listener=mock(Player.class);PlayerInventory inventory=mock(PlayerInventory.class);Location location=new Location(world,.5,64,.5);UUID audience=UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);when(world.getName()).thenReturn("world");when(performer.getUniqueId()).thenReturn(performerId);when(performer.isOnline()).thenReturn(true);when(performer.getWorld()).thenReturn(world);when(performer.getLocation()).thenReturn(location);when(performer.getInventory()).thenReturn(inventory);
        when(listener.isOnline()).thenReturn(true);when(listener.getUniqueId()).thenReturn(audience);when(core.playerCity(performerId)).thenReturn(Optional.of(city));when(core.playerCity(audience)).thenReturn(Optional.of(city));when(core.ownsSite(eq(city.cityId()),any())).thenReturn(true);
        when(items.matches(any(),eq("minecraft:note_block"))).thenReturn(true);when(world.getNearbyPlayers(any(Location.class),eq(8.0))).thenReturn(List.of(listener));
        LifePlatform platform=new LifePlatform(plugin,core,items,definitions);NoteReceipt receipt=platform.playNote(performer,session,pitch,session.notes()+1);
        verify(world).playSound(eq(location),eq("minecraft:block.note_block.harp"),eq(SoundCategory.RECORDS),eq(1f),anyFloat());
        return receipt;
    }
}

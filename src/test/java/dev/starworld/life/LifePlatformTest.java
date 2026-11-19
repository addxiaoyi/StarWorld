package dev.starworld.life;

import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.*;
import dev.starworld.life.LifeState.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LifePlatformTest {
    JavaPlugin plugin=mock(JavaPlugin.class);Server server=mock(Server.class);PluginManager plugins=mock(PluginManager.class);
    CoreBridge core=mock(CoreBridge.class);CustomItems items=mock(CustomItems.class);
    LifePlatform platform() {
        when(plugin.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(plugins);when(server.isPrimaryThread()).thenReturn(true);
        return new LifePlatform(plugin,core,items,LifeDefinitions.defaults());
    }
    @Test void missingFurnitureApiFailsClosedAndDoesNotPretendMusicCompletionExists() {
        LifePlatform platform=platform();Player player=mock(Player.class);Entity entity=mock(Entity.class);
        assertTrue(platform.inspectDecoration(player,UUID.randomUUID(),entity).isEmpty());assertTrue(platform.status().get("MusicPlus").contains("无公开完成 API"));
        verifyNoInteractions(core);verify(entity,never()).getLocation();
    }
    @Test void worldOperationsRejectWorkerThreadBeforeReadingPlayerOrWorld() {
        LifePlatform platform=platform();when(server.isPrimaryThread()).thenReturn(false);Player player=mock(Player.class);
        assertThrows(IllegalStateException.class,()->platform.inspectDecoration(player,UUID.randomUUID(),null));verifyNoInteractions(player);
        assertThrows(IllegalStateException.class,()->platform.inspectTargetDecoration(player,UUID.randomUUID()));verifyNoInteractions(player);
    }
    @Test void missingTargetingApiFailsClosedWithoutUsingBukkitEntityRaycast()throws Exception {
        platform();when(plugins.isPluginEnabled("CraftEngine")).thenReturn(true);
        LifePlatform platform=new LifePlatform(plugin,core,items,LifeDefinitions.defaults(),FurnitureFixture.class.getMethod("lookup",Entity.class));Player player=mock(Player.class);
        assertTrue(platform.inspectTargetDecoration(player,UUID.randomUUID()).isEmpty());verifyNoInteractions(player,core);
    }
    @Test void publicFurnitureRayTraceResolvesExactMetaAndRetainsDistanceOwnershipAndIdentityChecks()throws Exception {
        platform();when(plugins.isPluginEnabled("CraftEngine")).thenReturn(true);
        LifePlatform platform=new LifePlatform(plugin,core,items,LifeDefinitions.defaults(),FurnitureFixture.class.getMethod("lookup",Entity.class),FurnitureFixture.class.getMethod("rayTrace",Player.class,double.class));
        UUID city=UUID.randomUUID(),nation=UUID.randomUUID(),actor=UUID.randomUUID(),furniture=UUID.randomUUID();
        Player player=mock(Player.class);Entity meta=mock(Entity.class);World world=mock(World.class);Location location=new Location(world,2.5,64,1.5);
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(player.isOnline()).thenReturn(true);when(player.getUniqueId()).thenReturn(actor);when(player.getWorld()).thenReturn(world);when(player.getLocation()).thenReturn(location);
        when(meta.isValid()).thenReturn(true);when(meta.getUniqueId()).thenReturn(UUID.randomUUID());when(meta.getWorld()).thenReturn(world);when(meta.getLocation()).thenReturn(location);
        CoreCity current=new CoreCity(city,nation,"城市","国家",1,"fixture");when(core.playerCity(actor)).thenReturn(Optional.of(current));when(core.ownsSite(eq(city),any())).thenReturn(true);
        FurnitureFixture.current=FurnitureFixture.traced=new TestFurniture(furniture,"kaleidoscope_deco:chair/oak",meta);
        DecorationEvidence evidence=platform.inspectTargetDecoration(player,city).orElseThrow();assertEquals(furniture,evidence.furnitureId());assertEquals(meta.getUniqueId(),evidence.metaEntityId());assertEquals(6d,FurnitureFixture.maximumDistance);
        verify(player,never()).getTargetEntity(anyInt());
        FurnitureFixture.current=new TestFurniture(UUID.randomUUID(),"kaleidoscope_deco:chair/oak",meta);assertTrue(platform.inspectTargetDecoration(player,city).isEmpty(),"A stale ray hit must not borrow another furniture identity");
        FurnitureFixture.current=FurnitureFixture.traced;when(player.getLocation()).thenReturn(location.clone().add(7,0,0));assertTrue(platform.inspectTargetDecoration(player,city).isEmpty());
        when(player.getLocation()).thenReturn(location);when(core.ownsSite(eq(city),any())).thenReturn(false);assertTrue(platform.inspectTargetDecoration(player,city).isEmpty());
        when(core.ownsSite(eq(city),any())).thenReturn(true);FurnitureFixture.current=FurnitureFixture.traced=new TestFurniture(furniture,"untrusted:chair",meta);assertTrue(platform.inspectTargetDecoration(player,city).isEmpty());
        FurnitureFixture.current=FurnitureFixture.traced=new TestFurniture(furniture,"kaleidoscope_deco:chair/oak",meta);when(core.playerCity(actor)).thenReturn(Optional.empty());assertTrue(platform.inspectTargetDecoration(player,city).isEmpty());
        FurnitureFixture.traced=null;assertTrue(platform.inspectTargetDecoration(player,city).isEmpty());
    }
    @Test void actualSoundRequiresInstrumentCurrentTerritoryAndLiveSameCityAudience() {
        LifePlatform platform=platform();UUID city=UUID.randomUUID(),nation=UUID.randomUUID(),performerId=UUID.randomUUID(),worldId=UUID.randomUUID();
        Player performer=mock(Player.class),listener=mock(Player.class);World world=mock(World.class);PlayerInventory inventory=mock(PlayerInventory.class);
        when(world.getUID()).thenReturn(worldId);when(world.getName()).thenReturn("world");Location location=new Location(world,.5,64,.5);
        when(performer.isOnline()).thenReturn(true);when(performer.getUniqueId()).thenReturn(performerId);when(performer.getLocation()).thenReturn(location);when(performer.getWorld()).thenReturn(world);when(performer.getInventory()).thenReturn(inventory);
        when(listener.isOnline()).thenReturn(true);when(listener.getUniqueId()).thenReturn(UUID.randomUUID());
        CoreCity current=new CoreCity(city,nation,"城市","国家",1,"fixture");when(core.playerCity(performerId)).thenReturn(Optional.of(current));when(core.playerCity(listener.getUniqueId())).thenReturn(Optional.of(current));when(core.ownsSite(eq(city),any())).thenReturn(true);
        when(items.matches(any(),anyString())).thenReturn(true);when(world.getNearbyPlayers(any(Location.class),eq(8.0))).thenReturn(List.of(listener));
        long now=System.currentTimeMillis();ConcertView concert=new ConcertView(UUID.randomUUID(),city,nation,performerId,new Site(worldId,"world",0,64,0),10,now-2000,now-1000,0,0,0,"active");
        NoteReceipt note=platform.playNote(performer,concert,12,1);assertEquals(Set.of(listener.getUniqueId()),note.audience());assertEquals(concert.id(),note.session());
        verify(world).playSound(eq(location),eq("minecraft:block.note_block.harp"),eq(SoundCategory.RECORDS),eq(1f),eq(1f));
        when(core.ownsSite(eq(city),any())).thenReturn(false);assertThrows(IllegalArgumentException.class,()->platform.playNote(performer,concert,12,1));
        verify(world,times(1)).playSound(any(Location.class),anyString(),any(SoundCategory.class),anyFloat(),anyFloat());
    }
    @Test void publicFurnitureEvidenceRequiresExactCurrentIdentityLocationLoadedChunkAndMembership()throws Exception {
        platform();when(plugins.isPluginEnabled("CraftEngine")).thenReturn(true);
        LifePlatform platform=new LifePlatform(plugin,core,items,LifeDefinitions.defaults(),FurnitureFixture.class.getMethod("lookup",Entity.class));
        UUID city=UUID.randomUUID(),nation=UUID.randomUUID(),worldId=UUID.randomUUID(),actor=UUID.randomUUID(),meta=UUID.randomUUID(),furniture=UUID.randomUUID();
        Player player=mock(Player.class);Entity entity=mock(Entity.class);World world=mock(World.class);Location location=new Location(world,2.5,64,1.5);
        when(world.getUID()).thenReturn(worldId);when(world.getName()).thenReturn("world");when(world.isChunkLoaded(0,0)).thenReturn(true);
        when(player.isOnline()).thenReturn(true);when(player.getUniqueId()).thenReturn(actor);when(player.getWorld()).thenReturn(world);when(player.getLocation()).thenReturn(location);
        when(entity.isValid()).thenReturn(true);when(entity.getUniqueId()).thenReturn(meta);when(entity.getWorld()).thenReturn(world);when(entity.getLocation()).thenReturn(location);when(server.getEntity(meta)).thenReturn(entity);when(server.getWorld(worldId)).thenReturn(world);
        CoreCity current=new CoreCity(city,nation,"城市","国家",1,"fixture");when(core.playerCity(actor)).thenReturn(Optional.of(current));when(core.city(city)).thenReturn(Optional.of(current));when(core.ownsSite(eq(city),any())).thenReturn(true);
        FurnitureFixture.current=new TestFurniture(furniture,"kaleidoscope_deco:chair/oak");
        DecorationEvidence evidence=platform.inspectDecoration(player,city,entity).orElseThrow();
        Decoration decoration=new Decoration(evidence.furnitureId(),evidence.metaEntityId(),city,nation,actor,evidence.itemId(),evidence.site(),10);
        assertTrue(platform.validateDecoration(decoration));
        when(entity.getLocation()).thenReturn(new Location(world,3.5,64,1.5));assertFalse(platform.validateDecoration(decoration));
        when(entity.getLocation()).thenReturn(location);FurnitureFixture.current=new TestFurniture(UUID.randomUUID(),"kaleidoscope_deco:chair/oak");assertFalse(platform.validateDecoration(decoration));
        FurnitureFixture.current=new TestFurniture(furniture,"kaleidoscope_deco:chair/oak");when(world.isChunkLoaded(0,0)).thenReturn(false);assertFalse(platform.validateDecoration(decoration));
        when(world.isChunkLoaded(0,0)).thenReturn(true);when(core.playerCity(actor)).thenReturn(Optional.empty());assertTrue(platform.inspectDecoration(player,city,entity).isEmpty());
        when(core.playerCity(actor)).thenReturn(Optional.of(current));when(core.ownsSite(eq(city),any())).thenReturn(false);assertTrue(platform.inspectDecoration(player,city,entity).isEmpty());assertFalse(platform.validateDecoration(decoration));
    }
    public static class FurnitureFixture {
        static TestFurniture current,traced;static double maximumDistance;
        public static TestFurniture lookup(Entity entity){return current;}
        public static TestFurniture rayTrace(Player player,double distance){maximumDistance=distance;return traced;}
    }
    public record TestFurniture(UUID uuid,String item,Entity meta) {
        public TestFurniture(UUID uuid,String item){this(uuid,item,null);}
        public boolean isValid(){return true;}public TestKey id(){return new TestKey(item);}public Entity getBukkitEntity(){return meta;}
    }
    public record TestKey(String asString) {}
}

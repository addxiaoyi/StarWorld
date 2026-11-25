package dev.starworld.ui;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.evolution.*;
import dev.starworld.runtime.WorldRuntime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GeographyMenuTest {
    final class Rig implements AutoCloseable {
        final WorldRuntime runtime=mock(WorldRuntime.class);final Player player=mock(Player.class);final World world=mock(World.class);
        final UUID worldId=UUID.randomUUID(),viewer=UUID.randomUUID();final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"city","nation",1,"test");
        final JavaPlugin plugin=mock(JavaPlugin.class);final BukkitScheduler scheduler=mock(BukkitScheduler.class);final List<Runnable> tasks=new ArrayList<>();
        final MockedStatic<Bukkit> bukkit;final MockedStatic<JavaPlugin> plugins;final MockedConstruction<ItemStack> items;final GeographyMenu menu;InventoryView current;
        @SuppressWarnings("unchecked") Rig(){
            when(player.isOnline()).thenReturn(true);when(player.getUniqueId()).thenReturn(viewer);when(player.hasPermission("starworld.use")).thenReturn(true);
            when(world.getUID()).thenReturn(worldId);when(player.getLocation()).thenReturn(new Location(world,0,64,0));when(runtime.playerCity(player)).thenReturn(Optional.of(city));
            when(runtime.regionMap(eq(city.cityId()),any(),anyInt(),anyInt())).thenAnswer(call->CompletableFuture.completedFuture(Optional.of(RegionalMap.view(city.cityId(),city.nationId(),true,7,call.getArgument(1),call.getArgument(2),call.getArgument(3),List.of()))));
            when(runtime.districts(city.cityId())).thenReturn(CompletableFuture.completedFuture(Optional.of(new RegionalMap.DistrictReport(city.cityId(),city.nationId(),true,7,List.of()))));
            doAnswer(call->{((CompletableFuture<Object>)call.getArgument(1)).thenAccept((Consumer<Object>)call.getArgument(2));return null;}).when(runtime).reply(any(),any(),any());
            bukkit=mockStatic(Bukkit.class);plugins=mockStatic(JavaPlugin.class);items=mockConstruction(ItemStack.class,(item,context)->when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)));
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(call->{Inventory inv=mock(Inventory.class);when(inv.getHolder()).thenReturn(call.getArgument(0));return inv;});
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);plugins.when(()->JavaPlugin.getProvidingPlugin(GeographyMenu.class)).thenReturn(plugin);
            doAnswer(call->{tasks.add(call.getArgument(1));return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
            when(player.getOpenInventory()).thenAnswer(call->current);when(player.openInventory(any(Inventory.class))).thenAnswer(call->{current=mock(InventoryView.class);when(current.getTopInventory()).thenReturn(call.getArgument(0));return current;});menu=new GeographyMenu(runtime);
        }
        void click(int slot,ClickType type,Player who){InventoryClickEvent event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(current);when(event.getWhoClicked()).thenReturn(who);when(event.getClick()).thenReturn(type);when(event.getRawSlot()).thenReturn(slot);menu.click(event);verify(event).setCancelled(true);}
        public void close(){items.close();plugins.close();bukkit.close();}
    }
    @Test void navigationUsesBoundedCoordinatesAndNeverRequestsChunkLoads(){
        try(Rig r=new Rig()){
            r.menu.open(r.player);verify(r.runtime).regionMap(r.city.cityId(),r.worldId,0,0);
            r.click(46,ClickType.LEFT,r.player);assertEquals(1,r.tasks.size());r.tasks.removeFirst().run();verify(r.runtime).regionMap(r.city.cityId(),r.worldId,0,-4);
            r.click(51,ClickType.RIGHT,r.player);r.tasks.removeFirst().run();verify(r.runtime).regionMap(r.city.cityId(),r.worldId,8,-4);
            r.menu.openAt(r.player,r.worldId,RegionalMap.MAX_CHUNK,0);r.click(51,ClickType.LEFT,r.player);r.tasks.removeFirst().run();
            verify(r.runtime,times(2)).regionMap(r.city.cityId(),r.worldId,RegionalMap.MAX_CHUNK,0);verify(r.world,never()).getChunkAt(anyInt(),anyInt());verify(r.runtime,never()).open(any());
        }
    }
    @Test void displayInventoryBlocksShiftNumberDoubleDragAndForeignViewer(){
        try(Rig r=new Rig()){
            r.menu.open(r.player);for(ClickType type:List.of(ClickType.SHIFT_LEFT,ClickType.NUMBER_KEY,ClickType.DOUBLE_CLICK,ClickType.DROP))r.click(46,type,r.player);
            for(int slot:new int[]{-999,-1,54,90})r.click(slot,ClickType.LEFT,r.player);
            Player foreign=mock(Player.class);when(foreign.getUniqueId()).thenReturn(UUID.randomUUID());r.click(46,ClickType.LEFT,foreign);
            InventoryDragEvent event=mock(InventoryDragEvent.class);when(event.getView()).thenReturn(r.current);r.menu.drag(event);verify(event).setCancelled(true);
            assertTrue(r.tasks.isEmpty());verify(r.runtime,times(1)).regionMap(any(),any(),anyInt(),anyInt());
        }
    }
    @Test void deferredNavigationRejectsTransferAndRevokedPermission(){
        try(Rig r=new Rig()){
            r.menu.open(r.player);r.click(46,ClickType.LEFT,r.player);
            when(r.runtime.playerCity(r.player)).thenReturn(Optional.of(new CoreCity(r.city.cityId(),UUID.randomUUID(),"city","new nation",1,"now")));
            r.tasks.removeFirst().run();verify(r.player).closeInventory();verify(r.runtime,times(1)).regionMap(any(),any(),anyInt(),anyInt());
            when(r.runtime.playerCity(r.player)).thenReturn(Optional.of(r.city));r.click(46,ClickType.LEFT,r.player);when(r.player.hasPermission("starworld.use")).thenReturn(false);r.tasks.removeFirst().run();
            verify(r.player,times(2)).closeInventory();verify(r.runtime,times(1)).regionMap(any(),any(),anyInt(),anyInt());
        }
    }
    @Test void delayedReadCannotOpenAFormerNationsMapOrDistrictList(){
        try(Rig r=new Rig()){
            CompletableFuture<Optional<RegionalMap.View>> pending=new CompletableFuture<>();when(r.runtime.regionMap(any(),any(),anyInt(),anyInt())).thenReturn(pending);r.menu.open(r.player);
            when(r.runtime.playerCity(r.player)).thenReturn(Optional.empty());pending.complete(Optional.of(RegionalMap.view(r.city.cityId(),r.city.nationId(),true,1,r.worldId,0,0,List.of())));
            verify(r.player,never()).openInventory(any(Inventory.class));
            when(r.runtime.playerCity(r.player)).thenReturn(Optional.of(r.city));when(r.runtime.districts(r.city.cityId())).thenReturn(CompletableFuture.completedFuture(Optional.of(new RegionalMap.DistrictReport(r.city.cityId(),UUID.randomUUID(),true,1,List.of()))));
            r.menu.districts(r.player,0);verify(r.player,never()).openInventory(any(Inventory.class));verify(r.player).sendMessage(contains("尚无可用地区组"));
        }
    }
    @Test void publicCommandsAndCompletionExposeMapAndDistricts(){
        WorldRuntime runtime=mock(WorldRuntime.class);GeographyMenu menu=mock(GeographyMenu.class);WorldCommand command=new WorldCommand(runtime,mock(CityMenu.class),mock(GovernanceMenu.class),menu);
        Player player=mock(Player.class);when(player.hasPermission("starworld.use")).thenReturn(true);
        command.onCommand(player,null,"sw",new String[]{"map"});command.onCommand(player,null,"sw",new String[]{"districts"});
        verify(menu).open(player);verify(menu).districts(player,0);assertTrue(command.onTabComplete(player,null,"sw",new String[]{""}).containsAll(List.of("map","districts")));
    }
}

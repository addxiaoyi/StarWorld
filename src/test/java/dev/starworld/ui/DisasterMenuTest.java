package dev.starworld.ui;

import dev.starworld.disaster.*;
import dev.starworld.disaster.DisasterState.*;
import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.*;
import dev.starworld.runtime.*;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DisasterMenuTest {
    private final class Fixture implements AutoCloseable {
        final WorldRuntime runtime=mock(WorldRuntime.class);final ExpansionRuntime expansion=mock(ExpansionRuntime.class);final DisasterResponseService service=mock(DisasterResponseService.class);final Definitions definitions=mock(Definitions.class);final Player player=mock(Player.class);final JavaPlugin plugin=mock(JavaPlugin.class);final BukkitScheduler scheduler=mock(BukkitScheduler.class);final UUID viewer=UUID.randomUUID();
        final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"河谷城","国家",1,"fixture");final EventState event=new EventState("drought",10,13,15);final Site site=new Site(UUID.randomUUID(),"world",0,64,0);final Building farm=new Building(UUID.randomUUID(),city.cityId(),city.nationId(),"farm",site,1),well=new Building(UUID.randomUUID(),city.cityId(),city.nationId(),"well",new Site(site.worldId(),"world",2,64,0),1);
        final CityView view=new CityView(city.cityId(),city.nationId(),city.cityName(),"agrarian",9,Map.of("minecraft:bread",100L,"minecraft:oak_planks",100L),List.of(farm,well),event,"");
        final Strategy strategy=new Strategy("drought_irrigation","drought",Set.of("farm","well"),Map.of("minecraft:bread",12L,"minecraft:oak_planks",8L),Map.of("fertility",5L),500,8,160,12,1,0,null,0,0);
        final Map<Inventory,Map<Integer,ItemStack>> contents=new IdentityHashMap<>();final Map<ItemStack,ItemMeta> metas=new IdentityHashMap<>();final Map<Inventory,Component> titles=new IdentityHashMap<>();final List<Runnable> tasks=new ArrayList<>();final MockedStatic<Bukkit> bukkit;final MockedStatic<JavaPlugin> plugins;final MockedConstruction<ItemStack> stacks;final CityMenu menu;InventoryView current;
        @SuppressWarnings("unchecked") Fixture(){
            when(player.getUniqueId()).thenReturn(viewer);when(player.isOnline()).thenReturn(true);when(player.hasPermission("starworld.use")).thenReturn(true);when(runtime.playerCity(player)).thenReturn(Optional.of(city));when(runtime.open(city)).thenReturn(CompletableFuture.completedFuture(view));when(runtime.currentPeriod()).thenReturn(10L);when(runtime.manage(player,city.cityId())).thenReturn(true);when(runtime.verifiedBuildings(view)).thenReturn(Set.of(farm.id(),well.id()));when(runtime.expansion()).thenReturn(Optional.of(expansion));when(runtime.definitions()).thenReturn(definitions);when(definitions.buildings()).thenReturn(Map.of("farm",new Definitions.BuildingType("farm","农场","COMPOSTER",Map.of()),"well",new Definitions.BuildingType("well","水井","CAULDRON",Map.of())));when(expansion.disasters()).thenReturn(service);when(service.configuration()).thenReturn(configuration(64));when(runtime.disasterResponses(eq(city.cityId()),anyInt(),anyInt())).thenReturn(CompletableFuture.completedFuture(List.of()));when(runtime.disasterHistory(eq(city.cityId()),anyInt(),anyInt())).thenReturn(CompletableFuture.completedFuture(List.of()));
            doAnswer(call->{((CompletableFuture<?>)call.getArgument(1)).thenAccept(value->((Consumer<Object>)call.getArgument(2)).accept(value));return null;}).when(runtime).reply(any(),any(),any());
            bukkit=mockStatic(Bukkit.class);plugins=mockStatic(JavaPlugin.class);stacks=mockConstruction(ItemStack.class,(stack,context)->{ItemMeta meta=mock(ItemMeta.class);when(stack.getItemMeta()).thenReturn(meta);metas.put(stack,meta);});
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(Component.class))).thenAnswer(call->{Inventory inventory=mock(Inventory.class);when(inventory.getHolder()).thenReturn(call.getArgument(0));contents.put(inventory,new HashMap<>());titles.put(inventory,call.getArgument(2));doAnswer(set->{contents.get(inventory).put(set.getArgument(0),set.getArgument(1));return null;}).when(inventory).setItem(anyInt(),any(ItemStack.class));return inventory;});
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);plugins.when(()->JavaPlugin.getProvidingPlugin(CityMenu.class)).thenReturn(plugin);doAnswer(call->{tasks.add(call.getArgument(1,Runnable.class));return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
            when(player.getOpenInventory()).thenAnswer(call->current);when(player.openInventory(any(Inventory.class))).thenAnswer(call->{InventoryView opened=mock(InventoryView.class);when(opened.getTopInventory()).thenReturn(call.getArgument(0));current=opened;return opened;});menu=new CityMenu(runtime,"test");
        }
        DisasterResponseDefinitions configuration(int maxPage){return new DisasterResponseDefinitions(definitions,new DisasterState.Definitions(1,4,maxPage,64,true,Map.of(strategy.id(),strategy)));}
        Inventory inventory(){return current.getTopInventory();}
        ItemMeta meta(int slot){return metas.get(contents.get(inventory()).get(slot));}
        void click(int slot){InventoryClickEvent click=mock(InventoryClickEvent.class);when(click.getView()).thenReturn(current);when(click.getWhoClicked()).thenReturn(player);when(click.getRawSlot()).thenReturn(slot);when(click.getClick()).thenReturn(ClickType.LEFT);menu.click(click);verify(click).setCancelled(true);}
        void runTasks(){List<Runnable> ready=List.copyOf(tasks);tasks.clear();ready.forEach(Runnable::run);}
        Response response(){return new Response(UUID.randomUUID(),city.cityId(),city.nationId(),strategy.id(),"drought",10,13,15,10,11,23,-1,0,8,160,500,true,1,0,Set.of("farm","well"),strategy.costs(),strategy.recoveryPerStep(),null);}
        public void close(){stacks.close();plugins.close();bukkit.close();}
    }

    @Test void strategyListOnlyPreviewsAndDisplaysRealCostBeforeConfirmation(){try(Fixture f=new Fixture()){f.menu.openDisaster(f.player);verify(f.meta(0)).displayName(Component.text("灌溉工程"));f.click(0);verify(f.expansion,never()).respondToDisaster(any(),any(),anyString(),any(),any(),any());f.runTasks();assertEquals(Component.text("StarWorld · 灾害治理 · 确认响应"),f.titles.get(f.inventory()));verify(f.meta(21)).lore(argThat(lines->lines.contains(Component.text("minecraft:bread ×12；仓储：100"))&&lines.contains(Component.text("minecraft:oak_planks ×8；仓储：100"))));verify(f.meta(31)).displayName(Component.text("确认支付并救灾"));verify(f.expansion,never()).respondToDisaster(any(),any(),anyString(),any(),any(),any());}}

    @Test void repeatedConfirmClicksUseTheSameRequestAndTheDisplayedOriginalEvent(){try(Fixture f=new Fixture()){f.menu.openDisasterPreview(f.player,f.strategy.id());f.click(31);f.click(31);verify(f.expansion,never()).respondToDisaster(any(),any(),anyString(),any(),any(),any());f.runTasks();ArgumentCaptor<UUID> requests=ArgumentCaptor.forClass(UUID.class);verify(f.expansion,times(2)).respondToDisaster(eq(f.player),eq(f.city),eq(f.strategy.id()),requests.capture(),eq(f.event),any());assertNotNull(requests.getAllValues().getFirst());assertEquals(requests.getAllValues().getFirst(),requests.getAllValues().getLast());}}

    @Test void losingManagementOrARequiredAnchorDisablesThePreviewSubmitAction(){try(Fixture f=new Fixture()){when(f.runtime.manage(f.player,f.city.cityId())).thenReturn(false);f.menu.openDisasterPreview(f.player,f.strategy.id());verify(f.meta(31)).displayName(Component.text("当前无法提交"));f.click(31);f.runTasks();verify(f.expansion,never()).respondToDisaster(any(),any(),anyString(),any(),any(),any());when(f.runtime.manage(f.player,f.city.cityId())).thenReturn(true);when(f.runtime.verifiedBuildings(f.view)).thenReturn(Set.of(f.farm.id()));f.menu.openDisasterPreview(f.player,f.strategy.id());verify(f.meta(23)).lore(argThat(lines->lines.stream().anyMatch(line->line.equals(Component.text("缺少有效设施：水井（well）")))));verify(f.meta(31)).displayName(Component.text("当前无法提交"));}}

    @Test void insufficientStockAndAnUnmatchedEventCannotSubmitFromPreview(){try(Fixture f=new Fixture()){CityView empty=new CityView(f.city.cityId(),f.city.nationId(),"城","agrarian",9,Map.of(),f.view.buildings(),f.event,"");when(f.runtime.open(f.city)).thenReturn(CompletableFuture.completedFuture(empty));when(f.runtime.verifiedBuildings(empty)).thenReturn(Set.of(f.farm.id(),f.well.id()));f.menu.openDisasterPreview(f.player,f.strategy.id());verify(f.meta(31)).lore(argThat(lines->lines.contains(Component.text("城市仓储不足，请先存入救灾物资"))));CityView peaceful=new CityView(f.city.cityId(),f.city.nationId(),"城","agrarian",9,f.view.stock(),f.view.buildings(),null,"");when(f.runtime.open(f.city)).thenReturn(CompletableFuture.completedFuture(peaceful));when(f.runtime.verifiedBuildings(peaceful)).thenReturn(Set.of(f.farm.id(),f.well.id()));f.menu.openDisasterPreview(f.player,f.strategy.id());verify(f.meta(31)).lore(argThat(lines->lines.contains(Component.text("当前没有匹配且有效的灾害事件"))));f.click(31);f.runTasks();verify(f.expansion,never()).respondToDisaster(any(),any(),anyString(),any(),any(),any());}}

    @Test void nationChangeWhileConfirmClickIsQueuedClosesTheStaleMenu(){try(Fixture f=new Fixture()){f.menu.openDisasterPreview(f.player,f.strategy.id());f.click(31);CoreCity conquered=new CoreCity(f.city.cityId(),UUID.randomUUID(),"城","新国",1,"changed");when(f.runtime.playerCity(f.player)).thenReturn(Optional.of(conquered));f.runTasks();verify(f.player).closeInventory();verify(f.expansion,never()).respondToDisaster(any(),any(),anyString(),any(),any(),any());}}

    @Test void statusAndHistoryUseBoundedRealQueriesAndKeepConfiguredPageSize(){try(Fixture f=new Fixture()){when(f.service.configuration()).thenReturn(f.configuration(1));Response response=f.response();when(f.runtime.disasterResponses(f.city.cityId(),1,0)).thenReturn(CompletableFuture.completedFuture(List.of(response)));f.menu.openDisasterStatus(f.player,0);verify(f.meta(0)).lore(argThat(lines->lines.contains(Component.text("请求："+response.requestId()))&&lines.contains(Component.text("恢复步骤：0 / 8"))));f.click(52);f.runTasks();verify(f.runtime).disasterResponses(f.city.cityId(),1,1);when(f.runtime.disasterHistory(f.city.cityId(),1,0)).thenReturn(CompletableFuture.completedFuture(List.of(new History(response.requestId(),11,"recovery","fertility=5"))));f.menu.openDisasterHistory(f.player,0);verify(f.meta(0)).displayName(Component.text("恢复步骤已提交"));f.click(52);f.runTasks();verify(f.runtime).disasterHistory(f.city.cityId(),1,1);}}

    @Test void staleAsyncSnapshotDoesNotOpenAFormerNationsGovernanceView(){try(Fixture f=new Fixture()){when(f.runtime.playerCity(f.player)).thenReturn(Optional.of(f.city),Optional.of(new CoreCity(f.city.cityId(),UUID.randomUUID(),"城","新国",1,"changed")));f.menu.openDisaster(f.player);verify(f.player,never()).openInventory(any(Inventory.class));}}
    @Test void switchingInventoryWhileTheReadIsPendingCannotReopenAnAbandonedGovernanceView(){try(Fixture f=new Fixture()){CompletableFuture<CityView> pending=new CompletableFuture<>();when(f.runtime.open(f.city)).thenReturn(pending);f.menu.openDisaster(f.player);f.current=mock(InventoryView.class);when(f.current.getTopInventory()).thenReturn(mock(Inventory.class));pending.complete(f.view);verify(f.player,never()).openInventory(any(Inventory.class));}}

    @Test void disasterMenuCommandOpensTheGuiAndAppearsInCompletion(){WorldRuntime runtime=mock(WorldRuntime.class);CityMenu menu=mock(CityMenu.class);Player player=mock(Player.class);when(player.hasPermission("starworld.use")).thenReturn(true);WorldCommand command=new WorldCommand(runtime,menu);assertTrue(command.onCommand(player,mock(org.bukkit.command.Command.class),"sw",new String[]{"disaster-menu"}));verify(menu).openDisaster(player);assertTrue(command.onTabComplete(player,mock(org.bukkit.command.Command.class),"sw",new String[]{"disaster-"}).contains("disaster-menu"));}
}

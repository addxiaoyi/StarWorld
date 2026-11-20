package dev.starworld.ui;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.runtime.WorldRuntime;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CityMenuTest {
    private final WorldRuntime runtime = mock(WorldRuntime.class);
    private final CityMenu menu = new CityMenu(runtime, "Test");
    private final UUID viewer = UUID.randomUUID();
    private final UUID city = UUID.randomUUID();

    private InventoryView menuView() throws Exception {
        Class<?> holderClass = Class.forName("dev.starworld.ui.CityMenu$Holder");
        Constructor<?> constructor = holderClass.getDeclaredConstructor(UUID.class, UUID.class);
        constructor.setAccessible(true);
        InventoryHolder holder = (InventoryHolder) constructor.newInstance(viewer, city);
        Inventory top = mock(Inventory.class);
        when(top.getHolder()).thenReturn(holder);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        return view;
    }

    private Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(runtime.playerCity(player)).thenReturn(Optional.of(new CoreCity(city, UUID.randomUUID(), "City", "Nation", 1, "test")));
        return player;
    }

    private InventoryClickEvent click(InventoryView view, Player player, int raw, ClickType type) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(raw);
        when(event.getClick()).thenReturn(type);
        when(event.isShiftClick()).thenReturn(type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT);
        when(event.getAction()).thenReturn(type.isShiftClick() ? InventoryAction.MOVE_TO_OTHER_INVENTORY : InventoryAction.PICKUP_ALL);
        when(player.getOpenInventory()).thenReturn(view);
        return event;
    }

    @Test void bottomInventoryOutsideClicksAndDifferentViewerAreCancelledWithoutActions() throws Exception {
        InventoryView view = menuView();
        Player owner = player(viewer);
        for (int raw : new int[]{-999, -1, 54, 64, 89}) {
            InventoryClickEvent event = click(view, owner, raw, ClickType.LEFT);
            menu.click(event);
            verify(event).setCancelled(true);
        }
        InventoryClickEvent intruder = click(view, player(UUID.randomUUID()), 10, ClickType.LEFT);
        menu.click(intruder);
        verify(intruder).setCancelled(true);
        verify(runtime, never()).register(any(), anyString());
        verify(runtime, never()).open(any());
    }

    @Test void shiftNumberKeyAndDoubleClicksCannotDispatchTopInventoryActions() throws Exception {
        InventoryView view = menuView();
        Player owner = player(viewer);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<JavaPlugin> plugins = mockStatic(JavaPlugin.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            JavaPlugin plugin = mock(JavaPlugin.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            plugins.when(() -> JavaPlugin.getProvidingPlugin(CityMenu.class)).thenReturn(plugin);
            doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                    .when(scheduler).runTask(eq(plugin), any(Runnable.class));
            for (ClickType type : List.of(ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.DROP)) {
                InventoryClickEvent event = click(view, owner, 10, type);
                menu.click(event);
                verify(event).setCancelled(true);
            }
            verify(runtime, never()).register(any(), anyString());
        }
    }

    @Test void onlyOwnerSpecificTopSlotDispatchesAndWaitsForNextTick() throws Exception {
        InventoryView view = menuView();
        Player owner = player(viewer);
        List<Runnable> tasks = new ArrayList<>();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<JavaPlugin> plugins = mockStatic(JavaPlugin.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            JavaPlugin plugin = mock(JavaPlugin.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            plugins.when(() -> JavaPlugin.getProvidingPlugin(CityMenu.class)).thenReturn(plugin);
            doAnswer(call -> { tasks.add(call.getArgument(1, Runnable.class)); return null; })
                    .when(scheduler).runTask(eq(plugin), any(Runnable.class));
            InventoryClickEvent farm = click(view, owner, 10, ClickType.LEFT);
            InventoryClickEvent nonAction = click(view, owner, 20, ClickType.LEFT);
            menu.click(farm);
            menu.click(nonAction);
            verify(farm).setCancelled(true);
            verify(nonAction).setCancelled(true);
            verify(runtime, never()).register(any(), anyString());
            tasks.forEach(Runnable::run);
            verify(runtime).register(owner, "farm");
            verify(runtime, times(1)).register(any(), anyString());
            verify(owner).closeInventory();
        }
    }

    @Test void cityMembershipChangeClosesMenuOnNextTickWithoutDispatchingActions() throws Exception {
        InventoryView view = menuView();
        Player owner = player(viewer);
        List<Runnable> tasks = new ArrayList<>();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<JavaPlugin> plugins = mockStatic(JavaPlugin.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            JavaPlugin plugin = mock(JavaPlugin.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            plugins.when(() -> JavaPlugin.getProvidingPlugin(CityMenu.class)).thenReturn(plugin);
            doAnswer(call -> { tasks.add(call.getArgument(1, Runnable.class)); return null; })
                    .when(scheduler).runTask(eq(plugin), any(Runnable.class));
            InventoryClickEvent event = click(view, owner, 10, ClickType.LEFT);
            menu.click(event);
            verify(event).setCancelled(true);
            verify(owner, never()).closeInventory();
            when(runtime.playerCity(owner)).thenReturn(Optional.empty());
            tasks.forEach(Runnable::run);
            verify(owner).closeInventory();
            verify(runtime, never()).register(any(), anyString());
        }
    }

    @Test void switchingInventoryBeforeQueuedActionRunsCannotAffectTheNewView() throws Exception {
        InventoryView view = menuView();
        Player owner = player(viewer);
        List<Runnable> tasks = new ArrayList<>();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<JavaPlugin> plugins = mockStatic(JavaPlugin.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            JavaPlugin plugin = mock(JavaPlugin.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            plugins.when(() -> JavaPlugin.getProvidingPlugin(CityMenu.class)).thenReturn(plugin);
            doAnswer(call -> { tasks.add(call.getArgument(1, Runnable.class)); return null; })
                    .when(scheduler).runTask(eq(plugin), any(Runnable.class));
            menu.click(click(view, owner, 10, ClickType.LEFT));
            Inventory otherTop = mock(Inventory.class);
            InventoryView otherView = mock(InventoryView.class);
            when(otherView.getTopInventory()).thenReturn(otherTop);
            when(owner.getOpenInventory()).thenReturn(otherView);
            tasks.forEach(Runnable::run);
            verify(owner, never()).closeInventory();
            verify(runtime, never()).register(any(), anyString());
        }
    }

    @Test void everyDragInCityViewIsCancelledIncludingBottomOnlyDrags() throws Exception {
        InventoryView view = menuView();
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getRawSlots()).thenReturn(java.util.Set.of(54, 55));
        menu.drag(event);
        verify(event).setCancelled(true);
        verifyNoInteractions(runtime);
    }

    @Test void ordinaryInventoriesAreUnaffected() {
        Inventory top = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getView()).thenReturn(view);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        menu.click(click);
        menu.drag(drag);
        verify(click, never()).setCancelled(anyBoolean());
        verify(drag, never()).setCancelled(anyBoolean());
    }
    @Test void newGovernanceEntryUsesThePublicCommandWithoutChangingExistingMenuActions()throws Exception{
        InventoryView view=menuView();Player owner=player(viewer);when(runtime.expansion()).thenReturn(Optional.of(mock(dev.starworld.runtime.ExpansionRuntime.class)));
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class);MockedStatic<JavaPlugin> plugins=mockStatic(JavaPlugin.class)){BukkitScheduler scheduler=mock(BukkitScheduler.class);JavaPlugin plugin=mock(JavaPlugin.class);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);plugins.when(()->JavaPlugin.getProvidingPlugin(CityMenu.class)).thenReturn(plugin);doAnswer(call->{call.getArgument(1,Runnable.class).run();return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));menu.click(click(view,owner,47,ClickType.LEFT));verify(owner).performCommand("sw governance");verify(runtime,never()).register(any(),anyString());}
    }
}

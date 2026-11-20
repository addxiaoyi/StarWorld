package dev.starworld.runtime;

import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.domain.WorldState.Transfer;
import dev.starworld.integration.CoreBridge;
import dev.starworld.integration.CustomItems;
import dev.starworld.persistence.WorldStore;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transaction journal; player callbacks are explicitly released by each test. */
class WorldRuntimeTest {
    private static final String WHEAT = "minecraft:wheat";
    @TempDir Path temp;

    @Test void newPublicQueriesForwardOnlyToReadMethodsWithoutStartingOrCompletingWork()throws Exception{
        var works=mock(dev.starworld.works.PublicWorksService.class);var civic=mock(dev.starworld.civic.CivicNeedsService.class);UUID request=UUID.randomUUID();
        try(MockedConstruction<ExpansionRuntime> expansions=mockConstruction(ExpansionRuntime.class,(value,context)->{when(value.works()).thenReturn(works);when(value.civic()).thenReturn(civic);} );Fixture f=new Fixture()){
            when(works.projects(f.city.cityId(),1,2)).thenReturn(List.of());when(works.history(f.city.cityId(),1,3)).thenReturn(List.of());when(civic.requests(f.city.cityId(),1,4)).thenReturn(List.of());when(civic.history(f.city.cityId(),1,5)).thenReturn(List.of());when(civic.receipt(request)).thenReturn(Optional.empty());
            f.runtime.installExpansion(temp.toFile());assertTrue(f.runtime.publicWorks(f.city.cityId(),1,2).get(5,TimeUnit.SECONDS).isEmpty());assertTrue(f.runtime.publicWorksHistory(f.city.cityId(),1,3).get(5,TimeUnit.SECONDS).isEmpty());assertTrue(f.runtime.civicNeeds(f.city.cityId(),1,4).get(5,TimeUnit.SECONDS).isEmpty());assertTrue(f.runtime.civicHistory(f.city.cityId(),1,5).get(5,TimeUnit.SECONDS).isEmpty());assertTrue(f.runtime.civicReceipt(request).get(5,TimeUnit.SECONDS).isEmpty());
            verify(works).projects(f.city.cityId(),1,2);verify(works).history(f.city.cityId(),1,3);verify(civic).requests(f.city.cityId(),1,4);verify(civic).history(f.city.cityId(),1,5);verify(civic).receipt(request);verify(works,never()).start(any(),any(),anyString(),anyLong(),anySet(),anyString());verify(works,never()).cycle(any(),any(),anyLong(),anySet());verify(civic,never()).request(any(),any(),anyString(),anyLong(),anyString());verify(civic,never()).complete(any(),any(),anyLong(),anyString());
        }
    }

    private static Definitions definitions() {
        return new Definitions(60, 16, 16, 10000, 0,
                Map.of("plains", new Definitions.Terrain("plains", "Plains", 1, 1, 1, Map.of(), Map.of())),
                Map.of("agrarian", new Definitions.Civilization("agrarian", "Agrarian", Map.of())),
                Map.of("farm", new Definitions.BuildingType("farm", "Farm", "COMPOSTER", Map.of())),
                List.of(new Definitions.Recipe("grain", "farm", Map.of(), Map.of(WHEAT, 1L), null, 0)), Map.of());
    }

    private final class Fixture implements AutoCloseable {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final CoreBridge core = mock(CoreBridge.class);
        final CustomItems items = mock(CustomItems.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final UUID playerId = UUID.randomUUID();
        final CoreCity city = new CoreCity(UUID.randomUUID(), UUID.randomUUID(), "City", "Nation", 1, "fixture");
        final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        final WorldStore store;
        final MockedConstruction<SchedulerGate> gates;
        final WorldRuntime runtime;
        final ItemStack sample = mock(ItemStack.class);

        Fixture() throws Exception {
            when(plugin.getLogger()).thenReturn(Logger.getLogger("WorldRuntimeTest"));
            when(player.getUniqueId()).thenReturn(playerId);
            when(player.getInventory()).thenReturn(inventory);
            when(core.playerCity(playerId)).thenReturn(Optional.of(city));
            when(core.canManage(playerId, city.cityId())).thenReturn(true);
            when(items.create(WHEAT, 1)).thenReturn(sample);
            when(sample.getMaxStackSize()).thenReturn(64);
            store = new WorldStore(temp.resolve(UUID.randomUUID() + ".db"), definitions().stockLimit());
            store.ensureCity(city);
            store.stock(city.cityId(), WHEAT, 20);
            gates = mockConstruction(SchedulerGate.class, (gate, context) ->
                    doAnswer(call -> { callbacks.add(call.getArgument(1, Runnable.class)); return null; })
                            .when(gate).player(any(Player.class), any(Runnable.class), any(Runnable.class)));
            runtime = new WorldRuntime(plugin, definitions(), core, items, store);
        }

        Transfer prepare(boolean deposit, int amount) throws Exception {
            runtime.transfer(player, WHEAT, amount, deposit);
            // Receiving this callback proves prepare committed and activeTransferIds was populated.
            Runnable callback = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(callback, "Prepared transfer must schedule the player callback");
            assertEquals(1, store.pending().size());
            callbacks.add(callback);
            return store.pending().getFirst();
        }

        void releasePlayerCallback() throws Exception {
            Runnable callback = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(callback);
            callback.run();
            runtime.async(() -> true).get(5, TimeUnit.SECONDS); // storage worker barrier
        }

        long stock() throws Exception { return store.stock(city.cityId()).getOrDefault(WHEAT, 0L); }
        @Override public void close() throws Exception { try { runtime.close(); } finally { gates.close(); } }
    }

    @Test void administratorCannotResolveALivePreparedTransferBeforePlayerCallback() throws Exception {
        try (Fixture f = new Fixture()) {
            Transfer transfer = f.prepare(false, 5);
            assertEquals(15, f.stock(), "Withdrawal reserves stock before scheduling inventory access");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> f.runtime.resolve(transfer.id(), true, "admin").get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("PREPARED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(15, f.stock(), "Manual reconciliation must not refund a live withdrawal");
            verify(f.player, never()).getInventory();
            when(f.core.canManage(f.playerId, f.city.cityId())).thenReturn(false);
            f.releasePlayerCallback();
            assertEquals("CANCELLED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(20, f.stock());
        }
    }

    @Test void permissionRevocationCancelsPreparedWithdrawalAndRefundsExactlyOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            Transfer transfer = f.prepare(false, 7);
            when(f.core.canManage(f.playerId, f.city.cityId())).thenReturn(false);
            f.releasePlayerCallback();
            assertEquals("CANCELLED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(20, f.stock());
            assertTrue(f.store.pending().isEmpty());
            verifyNoInteractions(f.inventory);
            verify(f.player, never()).saveData();
            assertThrows(ExecutionException.class,
                    () -> f.runtime.resolve(transfer.id(), true, "admin").get(5, TimeUnit.SECONDS));
            assertEquals(20, f.stock(), "A cancelled withdrawal cannot be credited again");
        }
    }

    @Test void nationChangeWhileCallbackIsQueuedCancelsWithdrawalWithoutGivingItems() throws Exception {
        try (Fixture f = new Fixture()) {
            Transfer transfer = f.prepare(false, 6);
            CoreCity captured = new CoreCity(f.city.cityId(), UUID.randomUUID(), "City", "Conqueror", 1, "changed");
            when(f.core.playerCity(f.playerId)).thenReturn(Optional.of(captured));
            f.releasePlayerCallback();
            assertEquals("CANCELLED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(20, f.stock());
            verifyNoInteractions(f.inventory);
        }
    }

    @Test void cityChangeWhileCallbackIsQueuedCancelsWithdrawalWithoutGivingItems() throws Exception {
        try (Fixture f = new Fixture()) {
            Transfer transfer = f.prepare(false, 6);
            CoreCity changed = new CoreCity(UUID.randomUUID(), f.city.nationId(), "Other City", "Nation", 1, "changed");
            when(f.core.playerCity(f.playerId)).thenReturn(Optional.of(changed));
            f.releasePlayerCallback();
            assertEquals("CANCELLED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(20, f.stock());
            verifyNoInteractions(f.inventory);
        }
    }

    @Test void insufficientDepositLeavesEveryInventorySlotAndWarehouseUntouched() throws Exception {
        try (Fixture f = new Fixture()) {
            ItemStack twoWheat = mock(ItemStack.class);
            when(twoWheat.getAmount()).thenReturn(2);
            when(f.items.matches(twoWheat, WHEAT)).thenReturn(true);
            when(f.inventory.getStorageContents()).thenReturn(new ItemStack[]{twoWheat, null});
            Transfer transfer = f.prepare(true, 3);
            f.releasePlayerCallback();
            assertEquals("CANCELLED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(20, f.stock());
            verify(f.inventory, never()).setStorageContents(any(ItemStack[].class));
            verify(f.inventory, never()).addItem(any(ItemStack[].class));
            verify(f.player, never()).saveData();
            verify(twoWheat, never()).setAmount(anyInt());
        }
    }

    @Test void fullBackpackRefundsReservedWithdrawalAndNeverDropsOrAddsItems() throws Exception {
        try (Fixture f = new Fixture()) {
            ItemStack occupied = mock(ItemStack.class);
            Material solid = mock(Material.class);
            when(occupied.getType()).thenReturn(solid);
            when(solid.isAir()).thenReturn(false);
            when(occupied.isSimilar(f.sample)).thenReturn(false);
            when(f.inventory.getStorageContents()).thenReturn(new ItemStack[]{occupied});
            Transfer transfer = f.prepare(false, 4);
            f.releasePlayerCallback();
            assertEquals("CANCELLED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(20, f.stock());
            verify(f.inventory, never()).addItem(any(ItemStack[].class));
            verify(f.inventory, never()).setStorageContents(any(ItemStack[].class));
            verify(f.player, never()).saveData();
            verify(f.player, never()).getWorld();
        }
    }

    @Test void invalidInputIsRejectedBeforeAnyTransferOrInventoryMutation() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalArgumentException.class, () -> f.runtime.transfer(f.player, WHEAT, 0, true));
            assertThrows(IllegalArgumentException.class, () -> f.runtime.transfer(f.player, WHEAT, 2305, false));
            assertThrows(IllegalArgumentException.class, () -> f.runtime.transfer(f.player, "minecraft:diamond", 1, true));
            assertTrue(f.store.pending().isEmpty());
            assertEquals(20, f.stock());
            assertTrue(f.callbacks.isEmpty());
            verifyNoInteractions(f.inventory);
        }
    }

    @Test void successfulDepositRemovesExactInventoryQuantityBeforeCreditingWarehouse() throws Exception {
        try (Fixture f = new Fixture()) {
            ItemStack first = mutableStack(3), second = mutableStack(4);
            AtomicReference<ItemStack[]> saved = inventoryContents(f, new ItemStack[]{first, second, null});
            when(f.items.matches(any(ItemStack.class), eq(WHEAT))).thenReturn(true);
            Transfer transfer = f.prepare(true, 5);
            f.releasePlayerCallback();
            assertEquals("COMPLETED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(25, f.stock());
            assertEquals(2, Arrays.stream(saved.get()).filter(java.util.Objects::nonNull).mapToInt(ItemStack::getAmount).sum());
            assertEquals(3, first.getAmount(), "Removing items must not mutate an aliased source stack");
            assertEquals(4, second.getAmount());
            verify(f.player).saveData();
            assertThrows(ExecutionException.class,
                    () -> f.runtime.resolve(transfer.id(), true, "admin").get(5, TimeUnit.SECONDS));
            assertEquals(25, f.stock());
        }
    }

    @Test void changingIdentityCheckCannotCreditItemsThatWereNotRemoved() throws Exception {
        try (Fixture f = new Fixture()) {
            ItemStack wheat = mutableStack(4);
            AtomicReference<ItemStack[]> saved = inventoryContents(f, new ItemStack[]{wheat});
            // A CE reload/error between preflight and removal must never turn into free warehouse stock.
            when(f.items.matches(wheat, WHEAT)).thenReturn(true, false);
            Transfer transfer = f.prepare(true, 4);
            f.releasePlayerCallback();
            int remaining = Arrays.stream(saved.get()).filter(java.util.Objects::nonNull).mapToInt(ItemStack::getAmount).sum();
            assertEquals(20 + (4 - remaining), f.stock(), "Warehouse credit must equal the exact number of removed items");
            assertEquals("COMPLETED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(0, remaining);
            verify(f.items, times(1)).matches(wheat, WHEAT);
        }
    }

    @Test void successfulWithdrawalSplitsStacksSavesPlayerAndCannotBeResolvedAgain() throws Exception {
        try (Fixture f = new Fixture()) {
            f.store.stock(f.city.cityId(), WHEAT, 100);
            inventoryContents(f, new ItemStack[]{null, null});
            when(f.sample.clone()).thenAnswer(call -> mutableStack(1));
            AtomicReference<ItemStack[]> delivered = new AtomicReference<>();
            when(f.inventory.addItem(any(ItemStack[].class))).thenAnswer(call -> {
                delivered.set((ItemStack[]) call.getRawArguments()[0]);
                return new HashMap<Integer, ItemStack>();
            });
            Transfer transfer = f.prepare(false, 80);
            f.releasePlayerCallback();
            assertEquals("COMPLETED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(20, f.stock());
            assertNotNull(delivered.get());
            assertEquals(List.of(64, 16), Arrays.stream(delivered.get()).map(ItemStack::getAmount).toList());
            verify(f.player).saveData();
            assertThrows(ExecutionException.class,
                    () -> f.runtime.resolve(transfer.id(), true, "admin").get(5, TimeUnit.SECONDS));
            assertEquals(20, f.stock());
        }
    }

    @Test void playerSaveFailureKeepsAmbiguousWithdrawalQuarantinedUntilExplicitDecision() throws Exception {
        try (Fixture f = new Fixture()) {
            inventoryContents(f, new ItemStack[]{null});
            when(f.sample.clone()).thenAnswer(call -> mutableStack(1));
            when(f.inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
            doThrow(new IllegalStateException("simulated player save failure")).when(f.player).saveData();
            Transfer transfer = f.prepare(false, 4);
            f.releasePlayerCallback();
            assertEquals("PREPARED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(16, f.stock(), "An ambiguous entity delivery must not automatically restore warehouse stock");
            assertEquals(1, f.store.pending().size());
            // The administrator has inspected the player's saved data and acknowledges delivery without credit.
            assertTrue(f.runtime.resolve(transfer.id(), false, "admin").get(5, TimeUnit.SECONDS));
            assertEquals("RESOLVED", f.store.transfer(transfer.id()).orElseThrow().state());
            assertEquals(16, f.stock());
            assertThrows(ExecutionException.class,
                    () -> f.runtime.resolve(transfer.id(), true, "admin").get(5, TimeUnit.SECONDS));
            verify(f.inventory, times(1)).addItem(any(ItemStack[].class));
        }
    }

    private AtomicReference<ItemStack[]> inventoryContents(Fixture f, ItemStack[] initial) {
        AtomicReference<ItemStack[]> saved = new AtomicReference<>(initial.clone());
        when(f.inventory.getStorageContents()).thenAnswer(call -> saved.get().clone());
        doAnswer(call -> { saved.set(call.getArgument(0, ItemStack[].class).clone()); return null; })
                .when(f.inventory).setStorageContents(any(ItemStack[].class));
        return saved;
    }

    private static ItemStack mutableStack(int initialAmount) {
        ItemStack stack = mock(ItemStack.class);
        AtomicInteger amount = new AtomicInteger(initialAmount);
        when(stack.getAmount()).thenAnswer(call -> amount.get());
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(stack).setAmount(anyInt());
        when(stack.clone()).thenAnswer(call -> mutableStack(amount.get()));
        return stack;
    }
}

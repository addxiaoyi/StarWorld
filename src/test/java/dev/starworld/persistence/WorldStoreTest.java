package dev.starworld.persistence;

import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorldStoreTest {
    @TempDir Path temp;
    private CoreCity city() { return new CoreCity(UUID.randomUUID(), UUID.randomUUID(), "城市", "国家", 1, "core"); }
    private Definitions definitions() {
        return new Definitions(300, 12, 20, 1000, 0,
                Map.of("plains", new Definitions.Terrain("plains", "平原", 1, 1, 1, Map.of("ore", 100L), Map.of())),
                Map.of("agrarian", new Definitions.Civilization("agrarian", "农耕", Map.of())),
                Map.of("workshop", new Definitions.BuildingType("workshop", "工坊", "BLAST_FURNACE", Map.of())), List.of(), Map.of());
    }

    @Test void actualDatabaseMigratesAndRecoversCitiesEventsFacilitiesAndStock() throws Exception {
        Path db = temp.resolve("restore.db"); CoreCity city = city();
        Building b = new Building(UUID.randomUUID(), city.cityId(), city.nationId(), "workshop", new Site(UUID.randomUUID(), "world", -17, 70, -1), 1);
        EventState event = new EventState("drought", 10, 13, 15);
        try (WorldStore store = new WorldStore(db, 1000)) {
            assertEquals(2, store.schemaVersion());
            store.ensureCity(city); store.addBuilding(b, "plains", definitions());
            store.stock(city.cityId(), "minecraft:bread", 27); store.event(city.cityId(), event);
            store.finish(city.cityId(), 10, "持久化报告");
        }
        try (WorldStore store = new WorldStore(db, 1000)) {
            CityView view = store.view(city.cityId()).orElseThrow();
            assertEquals(27, view.stock().get("minecraft:bread")); assertEquals(event, view.event());
            assertEquals(List.of(b), view.buildings()); assertEquals(10, view.lastPeriod());
            assertEquals("持久化报告", view.lastReport()); assertTrue(store.settled(city.cityId(), 10));
            assertEquals(100, store.region(b.site().regionKey()).reserves().get("ore"));
        }
    }

    @Test void exceptionRollsBackWholeNestedTransactionAndSurvivesRestart() throws Exception {
        Path db = temp.resolve("rollback.db"); CoreCity city = city();
        try (WorldStore store = new WorldStore(db, 1000)) {
            store.ensureCity(city); store.stock(city.cityId(), "minecraft:bread", 10);
            assertThrows(SQLException.class, () -> store.tx(s -> {
                s.stock(city.cityId(), "minecraft:bread", 50);
                s.tx(inner -> { inner.stock(city.cityId(), "minecraft:iron_ingot", 30); return null; });
                throw new IllegalStateException("Simulated interruption before commit");
            }));
        }
        try (WorldStore store = new WorldStore(db, 1000)) {
            assertEquals(Map.of("minecraft:bread", 10L), store.stock(city.cityId()));
        }
    }

    @Test void linkageOrAssertionFailureCannotCommitHalfFinishedTransaction() throws Exception {
        try (WorldStore store = new WorldStore(temp.resolve("error-rollback.db"), 1000)) {
            CoreCity city = city(); store.ensureCity(city); store.stock(city.cityId(), "minecraft:bread", 10);
            assertThrows(AssertionError.class, () -> store.tx(s -> {
                s.stock(city.cityId(), "minecraft:bread", 999);
                throw new AssertionError("Simulated extension linkage/assertion failure");
            }));
            assertEquals(10, store.stock(city.cityId()).get("minecraft:bread"), "setAutoCommit(true) must not commit failed work");
        }
    }

    @Test void abruptProcessDeathRecoversCommittedWalAndDiscardsUncommittedWork() throws Exception {
        Path db = temp.resolve("crash.db"); CoreCity city = city();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"), CrashWriter.class.getName(), db.toString(), city.cityId().toString(), city.nationId().toString())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Crash fixture must not leave a server process running");
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(23, process.exitValue(), output);
        try (WorldStore store = new WorldStore(db, 1000)) {
            assertEquals(42, store.stock(city.cityId()).get("minecraft:bread"));
            assertFalse(store.settled(city.cityId(), 10));
            assertEquals(-1, store.view(city.cityId()).orElseThrow().lastPeriod());
        }
    }

    public static final class CrashWriter {
        public static void main(String[] args) throws Exception {
            WorldStore store = new WorldStore(Path.of(args[0]), 1000);
            CoreCity city = new CoreCity(UUID.fromString(args[1]), UUID.fromString(args[2]), "城市", "国家", 1, "fixture");
            store.ensureCity(city); store.stock(city.cityId(), "minecraft:bread", 42);
            store.tx(s -> {
                s.stock(city.cityId(), "minecraft:bread", 999);
                s.finish(city.cityId(), 10, "must not commit");
                Runtime.getRuntime().halt(23); // intentionally bypass close, shutdown hooks and JDBC rollback
                return null;
            });
        }
    }

    @Test void newerSchemaIsRejectedAndHistoricalSchemaUpgrades() throws Exception {
        Path newer = temp.resolve("newer.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + newer); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=99");
        }
        SQLException error = assertThrows(SQLException.class, () -> new WorldStore(newer, 1000));
        assertTrue(error.getMessage().contains("newer"));
        Path old = temp.resolve("old.db");
        try (WorldStore store = new WorldStore(old, 1000)) {
            store.execute("DROP TABLE audit");
            store.execute("DROP INDEX building_city");
            store.execute("DROP INDEX transfer_pending");
            store.execute("PRAGMA user_version=1");
        }
        try (WorldStore store = new WorldStore(old, 1000)) {
            assertEquals(2, store.schemaVersion());
            store.audit("migration-test", "schema-one audit table was absent and is now usable");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + old); Statement s = c.createStatement();
             ResultSet indexes = s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN ('building_city','transfer_pending')")) {
            assertTrue(indexes.next());
            assertEquals(2, indexes.getInt(1));
        }
    }

    @Test void pendingWithdrawalsAreNeverReplayedAfterRestartAndCanBeResolvedOnce() throws Exception {
        Path db = temp.resolve("withdraw.db"); CoreCity city = city(); Transfer transfer;
        try (WorldStore store = new WorldStore(db, 1000)) {
            store.ensureCity(city); store.stock(city.cityId(), "minecraft:iron_ingot", 20);
            transfer = store.prepareTransfer(UUID.randomUUID(), city.cityId(), "WITHDRAW", "minecraft:iron_ingot", 5);
            assertEquals(15, store.stock(city.cityId()).get("minecraft:iron_ingot"));
        }
        try (WorldStore store = new WorldStore(db, 1000)) {
            assertEquals(List.of(transfer), store.pending());
            assertEquals(15, store.stock(city.cityId()).get("minecraft:iron_ingot"));
            assertThrows(SQLException.class, () -> store.prepareTransfer(transfer.playerId(), city.cityId(), "WITHDRAW", "minecraft:iron_ingot", 1));
            store.resolveTransfer(transfer.id(), true, "test-admin");
            assertEquals(20, store.stock(city.cityId()).get("minecraft:iron_ingot"));
            assertTrue(store.pending().isEmpty());
            assertThrows(SQLException.class, () -> store.resolveTransfer(transfer.id(), true, "test-admin"));
            assertThrows(SQLException.class, () -> store.completeTransfer(transfer.id()));
            assertEquals(20, store.stock(city.cityId()).get("minecraft:iron_ingot"));
        }
    }

    @Test void pendingDepositsAwaitExplicitInventoryDecisionAndDoNotDoubleCredit() throws Exception {
        Path db = temp.resolve("deposit.db"); CoreCity city = city(); Transfer deposit;
        try (WorldStore store = new WorldStore(db, 1000)) {
            store.ensureCity(city); deposit = store.prepareTransfer(UUID.randomUUID(), city.cityId(), "DEPOSIT", "minecraft:wheat", 8);
            assertTrue(store.stock(city.cityId()).isEmpty());
        }
        try (WorldStore store = new WorldStore(db, 1000)) {
            assertTrue(store.stock(city.cityId()).isEmpty(), "Startup cannot assume the player's items were removed");
            store.completeTransfer(deposit.id());
            assertEquals(8, store.stock(city.cityId()).get("minecraft:wheat"));
            assertThrows(SQLException.class, () -> store.completeTransfer(deposit.id()));
            assertEquals(8, store.stock(city.cityId()).get("minecraft:wheat"));
            Transfer uncertain = store.prepareTransfer(UUID.randomUUID(), city.cityId(), "DEPOSIT", "minecraft:wheat", 3);
            store.resolveTransfer(uncertain.id(), false, "admin-no-removal");
            assertEquals(8, store.stock(city.cityId()).get("minecraft:wheat"));
        }
    }

    @Test void untouchedWithdrawalsCanBeCancelledOnlyOnceAndFullDepositsRemainPending() throws Exception {
        try (WorldStore store = new WorldStore(temp.resolve("transfer-limits.db"), 100)) {
            CoreCity city = city(); store.ensureCity(city); store.stock(city.cityId(), "minecraft:wheat", 90);
            Transfer withdraw = store.prepareTransfer(UUID.randomUUID(), city.cityId(), "WITHDRAW", "minecraft:wheat", 10);
            store.cancelUntouchedTransfer(withdraw.id());
            assertEquals(90, store.stock(city.cityId()).get("minecraft:wheat"));
            assertThrows(SQLException.class, () -> store.cancelUntouchedTransfer(withdraw.id()));
            Transfer a = store.prepareTransfer(UUID.randomUUID(), city.cityId(), "DEPOSIT", "minecraft:wheat", 10);
            Transfer b = store.prepareTransfer(UUID.randomUUID(), city.cityId(), "DEPOSIT", "minecraft:wheat", 10);
            store.completeTransfer(a.id());
            assertThrows(SQLException.class, () -> store.completeTransfer(b.id()));
            assertEquals(100, store.stock(city.cityId()).get("minecraft:wheat"));
            assertEquals("PREPARED", store.transfer(b.id()).orElseThrow().state(), "Capacity failure must preserve the unresolved receipt");
        }
    }

    @Test void unregisteringAndRegisteringFacilityCannotRefillDepletedReserve() throws Exception {
        Definitions d = definitions();
        try (WorldStore store = new WorldStore(temp.resolve("reserve.db"), 1000)) {
            CoreCity city = city(); store.ensureCity(city);
            Site site = new Site(UUID.randomUUID(), "world", 1, 64, 1);
            Building first = new Building(UUID.randomUUID(), city.cityId(), city.nationId(), "workshop", site, 1);
            store.addBuilding(first, "plains", d); store.reserve(site.regionKey(), "ore", 0);
            store.removeBuilding(city.cityId(), first.id());
            store.addBuilding(new Building(UUID.randomUUID(), city.cityId(), city.nationId(), "workshop", site, 1), "plains", d);
            assertEquals(0, store.region(site.regionKey()).reserves().get("ore"));
            assertThrows(SQLException.class, () -> store.addBuilding(new Building(UUID.randomUUID(), city.cityId(), city.nationId(), "workshop", new Site(site.worldId(), "world", 2, 64, 2), 1), "plains", d));
        }
    }

    @Test void civilizationIsNationScopedWithPersistentCooldownAndUpgradeConsumesStockAtomically() throws Exception {
        Path db = temp.resolve("nation.db"); CoreCity first = city();
        CoreCity sibling = new CoreCity(UUID.randomUUID(), first.nationId(), "第二城", first.nationName(), 1, "core");
        try (WorldStore store = new WorldStore(db, 1000)) {
            store.ensureCity(first); store.ensureCity(sibling);
            store.civilization(first.cityId(), "industrial", 0);
            assertEquals("industrial", store.view(sibling.cityId()).orElseThrow().civilization());
            assertThrows(SQLException.class, () -> store.civilization(sibling.cityId(), "agrarian", 5));
            store.civilization(sibling.cityId(), "agrarian", 6);
            assertEquals("agrarian", store.view(first.cityId()).orElseThrow().civilization());
            Building b = new Building(UUID.randomUUID(), first.cityId(), first.nationId(), "workshop", new Site(UUID.randomUUID(), "world", 0, 64, 0), 1);
            store.addBuilding(b, "plains", definitions());
            store.stock(first.cityId(), "minecraft:oak_planks", 16);
            assertThrows(SQLException.class, () -> store.upgrade(first.cityId(), b.id()));
            assertEquals(16, store.stock(first.cityId()).get("minecraft:oak_planks"));
            store.stock(first.cityId(), "minecraft:iron_ingot", 4); store.upgrade(first.cityId(), b.id());
            assertEquals(2, store.buildings(first.cityId()).getFirst().level());
            assertEquals(0, store.stock(first.cityId()).get("minecraft:oak_planks"));
            assertEquals(0, store.stock(first.cityId()).get("minecraft:iron_ingot"));
        }
        try (WorldStore store = new WorldStore(db, 1000)) {
            assertEquals("agrarian", store.view(sibling.cityId()).orElseThrow().civilization());
            assertThrows(SQLException.class, () -> store.civilization(first.cityId(), "industrial", 7));
        }
    }
}

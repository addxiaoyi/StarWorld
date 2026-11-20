package dev.starworld.persistence;

import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
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

    @Test void onlineBackupCreatesVerifiedCommittedSnapshotsAndCanBeReplaced() throws Exception {
        Path db=temp.resolve("online-backup.db"), backup=temp.resolve("backups/current.db");CoreCity city=city();
        String firstHash,secondHash;
        try(WorldStore store=new WorldStore(db,1000)){
            store.ensureCity(city);store.stock(city.cityId(),"minecraft:bread",42);
            WorldStore.Backup first=store.backup(backup);firstHash=first.sha256();assertEquals(2,first.schemaVersion());assertEquals("ok",first.integrity());
            try(WorldStore ignored=new WorldStore(backup,1000)) { assertEquals(42,ignored.stock(city.cityId()).get("minecraft:bread")); }
            store.stock(city.cityId(),"minecraft:bread",99);WorldStore.Backup second=store.backup(backup);secondHash=second.sha256();assertNotEquals(firstHash,secondHash);
            try(WorldStore ignored=new WorldStore(backup,1000)) { assertEquals(99,ignored.stock(city.cityId()).get("minecraft:bread")); }
            assertThrows(IllegalArgumentException.class,()->store.backup(db));
        }
    }

    @Test void repeatedOnlineBackupsRemainReadableAfterCommittedWrites() throws Exception {
        Path db=temp.resolve("online-backup-stress.db"),backup=temp.resolve("backups/stress.db");CoreCity city=city();
        try(WorldStore store=new WorldStore(db,10000)){
            store.ensureCity(city);store.stock(city.cityId(),"minecraft:wheat",0);
            for(int i=1;i<=12;i++){store.stock(city.cityId(),"minecraft:wheat",i);WorldStore.Backup snapshot=store.backup(backup);assertEquals("ok",snapshot.integrity());try(WorldStore copy=new WorldStore(backup,10000)){assertEquals(i,copy.stock(city.cityId()).get("minecraft:wheat"));}}
        }
    }

    @Test void onlineBackupRejectsActiveDatabaseHardlinksAndWalShmTargets()throws Exception {
        Path db=temp.resolve("protected.db"),alias=temp.resolve("same-inode.db");CoreCity city=city();
        try(WorldStore store=new WorldStore(db,1000)){
            store.ensureCity(city);store.stock(city.cityId(),"minecraft:bread",42);Files.createLink(alias,db);
            assertTrue(Files.isSameFile(alias,db));assertThrows(IllegalArgumentException.class,()->store.backup(alias));
            assertThrows(IllegalArgumentException.class,()->store.backup(Path.of(db+"-wal")));
            assertThrows(IllegalArgumentException.class,()->store.backup(Path.of(db+"-shm")));
            Path wal=Path.of(db+"-wal");assertTrue(Files.exists(wal));Path walAlias=temp.resolve("same-wal.db");Files.createLink(walAlias,wal);
            assertThrows(IllegalArgumentException.class,()->store.backup(walAlias));assertEquals(42,store.stock(city.cityId()).get("minecraft:bread"));
        }
    }

    @Test void onlineBackupRejectsLinkedTargetsAndLinkedBackupDirectory()throws Exception {
        Path db=temp.resolve("linked-protection.db"),plugin=temp.resolve("plugin"),outside=temp.resolve("outside");Files.createDirectories(plugin);Files.createDirectories(outside);
        try(WorldStore store=new WorldStore(db,1000)){
            Path alias=temp.resolve("symlink.db");Files.createSymbolicLink(alias,db);assertThrows(IllegalArgumentException.class,()->store.backup(alias));
            Files.createSymbolicLink(plugin.resolve("backups"),outside);assertThrows(IllegalArgumentException.class,()->store.backupInPluginDirectory(plugin,"escape"));assertFalse(Files.exists(outside.resolve("escape.db")));
            assertThrows(IllegalArgumentException.class,()->store.backupInPluginDirectory(plugin,"../escape"));
        }
    }

    @Test void nestedTransactionAndInvalidSchemaBackupFailuresPreservePreviousSnapshot()throws Exception {
        Path db=temp.resolve("backup-failure.db"),backup=temp.resolve("backups/previous.db");CoreCity city=city();
        try(WorldStore store=new WorldStore(db,1000)){
            store.ensureCity(city);store.stock(city.cityId(),"minecraft:bread",42);store.backup(backup);byte[] original=Files.readAllBytes(backup);
            store.tx(s->{s.stock(city.cityId(),"minecraft:bread",99);assertThrows(IllegalStateException.class,()->s.backup(backup));return null;});assertArrayEquals(original,Files.readAllBytes(backup));
            store.execute("PRAGMA user_version=99");assertThrows(SQLException.class,()->store.backup(backup));assertArrayEquals(original,Files.readAllBytes(backup));store.execute("PRAGMA user_version=2");
            Path directory=temp.resolve("non-file-target");Files.createDirectories(directory);Files.writeString(directory.resolve("keep"),"untouched");assertThrows(IllegalArgumentException.class,()->store.backup(directory));assertEquals("untouched",Files.readString(directory.resolve("keep")));
            try(var remaining=Files.list(backup.getParent())){assertTrue(remaining.noneMatch(p->p.getFileName().toString().startsWith(".starworld-backup-")));}
            try(WorldStore copy=new WorldStore(backup,1000)){assertEquals(42,copy.stock(city.cityId()).get("minecraft:bread"));}
        }
    }

    @Test void pluginBackupResolvesOrdinaryDirectoryAndRejectsTraversalNames()throws Exception {
        Path db=temp.resolve("plugin-backup.db"),plugin=temp.resolve("plain-plugin");CoreCity city=city();
        try(WorldStore store=new WorldStore(db,1000)){
            store.ensureCity(city);store.stock(city.cityId(),"minecraft:bread",42);
            WorldStore.Backup result=store.backupInPluginDirectory(plugin,"weekly-01");assertEquals(plugin.toRealPath().resolve("backups/weekly-01.db"),result.path());assertEquals(2,result.schemaVersion());
            for(String filename:List.of(".","..","../outside","a/b","a\\b"))assertThrows(IllegalArgumentException.class,()->store.backupInPluginDirectory(plugin,filename));
            try(WorldStore copy=new WorldStore(result.path(),1000)){assertEquals(42,copy.stock(city.cityId()).get("minecraft:bread"));}
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
    @Test void reducingConfiguredCapacityPreservesExistingGoodsAndAllowsGradualWithdrawal()throws Exception{
        Path db=temp.resolve("reduced-limit.db");CoreCity city=city();
        try(WorldStore store=new WorldStore(db,1000)){store.ensureCity(city);store.stock(city.cityId(),"minecraft:wheat",900);store.cityCapacity(city.cityId(),1000);}
        try(WorldStore store=new WorldStore(db,100)){assertEquals(900,store.stock(city.cityId()).get("minecraft:wheat"));assertEquals(100,store.cityCapacity(city.cityId()));
            assertThrows(SQLException.class,()->store.stock(city.cityId(),"minecraft:wheat",901));
            Transfer t=store.prepareTransfer(UUID.randomUUID(),city.cityId(),"WITHDRAW","minecraft:wheat",20);assertEquals(880,store.stock(city.cityId()).get("minecraft:wheat"));store.completeTransfer(t.id());
        }
    }
    @Test void configuredPeriodMigrationKeepsLogicalIdsAndEventTimelineAcrossRestart()throws Exception{
        Path db=temp.resolve("clock.db");CoreCity city=city();EventState event;
        try(WorldStore s=new WorldStore(db,1000)){s.ensureCity(city);var clock=s.simulationClock(10,1000);assertEquals(100,clock.period(1000));s.finish(city.cityId(),100,"old period");event=new EventState("drought",100,103,105);s.event(city.cityId(),event);}
        try(WorldStore s=new WorldStore(db,1000)){var clock=s.simulationClock(300,1010);assertEquals(101,clock.period(1010));assertEquals(102,clock.period(1310));assertEquals(event,s.view(city.cityId()).orElseThrow().event());assertTrue(clock.period(710)<=100,"clock rollback does not create a future settlement");}
        try(WorldStore s=new WorldStore(db,1000)){var clock=s.simulationClock(300,1310);assertEquals(102,clock.period(1310));var quick=s.simulationClock(10,1310);assertEquals(102,quick.period(1310));assertEquals(103,quick.period(1320));}
    }
    @Test void upgradingLegacyDatabaseWithLargerHistoricalPeriodNeverFreezesSettlementForYears()throws Exception{
        try(WorldStore s=new WorldStore(temp.resolve("legacy-clock.db"),1000)){CoreCity c=city();s.ensureCity(c);s.finish(c.cityId(),10000,"historical ten second period");var clock=s.simulationClock(300,1000);assertEquals(10000,clock.period(1000));assertEquals(10001,clock.period(1300));}
    }
    @Test void cancellingAnUntouchedWithdrawalRestoresReservedGoodsAfterWarehouseCapacityFalls()throws Exception{
        try(WorldStore s=new WorldStore(temp.resolve("refund-capacity.db"),1000)){CoreCity c=city();s.ensureCity(c);s.stock(c.cityId(),"minecraft:wheat",500);Transfer t=s.prepareTransfer(UUID.randomUUID(),c.cityId(),"WITHDRAW","minecraft:wheat",100);s.cityCapacity(c.cityId(),100);s.cancelUntouchedTransfer(t.id());assertEquals(500,s.stock(c.cityId()).get("minecraft:wheat"));assertThrows(Exception.class,()->s.cancelUntouchedTransfer(t.id()));assertThrows(SQLException.class,()->s.stock(c.cityId(),"minecraft:wheat",501));}
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

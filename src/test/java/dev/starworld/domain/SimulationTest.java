package dev.starworld.domain;

import dev.starworld.config.ConfigLoaderTest;
import dev.starworld.domain.WorldState.*;
import dev.starworld.persistence.WorldStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SimulationTest {
    @TempDir Path temp;
    private Definitions definitions() throws Exception {
        Path config = temp.resolve("config.yml"), data = temp.resolve("definitions.yml");
        try (var in = getClass().getResourceAsStream("/config.yml")) { Files.copy(Objects.requireNonNull(in), config); }
        try (var in = getClass().getResourceAsStream("/definitions.yml")) { Files.copy(Objects.requireNonNull(in), data); }
        Definitions d = ConfigLoaderTest.loadWithPlatformFixture(config, data);
        return new Definitions(d.periodSeconds(), d.maxBuildingsPerCity(), d.maxCitiesPerPass(), d.stockLimit(), 0,
                d.terrains(), d.civilizations(), d.buildings(), d.recipes(), d.events());
    }
    private CoreCity city() { return new CoreCity(UUID.randomUUID(), UUID.randomUUID(), "测试城", "测试国", 1, "测试核心快照"); }
    private Building register(WorldStore store, Definitions d, CoreCity city, String type, String terrain, int chunk) throws SQLException {
        store.ensureCity(city);
        UUID id = new UUID(0, chunk + 1L);
        Building b = new Building(id, city.cityId(), city.nationId(), type, new Site(new UUID(0, 100), "world", chunk * 16, 64, 0), 1);
        store.addBuilding(b, terrain, d);
        return b;
    }
    private long amount(WorldStore store, CoreCity city, String item) throws SQLException { return store.stock(city.cityId()).getOrDefault("minecraft:" + item, 0L); }

    @Test void threeCompleteDefaultChainsConvertReservesIntoFoodWoodAndWeapons() throws Exception {
        Definitions d = definitions();
        try (WorldStore store = new WorldStore(temp.resolve("chains.db"), d.stockLimit())) {
            CoreCity city = city();
            Building farm = register(store, d, city, "farm", "plains", 0);
            Building sawmill = register(store, d, city, "sawmill", "plains", 1);
            Building workshop = register(store, d, city, "workshop", "plains", 2);
            Set<UUID> authorized = Set.of(farm.id(), sawmill.id(), workshop.id());
            Simulation simulation = new Simulation(store, d);
            assertTrue(simulation.settle(city, 10, authorized).applied());
            Settlement second = simulation.settle(city, 11, authorized);
            assertTrue(second.produced().getOrDefault("minecraft:arrow", 0L) > 0, "Military supplies must require upstream timber and smelting");
            assertEquals(26, amount(store, city, "wheat"));
            assertEquals(0, amount(store, city, "bread"), "Food actually pays for industrial upkeep");
            assertEquals(4, amount(store, city, "oak_log"));
            assertEquals(18, amount(store, city, "oak_planks"));
            assertEquals(0, amount(store, city, "raw_iron"));
            assertEquals(0, amount(store, city, "iron_ingot"));
            assertEquals(10, amount(store, city, "arrow"));
            assertEquals(88, store.region(workshop.site().regionKey()).reserves().get("ore"));
        }
    }

    @Test void allSixTerrainProfilesChangeActualAgriculturalOutput() throws Exception {
        Definitions d = definitions();
        try (WorldStore store = new WorldStore(temp.resolve("terrain.db"), d.stockLimit())) {
            Set<Long> yields = new HashSet<>(); int chunk = 0;
            for (String terrain : List.of("mountains", "river", "plains", "forest", "desert", "snow")) {
                CoreCity city = city(); Building b = register(store, d, city, "farm", terrain, chunk++);
                Settlement result = new Simulation(store, d).settle(city, 10, Set.of(b.id()));
                long wheat = result.produced().getOrDefault("minecraft:wheat", 0L);
                assertEquals((long) Math.floor(12 * 1.25 * d.terrains().get(terrain).agriculture()), wheat, terrain);
                yields.add(wheat);
            }
            assertEquals(6, yields.size());
        }
    }

    @Test void civilizationTradeoffChangesFarmAndWorkshopProduction() throws Exception {
        Definitions d = definitions();
        try (WorldStore store = new WorldStore(temp.resolve("civilization.db"), d.stockLimit())) {
            Map<String, Long> farmYields = new HashMap<>(), ironYields = new HashMap<>(); int chunk = 0;
            for (String civ : List.of("agrarian", "industrial")) {
                CoreCity city = city();
                Building farm = register(store, d, city, "farm", "plains", chunk++);
                Building workshop = register(store, d, city, "workshop", "plains", chunk++);
                store.civilization(city.cityId(), civ, 0);
                store.stock(city.cityId(), "minecraft:bread", 20);
                store.stock(city.cityId(), "minecraft:oak_log", 10);
                store.stock(city.cityId(), "minecraft:oak_planks", 20);
                Settlement result = new Simulation(store, d).settle(city, 10, Set.of(farm.id(), workshop.id()));
                farmYields.put(civ, result.produced().get("minecraft:wheat"));
                ironYields.put(civ, result.produced().get("minecraft:iron_ingot"));
            }
            assertTrue(farmYields.get("agrarian") > farmYields.get("industrial"));
            assertTrue(ironYields.get("industrial") > ironYields.get("agrarian"));
        }
    }

    @Test void threeEventsApplyTheirConfiguredActiveAndAftermathEffectsThenExpire() throws Exception {
        Definitions d = definitions();
        try (WorldStore store = new WorldStore(temp.resolve("events.db"), d.stockLimit())) {
            int chunk = 0;
            for (String type : List.of("drought", "blizzard", "harvest")) {
                CoreCity city = city(); Building farm = register(store, d, city, "farm", "plains", chunk++);
                Simulation sim = new Simulation(store, d); Definitions.WorldEvent event = d.events().get(type);
                EventState state = sim.trigger(city.cityId(), type, 10);
                long active = sim.settle(city, 10, Set.of(farm.id())).produced().get("minecraft:wheat");
                long aftermath = sim.settle(city, state.endPeriod(), Set.of(farm.id())).produced().get("minecraft:wheat");
                long recovered = sim.settle(city, state.aftermathEndPeriod(), Set.of(farm.id())).produced().get("minecraft:wheat");
                assertEquals((long) Math.floor(12 * 1.25 * 1.3 * event.multipliers().get("farm")), active, type);
                assertEquals((long) Math.floor(12 * 1.25 * 1.3 * event.aftermathMultiplier()), aftermath, type);
                assertEquals(19, recovered, type);
                assertNull(store.view(city.cityId()).orElseThrow().event());
            }
        }
    }

    @Test void samePeriodAndClockRollbackCannotReplayAfterRestartOrCreateOfflineCatchup() throws Exception {
        Definitions d = definitions(); Path db = temp.resolve("restart.db"); CoreCity city = city(); Building b;
        try (WorldStore store = new WorldStore(db, d.stockLimit())) {
            b = register(store, d, city, "farm", "plains", 0);
            new Simulation(store, d).settle(city, 10, Set.of(b.id()));
        }
        try (WorldStore store = new WorldStore(db, d.stockLimit())) {
            Simulation sim = new Simulation(store, d); Map<String, Long> before = store.stock(city.cityId());
            assertFalse(sim.settle(city, 10, Set.of(b.id())).applied());
            assertFalse(sim.settle(city, 9, Set.of(b.id())).applied());
            assertEquals(before, store.stock(city.cityId()));
            assertTrue(sim.settle(city, 100, Set.of(b.id())).applied());
            assertEquals(26, amount(store, city, "wheat"), "A long restart gap adds one current period, not 90 offline cycles");
        }
    }

    @Test void concurrentSettlementRequestsShareOneReceiptAndOneProduction() throws Exception {
        Definitions d = definitions();
        try (WorldStore store = new WorldStore(temp.resolve("concurrent.db"), d.stockLimit()); ExecutorService pool = Executors.newFixedThreadPool(8)) {
            CoreCity city = city(); Building b = register(store, d, city, "farm", "plains", 0); Simulation sim = new Simulation(store, d);
            CountDownLatch start = new CountDownLatch(1); List<Future<Settlement>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> { start.await(); return sim.settle(city, 10, Set.of(b.id())); }));
            start.countDown(); int applied = 0;
            for (Future<Settlement> future : futures) if (future.get(10, TimeUnit.SECONDS).applied()) applied++;
            assertEquals(1, applied);
            assertEquals(13, amount(store, city, "wheat"));
            assertEquals(3, amount(store, city, "bread"));
        }
    }

    @Test void independentDatabaseConnectionsCannotCommitDuplicatePeriod() throws Exception {
        Definitions d = definitions(); Path db = temp.resolve("connections.db");
        try (WorldStore first = new WorldStore(db, d.stockLimit()); WorldStore second = new WorldStore(db, d.stockLimit()); ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CoreCity city = city(); Building b = register(first, d, city, "farm", "plains", 0);
            CountDownLatch start = new CountDownLatch(1);
            Future<Settlement> a = pool.submit(() -> { start.await(); return new Simulation(first, d).settle(city, 10, Set.of(b.id())); });
            Future<Settlement> other = pool.submit(() -> { start.await(); return new Simulation(second, d).settle(city, 10, Set.of(b.id())); });
            start.countDown();
            assertEquals(1, (a.get(10, TimeUnit.SECONDS).applied() ? 1 : 0) + (other.get(10, TimeUnit.SECONDS).applied() ? 1 : 0));
            assertEquals(13, amount(first, city, "wheat"));
            assertTrue(first.settled(city.cityId(), 10)); assertTrue(second.settled(city.cityId(), 10));
        }
    }

    @Test void unauthorizedFacilitiesAndChangedNationStopProductionWithoutPayingUpkeep() throws Exception {
        Definitions d = definitions();
        try (WorldStore store = new WorldStore(temp.resolve("ownership.db"), d.stockLimit())) {
            CoreCity city = city(); Building b = register(store, d, city, "workshop", "mountains", 0);
            store.stock(city.cityId(), "minecraft:bread", 20); Simulation sim = new Simulation(store, d);
            assertTrue(sim.settle(city, 10, Set.of()).produced().isEmpty());
            CoreCity changed = new CoreCity(city.cityId(), UUID.randomUUID(), city.cityName(), "新国家", 1, "领土已转移");
            assertTrue(sim.settle(changed, 11, Set.of(b.id())).produced().isEmpty());
            assertEquals(20, amount(store, city, "bread"));
            assertEquals(1200, store.region(b.site().regionKey()).reserves().get("ore"));
        }
    }

    @Test void exhaustedNonrenewableReservesAndMissingUpkeepPreventProduction() throws Exception {
        Definitions d = definitions();
        try (WorldStore store = new WorldStore(temp.resolve("scarcity.db"), d.stockLimit())) {
            CoreCity city = city(); Building b = register(store, d, city, "workshop", "mountains", 0); Simulation sim = new Simulation(store, d);
            assertTrue(sim.settle(city, 10, Set.of(b.id())).produced().isEmpty());
            assertEquals(1200, store.region(b.site().regionKey()).reserves().get("ore"));
            store.stock(city.cityId(), "minecraft:bread", 20); store.reserve(b.site().regionKey(), "ore", 0);
            assertTrue(sim.settle(city, 11, Set.of(b.id())).produced().isEmpty());
            assertEquals(0, amount(store, city, "raw_iron"));
            assertEquals(0, store.region(b.site().regionKey()).reserves().get("ore"));
        }
    }

    @Test void fullOutputStockPreservesRecipeIngredientsAndReserve() throws Exception {
        Definitions d = definitions();
        try (WorldStore store = new WorldStore(temp.resolve("capacity.db"), d.stockLimit())) {
            CoreCity city = city(); Building b = register(store, d, city, "farm", "plains", 0);
            store.stock(city.cityId(), "minecraft:bread", d.stockLimit());
            store.stock(city.cityId(), "minecraft:wheat", d.stockLimit());
            Settlement result = new Simulation(store, d).settle(city, 10, Set.of(b.id()));
            assertTrue(result.produced().isEmpty());
            assertEquals(d.stockLimit(), amount(store, city, "wheat"));
            assertEquals(d.stockLimit(), amount(store, city, "bread"));
            assertEquals(700, store.region(b.site().regionKey()).reserves().get("fertility"));
        }
    }

    @Test void sharedRegionRegeneratesOnceAcrossDifferentCitySettlementsAndRestart() throws Exception {
        Definitions d = definitions(); Path db = temp.resolve("shared.db"); CoreCity first = city(), second = city(); Building a, b;
        try (WorldStore store = new WorldStore(db, d.stockLimit())) {
            a = register(store, d, first, "farm", "plains", 0);
            store.ensureCity(second);
            b = new Building(UUID.randomUUID(), second.cityId(), second.nationId(), "workshop", new Site(a.site().worldId(), "world", 2, 64, 2), 1);
            store.addBuilding(b, "plains", d);
            store.reserve(a.site().regionKey(), "fertility", 100);
            new Simulation(store, d).settle(first, 10, Set.of(a.id()));
            assertEquals(106, store.region(a.site().regionKey()).reserves().get("fertility"));
        }
        try (WorldStore store = new WorldStore(db, d.stockLimit())) {
            new Simulation(store, d).settle(second, 10, Set.of(b.id()));
            assertEquals(106, store.region(a.site().regionKey()).reserves().get("fertility"), "The second city must not receive another regeneration tick");
        }
    }

    @Test void failureAfterProductionRollsBackStockReservesEventAndPeriodReceipt() throws Exception {
        Definitions d = definitions();
        try (WorldStore store = new WorldStore(temp.resolve("atomic.db"), d.stockLimit())) {
            CoreCity city = city(); Building b = register(store, d, city, "farm", "plains", 0);
            store.execute("CREATE TRIGGER reject_receipt BEFORE INSERT ON settlement BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END");
            assertThrows(SQLException.class, () -> new Simulation(store, d).settle(city, 10, Set.of(b.id())));
            assertTrue(store.stock(city.cityId()).isEmpty());
            assertEquals(700, store.region(b.site().regionKey()).reserves().get("fertility"));
            assertEquals(-1, store.view(city.cityId()).orElseThrow().lastPeriod());
            assertFalse(store.settled(city.cityId(), 10));
            store.execute("DROP TRIGGER reject_receipt");
            assertTrue(new Simulation(store, d).settle(city, 10, Set.of(b.id())).applied());
            assertEquals(13, amount(store, city, "wheat"));
        }
    }

    @Test void unavailableCustomOutputPausesItsRecipeAndKeepsIngredients() throws Exception {
        Definitions original = definitions();
        Definitions d = new Definitions(original.periodSeconds(), original.maxBuildingsPerCity(), original.maxCitiesPerPass(), original.stockLimit(), 0,
                original.terrains(), original.civilizations(), original.buildings(),
                List.of(new Definitions.Recipe("custom_food", "farm", Map.of("minecraft:wheat", 6L), Map.of("kaleidoscopecookery:baozi", 2L), null, 0)), original.events());
        try (WorldStore store = new WorldStore(temp.resolve("optional-content.db"), d.stockLimit())) {
            CoreCity city = city(); Building b = register(store, d, city, "farm", "plains", 0);
            store.stock(city.cityId(), "minecraft:wheat", 20);
            Settlement result = new Simulation(store, d, Set.of("minecraft:wheat")).settle(city, 10, Set.of(b.id()));
            assertTrue(result.produced().isEmpty());
            assertEquals(20, amount(store, city, "wheat"));
            assertEquals(0, store.stock(city.cityId()).getOrDefault("kaleidoscopecookery:baozi", 0L));
        }
    }
}

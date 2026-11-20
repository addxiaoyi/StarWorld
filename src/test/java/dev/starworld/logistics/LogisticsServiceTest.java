package dev.starworld.logistics;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.domain.WorldState.Site;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsServiceTest {
    @TempDir Path temp;
    private static final String BREAD = "minecraft:bread", IRON = "minecraft:iron_ingot", PLANKS = "minecraft:oak_planks";

    private static final class Rig implements AutoCloseable {
        final Path db; final WorldStore store; final LogisticsService service;
        final UUID world = UUID.randomUUID(), actor = UUID.randomUUID();
        final CoreCity a = new CoreCity(UUID.randomUUID(), UUID.randomUUID(), "粮城", "农国", 1, "test");
        final CoreCity b = new CoreCity(UUID.randomUUID(), UUID.randomUUID(), "铁城", "工国", 1, "test");
        final Set<String> anchors = new HashSet<>(); UUID originNode, targetNode, route;
        Rig(Path db, LogisticsDefinitions definitions, long stockLimit) throws Exception {
            this.db = db; store = new WorldStore(db, stockLimit); service = new LogisticsService(store, definitions);
            for (CoreCity city : List.of(a, b)) { store.ensureCity(city); store.stock(city.cityId(), BREAD, 1000); store.stock(city.cityId(), IRON, 1000); store.stock(city.cityId(), PLANKS, 1000); }
        }
        AuthoritySnapshot auth(long period, UUID... managed) throws Exception {
            Set<UUID> nodes = new HashSet<>(); for (Node n : service.nodes(1000, 0)) nodes.add(n.id());
            return new AuthoritySnapshot(actor, period, Map.of(a.cityId(), a.nationId(), b.cityId(), b.nationId()), Set.of(managed), anchors, nodes, Set.of(TradePair.of(a.cityId(), b.cityId())));
        }
        Node register(CoreCity city, NodeType type, int x, String terrain) throws Exception {
            Site site = new Site(world, "world", x, 64, 0); anchors.add(site.anchorKey());
            return service.registerNode(UUID.randomUUID(), city.cityId(), type, site, terrain, auth(0, city.cityId()));
        }
        void route(int distance) throws Exception {
            originNode = register(a, NodeType.ROAD, 0, "plains").id(); targetNode = register(b, NodeType.ROAD, distance, "plains").id();
            route = UUID.randomUUID(); service.createRoute(route, a.cityId(), b.cityId(), List.of(originNode, targetNode), Mode.LAND, auth(0, a.cityId()));
        }
        Order offer(long period, long bread, long iron) throws Exception { return service.createOffer(UUID.randomUUID(), route, Map.of(BREAD, bread), Map.of(IRON, iron), auth(period, a.cityId())); }
        Order accepted(long bread, long iron) throws Exception { Order o = offer(0, bread, iron); return service.accept(o.id(), auth(0, b.cityId())); }
        long stock(CoreCity city, String item) throws SQLException { return store.stock(city.cityId()).getOrDefault(item, 0L); }
        public void close() throws Exception { store.close(); }
    }
    private Rig rig() throws Exception { return new Rig(temp.resolve(UUID.randomUUID() + ".db"), LogisticsDefinitions.defaults(), 10000); }

    @Test void allFourNodesHaveRealConstructionAndUpgradesAndStableRegistrationIds() throws Exception {
        try (Rig r = rig()) {
            int x = 0;
            for (NodeType type : NodeType.values()) {
                long before = r.stock(r.a, PLANKS); Node node = r.register(r.a, type, x, "plains"); x += 16;
                assertEquals(before - r.service.definitions().nodes().get(type).construction().get(PLANKS), r.stock(r.a, PLANKS));
                assertEquals(node, r.service.registerNode(node.id(), node.cityId(), type, node.site(), node.terrain(), r.auth(0, r.a.cityId())));
                assertEquals(2, r.service.upgradeNode(node.id(), r.auth(0, r.a.cityId())).level());
            }
            assertEquals(4, r.service.nodes(100, 0).size());
            assertEquals(2, r.service.nodes(2, 2).size());
        }
    }

    @Test void registrationAndRoutesRejectUnprovenAuthorityAndForeignWaypointUpkeep() throws Exception {
        try (Rig r = rig()) {
            Site site = new Site(r.world, "world", 0, 64, 0);
            assertThrows(SQLException.class, () -> r.service.registerNode(UUID.randomUUID(), r.a.cityId(), NodeType.ROAD, site, "plains", r.auth(0, r.a.cityId())));
            r.route(32);
            assertThrows(SQLException.class, () -> r.service.createRoute(UUID.randomUUID(), r.a.cityId(), r.b.cityId(), List.of(r.originNode, r.targetNode), Mode.LAND, r.auth(0, r.b.cityId())));
            AuthoritySnapshot a = r.auth(0, r.a.cityId());
            AuthoritySnapshot missingNode = new AuthoritySnapshot(a.actor(), 0, a.cityNations(), a.managedCities(), a.controlledAnchors(), Set.of(r.originNode), a.permittedTrades());
            assertThrows(SQLException.class, () -> r.service.createRoute(UUID.randomUUID(), r.a.cityId(), r.b.cityId(), List.of(r.originNode, r.targetNode), Mode.LAND, missingNode));
            assertThrows(SQLException.class, () -> r.service.createRoute(UUID.randomUUID(), r.a.cityId(), r.b.cityId(), List.of(r.originNode, r.targetNode), Mode.SEA, a));
        }
    }

    @Test void terrainAndMaritimeNodesChangeActualTravelTimeWithoutCrossWorldTeleporting() throws Exception {
        try (Rig r = rig()) {
            Node a = r.register(r.a, NodeType.ROAD, 0, "mountains"), b = r.register(r.b, NodeType.PASS, 320, "mountains");
            Route mountain = r.service.createRoute(UUID.randomUUID(), r.a.cityId(), r.b.cityId(), List.of(a.id(), b.id()), Mode.LAND, r.auth(0, r.a.cityId()));
            assertEquals(5, mountain.travelPeriods()); assertEquals(128, mountain.capacity());
            Node p = r.register(r.a, NodeType.PORT, 16, "river"), q = r.register(r.b, NodeType.PORT, 336, "river");
            Route sea = r.service.createRoute(UUID.randomUUID(), r.a.cityId(), r.b.cityId(), List.of(p.id(), q.id()), Mode.SEA, r.auth(0, r.a.cityId()));
            assertEquals(2, sea.travelPeriods()); assertEquals(512, sea.capacity());
            Site other = new Site(UUID.randomUUID(), "nether", 32, 64, 0); r.anchors.add(other.anchorKey());
            Node alien = r.service.registerNode(UUID.randomUUID(), r.b.cityId(), NodeType.PORT, other, "river", r.auth(0, r.b.cityId()));
            assertThrows(SQLException.class, () -> r.service.createRoute(UUID.randomUUID(), r.a.cityId(), r.b.cityId(), List.of(p.id(), alien.id()), Mode.SEA, r.auth(0, r.a.cityId())));
        }
    }

    @Test void quoteAndAcceptanceEscrowPhysicalItemsOnceAndRequireBothCitiesConsent() throws Exception {
        try (Rig r = rig()) {
            r.route(320); Order o = r.offer(0, 20, 5);
            assertEquals(980, r.stock(r.a, BREAD)); assertEquals(1000, r.stock(r.b, IRON));
            assertEquals(o, r.service.createOffer(o.id(), r.route, o.offered(), o.requested(), r.auth(0, r.a.cityId())));
            assertEquals(980, r.stock(r.a, BREAD));
            assertThrows(SQLException.class, () -> r.service.accept(o.id(), r.auth(0, r.a.cityId())));
            assertEquals(OrderStatus.IN_TRANSIT, r.service.accept(o.id(), r.auth(0, r.b.cityId())).status());
            r.service.accept(o.id(), r.auth(0, r.b.cityId())); assertEquals(995, r.stock(r.b, IRON));
            assertThrows(SQLException.class, () -> r.service.createOffer(o.id(), r.route, Map.of(BREAD, 30L), o.requested(), r.auth(0, r.a.cityId())));
            assertEquals(980, r.stock(r.a, BREAD));
        }
    }

    @Test void transportMovesOncePerPeriodAndSkippedOfflineTimeDoesNotProduceCatchup() throws Exception {
        try (Rig r = rig()) {
            r.route(320); Order o = r.accepted(20, 5);
            assertEquals(2, o.travelPeriods());
            assertEquals(1, r.service.settle(r.auth(100), 10).progressed());
            assertEquals(1, r.service.order(o.id()).orElseThrow().progressedPeriods());
            assertEquals(0, r.service.settle(r.auth(100), 10).inspected());
            assertEquals(1, r.service.settle(r.auth(101), 10).completed());
            assertEquals(1020, r.stock(r.b, BREAD)); assertEquals(1005, r.stock(r.a, IRON));
            assertEquals(1, r.service.completedAfter(0, 100).size());
            TradeReceipt receipt = r.service.completedAfter(0, 100).getFirst();
            assertEquals(o.id(), receipt.orderId()); assertEquals(o.offered(), receipt.offered());
            assertTrue(r.service.completedAfter(receipt.sequence(), 100).isEmpty());
            assertEquals(0, r.service.settle(r.auth(102), 10).completed());
        }
    }

    @Test void escrowAndTransitResumeAfterRestartAndReceiptCannotBeDuplicated() throws Exception {
        Path db; Order order; UUID a, b, nationA, nationB, actor; Set<String> anchors; Set<UUID> valid;
        try (Rig r = rig()) {
            r.route(320); order = r.accepted(20, 5); r.service.settle(r.auth(1), 10);
            db = r.db; a = r.a.cityId(); b = r.b.cityId(); nationA = r.a.nationId(); nationB = r.b.nationId(); actor = r.actor; anchors = Set.copyOf(r.anchors); valid = Set.of(r.originNode, r.targetNode);
        }
        try (WorldStore store = new WorldStore(db, 10000)) {
            LogisticsService service = new LogisticsService(store, LogisticsDefinitions.defaults());
            AuthoritySnapshot auth = new AuthoritySnapshot(actor, 2, Map.of(a, nationA, b, nationB), Set.of(b), anchors, valid, Set.of(TradePair.of(a, b)));
            service.accept(order.id(), auth); assertEquals(995, store.stock(b).get(IRON));
            assertEquals(1, service.settle(auth, 10).completed());
            assertEquals(1020, store.stock(b).get(BREAD)); assertEquals(1, service.completedAfter(0, 10).size());
        }
        try (WorldStore store = new WorldStore(db, 10000)) {
            LogisticsService service = new LogisticsService(store, LogisticsDefinitions.defaults());
            assertEquals(OrderStatus.COMPLETED, service.order(order.id()).orElseThrow().status());
            assertEquals(1, service.completedAfter(0, 10).size());
        }
    }

    @Test void claimLossAndDiplomaticClosurePauseCargoWithoutProducingOrDiscardingIt() throws Exception {
        try (Rig r = rig()) {
            r.route(320); Order o = r.accepted(20, 5); AuthoritySnapshot a = r.auth(1);
            AuthoritySnapshot lost = new AuthoritySnapshot(a.actor(), 1, a.cityNations(), Set.of(), a.controlledAnchors(), Set.of(r.originNode), a.permittedTrades());
            assertEquals(1, r.service.settle(lost, 10).paused()); assertEquals(0, r.service.order(o.id()).orElseThrow().progressedPeriods());
            a = r.auth(2); AuthoritySnapshot war = new AuthoritySnapshot(a.actor(), 2, a.cityNations(), Set.of(), a.controlledAnchors(), a.validNodes(), Set.of());
            assertEquals(1, r.service.settle(war, 10).paused()); assertEquals(995, r.stock(r.b, IRON)); assertEquals(1000, r.stock(r.b, BREAD));
            assertEquals(1, r.service.settle(r.auth(3), 10).progressed());
            assertEquals(1, r.service.settle(r.auth(4), 10).completed());
        }
    }

    @Test void conquestCannotSendOrRefundOriginalNationsEscrowToNewOwners() throws Exception {
        try (Rig r = rig()) {
            r.route(32); Order o = r.accepted(20, 5); UUID conqueror = UUID.randomUUID();
            r.store.ensureCity(new CoreCity(r.b.cityId(), conqueror, r.b.cityName(), "新国家", 1, "test"));
            AuthoritySnapshot a = r.auth(1, r.b.cityId()); Map<UUID, UUID> changed = Map.of(r.a.cityId(), r.a.nationId(), r.b.cityId(), conqueror);
            AuthoritySnapshot capture = new AuthoritySnapshot(a.actor(), 1, changed, a.managedCities(), a.controlledAnchors(), a.validNodes(), a.permittedTrades());
            assertEquals(1, r.service.settle(capture, 10).paused());
            assertThrows(SQLException.class, () -> r.service.cancel(o.id(), capture));
            assertEquals(995, r.stock(r.b, IRON)); assertEquals(980, r.stock(r.a, BREAD));
        }
    }

    @Test void routeCapacityAndMaintenanceAreSharedAcrossCargoAndBatchBounded() throws Exception {
        LogisticsDefinitions d = LogisticsDefinitions.defaults(); Map<NodeType, LogisticsDefinitions.NodeSpec> specs = new EnumMap<>(d.nodes());
        var road = specs.get(NodeType.ROAD); specs.put(NodeType.ROAD, new LogisticsDefinitions.NodeSpec(road.name(), road.anchor(), 20, road.timeFactor(), road.construction(), road.maintenance()));
        LogisticsDefinitions narrow = new LogisticsDefinitions(specs, d.terrainTime(), d.maxNodesPerCity(), d.maxRoutesPerCity(), d.maxWaypoints(), d.maxOpenOrdersPerCity(), d.maxBatch(), d.blocksPerPeriod(), d.maxTravelPeriods(), d.offerLifetimePeriods(), d.maxItemsPerOrder());
        try (Rig r = new Rig(temp.resolve("capacity.db"), narrow, 10000)) {
            r.route(32); r.accepted(8, 8); r.accepted(8, 8); long planks = r.stock(r.a, PLANKS);
            assertEquals(1, r.service.settle(r.auth(1), 1).completed());
            assertEquals(1, r.service.settle(r.auth(1), 1).paused());
            assertEquals(planks - 1, r.stock(r.a, PLANKS), "Shared node upkeep is paid once per period");
            assertEquals(1, r.service.settle(r.auth(2), 1).completed());
            assertEquals(2, r.service.completedAfter(0, 10).size());
            assertThrows(IllegalArgumentException.class, () -> r.service.settle(r.auth(3), d.maxBatch() + 1));
        }
    }

    @Test void maintenanceShortageHaltsTheRouteAndRefillingOnlyResumesNextPeriod() throws Exception {
        try (Rig r = rig()) {
            r.route(32); Order o = r.accepted(10, 5); r.store.stock(r.a.cityId(), PLANKS, 0);
            assertEquals(1, r.service.settle(r.auth(1), 10).paused()); assertEquals(0, r.service.order(o.id()).orElseThrow().progressedPeriods());
            r.store.stock(r.a.cityId(), PLANKS, 10);
            assertEquals(0, r.service.settle(r.auth(1), 10).inspected());
            assertEquals(1, r.service.settle(r.auth(2), 10).completed());
        }
    }

    @Test void creatingParallelRoutesCannotMultiplySharedNodeCapacity() throws Exception {
        LogisticsDefinitions d = LogisticsDefinitions.defaults(); Map<NodeType, LogisticsDefinitions.NodeSpec> specs = new EnumMap<>(d.nodes());
        var road = specs.get(NodeType.ROAD); specs.put(NodeType.ROAD, new LogisticsDefinitions.NodeSpec(road.name(), road.anchor(), 20, road.timeFactor(), road.construction(), road.maintenance()));
        var narrow = new LogisticsDefinitions(specs, d.terrainTime(), 24, 12, 16, 100, 50, 128, 1000, 24, 16);
        try (Rig r = new Rig(temp.resolve("parallel.db"), narrow, 10000)) {
            r.route(32); r.accepted(8, 8);
            r.route = r.service.createRoute(UUID.randomUUID(), r.a.cityId(), r.b.cityId(), List.of(r.originNode, r.targetNode), Mode.LAND, r.auth(0, r.a.cityId())).id();
            r.accepted(8, 8); BatchResult result = r.service.settle(r.auth(1), 10);
            assertEquals(1, result.completed()); assertEquals(1, result.paused());
            assertEquals(1, r.service.settle(r.auth(2), 10).completed());
        }
    }

    @Test void failedReceiptInsertRollsBackMaintenanceCargoDeliveryProgressAndClock() throws Exception {
        try (Rig r = rig()) {
            r.route(32); Order o = r.accepted(20, 5); long planks = r.stock(r.a, PLANKS);
            r.store.execute("CREATE TRIGGER logistics_test_fail BEFORE INSERT ON logistics_receipt BEGIN SELECT RAISE(ABORT,'fixture receipt failure'); END");
            assertThrows(SQLException.class, () -> r.service.settle(r.auth(1), 10));
            assertEquals(planks, r.stock(r.a, PLANKS)); assertEquals(980, r.stock(r.a, BREAD)); assertEquals(1000, r.stock(r.b, BREAD));
            assertEquals(995, r.stock(r.b, IRON)); assertEquals(0, r.service.order(o.id()).orElseThrow().progressedPeriods()); assertTrue(r.service.completedAfter(0, 10).isEmpty());
            r.store.execute("DROP TRIGGER logistics_test_fail"); assertEquals(1, r.service.settle(r.auth(1), 10).completed());
        }
    }

    @Test void capturedBatchDoesNotReplaceCancelledOrderWithAnUncapturedPath() throws Exception {
        try (Rig r = rig()) {
            r.route(32); Order first = r.accepted(20, 5), second = r.accepted(20, 5);
            r.service.cancel(first.id(), r.auth(1, r.a.cityId()));
            assertEquals(0, r.service.settleCaptured(r.auth(1), List.of(first.id())).inspected());
            assertEquals(OrderStatus.IN_TRANSIT, r.service.order(second.id()).orElseThrow().status());
            assertEquals(1, r.service.settleCaptured(r.auth(1), List.of(second.id())).completed());
        }
    }

    @Test void reducedCityWarehouseCapacityPausesOnlyThatCargoAndCanResumeAfterExpansion() throws Exception {
        try (Rig r = rig()) {
            r.route(32); Order o = r.accepted(20, 5); r.store.cityCapacity(r.b.cityId(), 1000);
            assertEquals(1, r.service.settle(r.auth(1), 10).paused());
            assertEquals(OrderStatus.PAUSED, r.service.order(o.id()).orElseThrow().status());
            r.store.cityCapacity(r.b.cityId(), 1100); assertEquals(1, r.service.settle(r.auth(2), 10).completed());
        }
    }

    @Test void fullDestinationRetainsEscrowAndFailedBilateralRefundRollsBackBothSides() throws Exception {
        try (Rig r = new Rig(temp.resolve("full.db"), LogisticsDefinitions.defaults(), 1100)) {
            r.route(32); Order o = r.accepted(20, 5); r.store.stock(r.b.cityId(), BREAD, 1100);
            assertEquals(1, r.service.settle(r.auth(1), 10).paused());
            assertEquals(980, r.stock(r.a, BREAD)); assertTrue(r.service.completedAfter(0, 10).isEmpty());
            r.store.stock(r.b.cityId(), IRON, 1100);
            assertThrows(SQLException.class, () -> r.service.cancel(o.id(), r.auth(1, r.a.cityId())));
            assertEquals(980, r.stock(r.a, BREAD), "First refund must roll back if second warehouse is full");
            assertEquals(OrderStatus.PAUSED, r.service.order(o.id()).orElseThrow().status());
            r.store.stock(r.b.cityId(), IRON, 995); r.service.cancel(o.id(), r.auth(1, r.b.cityId()));
            assertEquals(1000, r.stock(r.a, BREAD)); assertEquals(1000, r.stock(r.b, IRON));
            r.service.cancel(o.id(), r.auth(1, r.b.cityId())); assertEquals(1000, r.stock(r.a, BREAD));
        }
    }

    @Test void expiredOfferWaitsForRefundCapacityAndCannotBeAcceptedOrCreditedTwice() throws Exception {
        try (Rig r = new Rig(temp.resolve("expired.db"), LogisticsDefinitions.defaults(), 1100)) {
            r.route(32); Order o = r.offer(0, 20, 5); r.store.stock(r.a.cityId(), BREAD, 1100);
            assertThrows(SQLException.class, () -> r.service.accept(o.id(), r.auth(24, r.b.cityId())));
            assertEquals(0, r.service.settle(r.auth(24), 10).expired());
            assertEquals(OrderStatus.OFFERED, r.service.order(o.id()).orElseThrow().status());
            r.store.stock(r.a.cityId(), BREAD, 1000); assertEquals(1, r.service.settle(r.auth(25), 10).expired());
            assertEquals(1020, r.stock(r.a, BREAD)); assertEquals(0, r.service.settle(r.auth(26), 10).expired());
        }
    }

    @Test void supplyDemandReadsEscrowAdjustedWarehouseAndManagerSetsDesiredStock() throws Exception {
        try (Rig r = rig()) {
            r.route(32); r.service.setDemand(r.a.cityId(), BREAD, 990, r.auth(0, r.a.cityId()));
            assertEquals(10, r.service.supplyDemand(r.a.cityId(), 100, 0).stream().filter(q -> q.item().equals(BREAD)).findFirst().orElseThrow().surplus());
            r.offer(0, 20, 5);
            SupplyDemand quote = r.service.supplyDemand(r.a.cityId(), 100, 0).stream().filter(q -> q.item().equals(BREAD)).findFirst().orElseThrow();
            assertEquals(980, quote.available()); assertEquals(10, quote.shortage());
            assertThrows(SQLException.class, () -> r.service.setDemand(r.a.cityId(), BREAD, 1, r.auth(0, r.b.cityId())));
        }
    }

    @Test void stalePeriodAndInvalidPhysicalCargoAreRejectedWithoutWarehouseMutation() throws Exception {
        try (Rig r = rig()) {
            r.route(32); r.service.settle(r.auth(10), 10);
            assertThrows(SQLException.class, () -> r.offer(9, 20, 5));
            assertEquals(1000, r.stock(r.a, BREAD));
            assertThrows(IllegalArgumentException.class, () -> r.service.createOffer(UUID.randomUUID(), r.route, Map.of(BREAD, 0L), Map.of(IRON, 1L), r.auth(10, r.a.cityId())));
            assertThrows(SQLException.class, () -> r.offer(10, 10000, 1)); assertEquals(1000, r.stock(r.a, BREAD));
            assertThrows(IllegalArgumentException.class, () -> r.service.nodes(0, 0));
        }
    }
}

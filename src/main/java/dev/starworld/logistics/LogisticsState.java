package dev.starworld.logistics;

import dev.starworld.domain.WorldState.Site;
import java.util.*;

/** Value objects passed between the main-thread authority capture and storage worker. */
public final class LogisticsState {
    private LogisticsState() {}
    public enum NodeType { ROAD, BRIDGE, PORT, PASS }
    public enum Mode { LAND, SEA }
    public enum OrderStatus { OFFERED, IN_TRANSIT, PAUSED, COMPLETED, CANCELLED, EXPIRED }
    public enum RerouteStatus { PENDING, APPLIED, CANCELLED, EXPIRED }

    /**
     * Captured for exactly one current period on the Paper main thread. controlledAnchors means
     * both StarCore city and nation claims AND the configured physical anchor are valid.
     * validNodes means the same checks for persisted nodes, including loaded chunk/anchor checks.
     * No omitted city/node is implicitly trusted. System processing may use an empty managedCities set.
     */
    public record AuthoritySnapshot(UUID actor, long period, Map<UUID, UUID> cityNations,
                                    Set<UUID> managedCities, Set<String> controlledAnchors, Set<UUID> validNodes,
                                    Set<TradePair> permittedTrades) {
        public AuthoritySnapshot {
            Objects.requireNonNull(actor);
            if (period < 0) throw new IllegalArgumentException("Negative period");
            cityNations = Map.copyOf(cityNations); managedCities = Set.copyOf(managedCities);
            controlledAnchors = Set.copyOf(controlledAnchors); validNodes = Set.copyOf(validNodes);
            permittedTrades = Set.copyOf(permittedTrades);
        }
    }
    public record TradePair(UUID first, UUID second) {
        public TradePair {
            Objects.requireNonNull(first); Objects.requireNonNull(second);
            if (first.equals(second) || first.compareTo(second) >= 0) throw new IllegalArgumentException("Use TradePair.of for distinct cities");
        }
        public static TradePair of(UUID a, UUID b) { return a.compareTo(b) < 0 ? new TradePair(a, b) : new TradePair(b, a); }
    }

    public record Node(UUID id, UUID cityId, UUID nationId, NodeType type, Site site,
                       String terrain, int level, boolean enabled) {
        public Node {
            Objects.requireNonNull(id); Objects.requireNonNull(cityId); Objects.requireNonNull(nationId);
            Objects.requireNonNull(type); Objects.requireNonNull(site); Objects.requireNonNull(terrain);
            if (level < 1 || level > 3) throw new IllegalArgumentException("Node level must be 1..3");
        }
    }

    public record Route(UUID id, UUID originCity, UUID targetCity, List<UUID> nodes, Mode mode,
                        boolean enabled, double distanceBlocks, int travelPeriods, long capacity) {
        public Route { nodes = List.copyOf(nodes); }
    }

    /** Read-only planning projection; it never grants route or transit authority. */
    public record RoutePlan(UUID routeId, UUID originCity, UUID targetCity, Mode mode, boolean enabled,
                            double distanceBlocks, int travelPeriods, long capacity, int nodeCount,
                            Map<UUID, Map<String, Long>> maintenancePerPeriod,
                            Map<UUID, Map<String, Long>> standaloneJourneyMaintenance) {
        public RoutePlan {
            Objects.requireNonNull(routeId); Objects.requireNonNull(originCity); Objects.requireNonNull(targetCity);
            Objects.requireNonNull(mode);
            if (!Double.isFinite(distanceBlocks) || distanceBlocks < 0 || travelPeriods < 1 || capacity < 1 || nodeCount < 2)
                throw new IllegalArgumentException("Invalid route planning projection");
            maintenancePerPeriod = immutableCosts(maintenancePerPeriod);
            standaloneJourneyMaintenance = immutableCosts(standaloneJourneyMaintenance);
        }
        private static Map<UUID, Map<String, Long>> immutableCosts(Map<UUID, Map<String, Long>> costs) {
            Map<UUID, Map<String, Long>> result = new HashMap<>();
            costs.forEach((city, values) -> result.put(Objects.requireNonNull(city), Map.copyOf(values)));
            return Map.copyOf(result);
        }
    }

    /** offered comes from origin; requested comes from target. Both remain in escrow in transit. */
    public record Order(UUID id, UUID routeId, UUID originCity, UUID targetCity,
                        UUID originNation, UUID targetNation, Map<String, Long> offered,
                        Map<String, Long> requested, OrderStatus status, long createdPeriod,
                        long expiresPeriod, long lastPeriod, int progressedPeriods, int travelPeriods,
                        long completedPeriod, String reason) {
        public Order { offered = Map.copyOf(offered); requested = Map.copyOf(requested); }
        public long cargoUnits() { return Math.addExact(offered.values().stream().mapToLong(Long::longValue).sum(), requested.values().stream().mapToLong(Long::longValue).sum()); }
    }

    /** Durable sequence cursor for cultural diffusion; each completed order appears exactly once. */
    public record TradeReceipt(long sequence, UUID orderId, UUID originCity, UUID targetCity,
                               UUID originNation, UUID targetNation, long period,
                               Map<String, Long> offered, Map<String, Long> requested) {
        public TradeReceipt { offered = Map.copyOf(offered); requested = Map.copyOf(requested); }
    }
    public record SupplyDemand(UUID cityId, String item, long available, long desired, long surplus, long shortage) {}
    public record BatchResult(long period, int inspected, int progressed, int completed, int paused, int expired) {}

    /** Both cities must consent before accepted escrow starts a different complete journey. */
    public record Reroute(UUID id, UUID orderId, UUID previousRouteId, UUID proposedRouteId, UUID proposerCity,
                          long createdPeriod, long expiresPeriod, RerouteStatus status, long appliedPeriod) {}

    /** Persisted traffic observations only; this is not proof that a chunk, anchor or nation is still valid. */
    public record NodeTraffic(UUID nodeId, UUID cityId, NodeType type, long capacity, long used, long remaining,
                              long maintenancePeriod, boolean maintenancePaid, boolean enabled) {}
    public record TransportStatus(UUID routeId, long period, long capacity, long routeUsed, long available,
                                  List<NodeTraffic> nodes, List<UUID> bottlenecks, int waitingOrders,
                                  int pausedOrders, Map<String, Integer> pausedReasons, boolean routeEnabled) {
        public TransportStatus { nodes=List.copyOf(nodes);bottlenecks=List.copyOf(bottlenecks);pausedReasons=Map.copyOf(pausedReasons); }
    }
}

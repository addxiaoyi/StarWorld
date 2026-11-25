package dev.starworld.logistics;

import java.util.*;
import java.util.regex.Pattern;

/** Immutable, Bukkit-free configuration. Amounts refer to the StarWorld physical warehouse. */
public record LogisticsDefinitions(Map<LogisticsState.NodeType, NodeSpec> nodes,
                                   Map<String, Double> terrainTime,
                                   int maxNodesPerCity, int maxRoutesPerCity, int maxWaypoints,
                                   int maxOpenOrdersPerCity, int maxBatch, double blocksPerPeriod,
                                   int maxTravelPeriods, int offerLifetimePeriods, int maxItemsPerOrder) {
    private static final Pattern ITEM = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    public LogisticsDefinitions {
        nodes = Map.copyOf(nodes); terrainTime = Map.copyOf(terrainTime);
        if (!nodes.keySet().containsAll(EnumSet.allOf(LogisticsState.NodeType.class)))
            throw new IllegalArgumentException("All four transport node types must be configured");
        terrainTime.forEach((id, value) -> { if (id.isBlank() || !Double.isFinite(value) || value <= 0 || value > 100) throw new IllegalArgumentException("Invalid terrain travel factor"); });
        if (maxNodesPerCity < 1 || maxNodesPerCity > 1000 || maxRoutesPerCity < 1 || maxRoutesPerCity > 1000
                || maxWaypoints < 2 || maxWaypoints > 64 || maxOpenOrdersPerCity < 1 || maxOpenOrdersPerCity > 10000
                || maxBatch < 1 || maxBatch > 1000 || !Double.isFinite(blocksPerPeriod) || blocksPerPeriod <= 0
                || maxTravelPeriods < 1 || maxTravelPeriods > 100000 || offerLifetimePeriods < 1 || offerLifetimePeriods > 100000
                || maxItemsPerOrder < 1 || maxItemsPerOrder > 64) throw new IllegalArgumentException("Invalid logistics limits");
    }

    public record NodeSpec(String name, String anchor, long capacity, double timeFactor,
                           Map<String, Long> construction, Map<String, Long> maintenance) {
        public NodeSpec {
            Objects.requireNonNull(name); Objects.requireNonNull(anchor);
            if (name.isBlank() || anchor.isBlank() || capacity < 1 || capacity > 1_000_000 || !Double.isFinite(timeFactor) || timeFactor <= 0 || timeFactor > 100)
                throw new IllegalArgumentException("Invalid transport node definition");
            construction = amounts(construction); maintenance = amounts(maintenance);
        }
    }

    public static Map<String, Long> amounts(Map<String, Long> values) {
        Map<String, Long> result = Map.copyOf(values);
        result.forEach((item, amount) -> { if (!ITEM.matcher(item).matches() || amount < 1 || amount > 1_000_000_000L) throw new IllegalArgumentException("Invalid physical cargo: " + item); });
        return result;
    }

    public static LogisticsDefinitions defaults() {
        return new LogisticsDefinitions(Map.of(
                LogisticsState.NodeType.ROAD, new NodeSpec("道路驿站", "LODESTONE", 256, .8, Map.of("minecraft:oak_planks", 16L), Map.of("minecraft:oak_planks", 1L)),
                LogisticsState.NodeType.BRIDGE, new NodeSpec("桥梁", "CHISELED_STONE_BRICKS", 192, .9, Map.of("minecraft:oak_planks", 24L, "minecraft:iron_ingot", 4L), Map.of("minecraft:oak_planks", 1L)),
                LogisticsState.NodeType.PORT, new NodeSpec("港口", "BARREL", 512, .65, Map.of("minecraft:oak_planks", 32L, "minecraft:iron_ingot", 8L), Map.of("minecraft:oak_planks", 2L)),
                LogisticsState.NodeType.PASS, new NodeSpec("关隘", "POLISHED_ANDESITE", 128, .75, Map.of("minecraft:oak_planks", 16L, "minecraft:iron_ingot", 8L), Map.of("minecraft:iron_ingot", 1L))),
                Map.of("plains", 1.0, "mountains", 2.2, "forest", 1.4, "desert", 1.6, "snow", 2.0, "river", 1.8),
                24, 12, 16, 100, 50, 128, 1000, 24, 16);
    }
}

package dev.starworld.logistics;

import dev.starworld.logistics.LogisticsDefinitions.NodeSpec;
import dev.starworld.logistics.LogisticsState.NodeType;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/** Read logistics.yml on the main thread before creating LogisticsService. Missing keys use defaults. */
public final class LogisticsConfig {
    private LogisticsConfig() {}

    public static LogisticsDefinitions load(ConfigurationSection yaml) {
        Objects.requireNonNull(yaml); LogisticsDefinitions d = LogisticsDefinitions.defaults();
        if (integer(yaml, "schema-version", 1) != 1) throw new IllegalArgumentException("Unsupported logistics.yml schema-version");
        Map<NodeType, NodeSpec> nodes = new EnumMap<>(NodeType.class); nodes.putAll(d.nodes());
        ConfigurationSection configured = yaml.getConfigurationSection("nodes");
        if (configured != null) for (String key : configured.getKeys(false)) {
            NodeType type;
            try { type = NodeType.valueOf(key.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown logistics node type: " + key); }
            ConfigurationSection section = configured.getConfigurationSection(key);
            if (section == null) throw new IllegalArgumentException("Node must be a mapping: " + key);
            NodeSpec original = d.nodes().get(type);
            nodes.put(type, new NodeSpec(text(section, "name", original.name()), text(section, "anchor", original.anchor()),
                    integer(section, "capacity", original.capacity()), decimal(section, "time-factor", original.timeFactor()),
                    amounts(section, "construction", original.construction()), amounts(section, "maintenance", original.maintenance())));
        }
        Map<String, Double> terrain = new HashMap<>(d.terrainTime());
        ConfigurationSection terrainSection = yaml.getConfigurationSection("terrain-time");
        if (terrainSection != null) for (String key : terrainSection.getKeys(false)) terrain.put(key, decimal(terrainSection, key, 1));
        return new LogisticsDefinitions(nodes, terrain,
                intValue(yaml, "limits.nodes-per-city", d.maxNodesPerCity()), intValue(yaml, "limits.routes-per-city", d.maxRoutesPerCity()),
                intValue(yaml, "limits.waypoints", d.maxWaypoints()), intValue(yaml, "limits.open-orders-per-city", d.maxOpenOrdersPerCity()),
                intValue(yaml, "limits.batch", d.maxBatch()), decimal(yaml, "blocks-per-period", d.blocksPerPeriod()),
                intValue(yaml, "limits.travel-periods", d.maxTravelPeriods()), intValue(yaml, "offer-lifetime-periods", d.offerLifetimePeriods()),
                intValue(yaml, "limits.items-per-order", d.maxItemsPerOrder()));
    }

    private static Map<String, Long> amounts(ConfigurationSection section, String key, Map<String, Long> fallback) {
        if (!section.contains(key)) return fallback;
        ConfigurationSection mapping = section.getConfigurationSection(key);
        if (mapping == null) throw new IllegalArgumentException("Expected cargo mapping: " + key);
        Map<String, Long> values = new HashMap<>(); for (String item : mapping.getKeys(true)){if(mapping.isConfigurationSection(item)){if(mapping.getConfigurationSection(item).getKeys(false).isEmpty())throw new IllegalArgumentException("Empty cargo amount mapping: "+item);continue;}values.put(item, integer(mapping, item, -1));}
        return LogisticsDefinitions.amounts(values);
    }
    private static String text(ConfigurationSection yaml, String path, String fallback) {
        Object value = yaml.get(path, fallback); if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("Expected text: " + path); return s;
    }
    private static int intValue(ConfigurationSection yaml, String path, int fallback) { return Math.toIntExact(integer(yaml, path, fallback)); }
    private static long integer(ConfigurationSection yaml, String path, long fallback) {
        Object value = yaml.get(path, fallback);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue()) throw new IllegalArgumentException("Expected integer: " + path);
        return n.longValue();
    }
    private static double decimal(ConfigurationSection yaml, String path, double fallback) {
        Object value = yaml.get(path, fallback); if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("Expected number: " + path); return n.doubleValue();
    }
}

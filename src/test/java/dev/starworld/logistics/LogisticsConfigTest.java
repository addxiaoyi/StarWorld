package dev.starworld.logistics;

import dev.starworld.logistics.LogisticsState.NodeType;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsConfigTest {
    @Test void defaultsAreImmutableAndPartialConfigurationOverridesOnlySpecifiedValues() throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString("schema-version: 1\nblocks-per-period: 256\nnodes:\n  road:\n    capacity: 100\n    maintenance: {}\nterrain-time:\n  mountains: 3.0\n");
        var d = LogisticsConfig.load(yaml);
        assertEquals(256, d.blocksPerPeriod()); assertEquals(100, d.nodes().get(NodeType.ROAD).capacity());
        assertEquals(Map.of(), d.nodes().get(NodeType.ROAD).maintenance()); assertEquals(3, d.terrainTime().get("mountains"));
        assertEquals(512, d.nodes().get(NodeType.PORT).capacity());
        assertThrows(UnsupportedOperationException.class, () -> d.terrainTime().put("plains", 5.0));
    }
    @Test void invalidVersionAmountsAndLimitsFailBeforePluginStarts() throws Exception {
        for (String content : new String[]{"schema-version: 99", "limits:\n  batch: 0", "nodes:\n  road:\n    capacity: 1.5", "nodes:\n  dragon: {}", "nodes:\n  road:\n    construction:\n      minecraft:bread: -1", "terrain-time:\n  plains: .NaN"}) {
            var yaml = new YamlConfiguration(); yaml.loadFromString(content);
            assertThrows(IllegalArgumentException.class, () -> LogisticsConfig.load(yaml), content);
        }
    }
}

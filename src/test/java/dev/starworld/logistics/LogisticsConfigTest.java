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
    @Test void dottedAndSlashedCustomItemIdsRemainLeafCargoKeysAfterActualConfigurationParse()throws Exception{
        String item="server.food:grains/wheat.flour";String content="schema-version: 1\nnodes:\n  road:\n    construction: {\""+item+"\": 3}\n    maintenance: {\""+item+"\": 2}\n";
        var yaml=new YamlConfiguration();yaml.loadFromString(content);var config=LogisticsConfig.load(yaml);var road=config.nodes().get(NodeType.ROAD);
        assertEquals(Map.of(item,3L),road.construction());assertEquals(Map.of(item,2L),road.maintenance());
        yaml.loadFromString(content.replace(": 3",": 1.5"));assertThrows(IllegalArgumentException.class,()->LogisticsConfig.load(yaml));
    }
}

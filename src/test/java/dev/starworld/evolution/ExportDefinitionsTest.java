package dev.starworld.evolution;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExportDefinitionsTest {
    @TempDir Path temp;
    @Test void dottedSlashedPhysicalExportItemsLoadFromRealConfiguredYaml()throws Exception{
        Path file=Path.of("src","main","resources","exports.yml"),copy=temp.resolve("literal-exports.yml");String source=Files.readString(file);String item="server.food:grains/wheat.flour";
        String withCost=source.replace("costs: {minecraft:wheat: 32}","costs: {minecraft:wheat: 32, \""+item+"\": 2}")
                .replace("costs: {minecraft:arrow: 64, minecraft:shield: 2}","costs: {minecraft:arrow: 64, minecraft:shield: 2, \""+item+"\": 3}");
        assertNotEquals(source,withCost,"Both CE-bearing contracts should be present in the fixture");Files.writeString(copy,withCost);
        var loaded=ExportDefinitions.load(copy.toFile());assertEquals(2L,loaded.contracts().get("grain").costs().get(item));assertEquals(3L,loaded.contracts().get("armaments").costs().get(item));assertEquals(2L,loaded.contracts().get("grain").cost(1).get(item));
    }
    @Test void fractionalOrNegativeDottedExportAmountsRemainRejected()throws Exception{
        String item="server.food:grains/wheat.flour";String source=Files.readString(Path.of("src","main","resources","exports.yml"));
        for(String amount:List.of("-1","1.5")){Path file=temp.resolve("invalid-export-"+amount.replace('.','_')+".yml");Files.writeString(file,source.replace("costs: {minecraft:wheat: 32}","costs: {\""+item+"\": "+amount+"}"));assertThrows(Exception.class,()->ExportDefinitions.load(file.toFile()),amount);}
    }
}

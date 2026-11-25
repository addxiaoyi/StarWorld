package dev.starworld.civic;

import dev.starworld.civic.CivicNeedsDefinitions.Need;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CivicNeedsDefinitionsTest {
    @TempDir Path temp;
    @Test void defaultsRoundTripAndInstallationNeverOverwritesAdministratorSettings() throws Exception {
        Path file=temp.resolve("nested/civic-needs.yml");CivicNeedsDefinitions.writeDefaults(file.toFile());
        CivicNeedsDefinitions d=CivicNeedsDefinitions.load(file.toFile());assertEquals(CivicNeedsDefinitions.defaults(),d);
        assertEquals(3,d.needs().size());assertEquals(Set.of("minecraft:wheat","minecraft:bread","minecraft:oak_planks","minecraft:paper"),d.itemIds());
        Files.writeString(file,Files.readString(file)+"\n# keep administrator changes\n");CivicNeedsDefinitions.writeDefaults(file.toFile());assertTrue(Files.readString(file).endsWith("# keep administrator changes\n"));
        assertThrows(UnsupportedOperationException.class,()->d.needs().clear());assertThrows(UnsupportedOperationException.class,()->d.needs().get("community_meals").costs().clear());
    }
    @Test void strictSchemaUnknownKeysFractionsAndMissingDefinitionsAreRejected() throws Exception {
        Path file=temp.resolve("civic.yml");CivicNeedsDefinitions.writeDefaults(file.toFile());String original=Files.readString(file);
        for(String invalid:List.of(original.replace("civic-needs-version: 1","civic-needs-version: 999"),
                original.replace("maximum-page: 32","maximum-page: 2.5"),original.replace("maximum-page: 32","maximum-page: 2147483648"),
                original.replace("history-limit: 200","history-limit: 9"),original.replace("history-limit: 200","history-limit: 2001"),
                original+"unknown-key: true\n",original.replace("minecraft:wheat: 16","minecraft:wheat: 1.5"),
                original.replace("distinct-sources: false","distinct-sources: 'false'"),
                original.replace("lifetime-periods: 12","lifetime-periods: 11"),original.replace("cooldown-periods: 24","cooldown-periods: 241"),
                original.replace("action-count: 1","action-count: 5"),original.replace("stability-reward: 2","stability-reward: 3"),
                original.replace("prosperity-reward: 3","prosperity-reward: 4"),original.replace("minecraft:wheat: 16","invalid-item: 16"))) {
            Files.writeString(file,invalid);assertThrows(IllegalArgumentException.class,()->CivicNeedsDefinitions.load(file.toFile()));
        }
        Files.writeString(file,original);YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file.toFile());yaml.set("needs.festival",null);yaml.save(file.toFile());assertThrows(IllegalArgumentException.class,()->CivicNeedsDefinitions.load(file.toFile()));
    }
    @Test void definitionsCannotTurnRegistrationIntoEvidenceOrGrantMoneyOrUnboundedRewards() {
        assertThrows(IllegalArgumentException.class,()->new Need("public_spaces","空间",24,48,Map.of("minecraft:oak_planks",16L),"decoration",1,true,1,3));
        assertThrows(IllegalArgumentException.class,()->new Need("public_spaces","空间",24,48,Map.of("minecraft:oak_planks",16L),"decoration",2,false,1,3));
        assertThrows(IllegalArgumentException.class,()->new Need("festival","节庆",18,36,Map.of(),"concert",1,false,2,2));
        assertThrows(IllegalArgumentException.class,()->new Need("community_meals","聚餐",12,24,Map.of("minecraft:bread",4L),"register",1,false,2,1));
        assertThrows(IllegalArgumentException.class,()->new Need("festival","节庆",18,36,Map.of("minecraft:paper",8L),"concert",1,false,Double.NaN,2));
        assertThrows(IllegalArgumentException.class,()->new CivicNeedsDefinitions(1,129,0,CivicNeedsDefinitions.defaults().needs()));
        assertThrows(IllegalArgumentException.class,()->new CivicNeedsDefinitions(2,32,0,CivicNeedsDefinitions.defaults().needs()));
    }
    @Test void optionalFurnitureDegradesExplicitlyWhileVanillaFeastAndConcertStayAvailable() {
        CivicNeedsDefinitions d=CivicNeedsDefinitions.defaults();Map<String,String> statuses=d.availability(d.itemIds(),false);
        assertTrue(statuses.get("public_spaces").contains("真实家具维护凭据"));assertTrue(statuses.get("community_meals").startsWith("可用"));assertTrue(statuses.get("festival").startsWith("可用"));
        assertTrue(d.availability(Set.of(),true).values().stream().allMatch(s->s.startsWith("停用")));
    }
    @Test void namespacedCustomMaterialIdsWithDotsAndSlashesRoundTripAsLiteralKeys() throws Exception {
        Path file=temp.resolve("custom.yml");CivicNeedsDefinitions.writeDefaults(file.toFile());String yaml=Files.readString(file).replace("minecraft:wheat: 16","server.food:grains/wheat: 16");Files.writeString(file,yaml);
        CivicNeedsDefinitions definitions=CivicNeedsDefinitions.load(file.toFile());assertEquals(16,definitions.needs().get("community_meals").costs().get("server.food:grains/wheat"));assertTrue(definitions.itemIds().contains("server.food:grains/wheat"));
    }
}

package dev.starworld.life;

import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LifeDefinitionsTest {
    @TempDir Path temp;
    private static String replaceFirst(String text,String expected,String replacement){int index=text.indexOf(expected);assertTrue(index>=0,"Missing fixture fragment: "+expected);return text.substring(0,index)+replacement+text.substring(index+expected.length());}
    @Test void completeDefaultsRoundTripWithoutOverwritingAdministratorDefinitions()throws Exception {
        Path path=temp.resolve("nested/life.yml");LifeDefinitions.writeDefaults(path.toFile());
        assertEquals(LifeDefinitions.defaults(),LifeDefinitions.load(path.toFile()));String content=Files.readString(path);Files.writeString(path,content+"\n# administrator marker\n");
        assertEquals(LifeDefinitions.defaults(),LifeDefinitions.load(Path.of("examples","life.yml").toFile()));
        LifeDefinitions.writeDefaults(path.toFile());assertTrue(Files.readString(path).endsWith("# administrator marker\n"));
        assertTrue(LifeDefinitions.defaults().itemIds().contains("musicplus:handheld_instrument"));
        assertTrue(LifeDefinitions.defaults().feasts().get("cookery").inputs().containsKey("kaleidoscopecookery:baozi"));
        assertEquals(Map.of("kaleidoscope_tavern:wine",1L,"minecraft:bread",4L),LifeDefinitions.defaults().feasts().get("tavern").inputs(),"Use the actual catalog output item, not its barrel/wine recipe ID");
    }
    @Test void unknownVersionProfessionFractionalInputsAndUnboundedBonusAreRejected()throws Exception {
        Path path=temp.resolve("life.yml");LifeDefinitions.writeDefaults(path.toFile());YamlConfiguration y=YamlConfiguration.loadConfiguration(path.toFile());
        y.set("life-version",999);y.save(path.toFile());assertThrows(IllegalArgumentException.class,()->LifeDefinitions.load(path.toFile()));
        y.set("life-version",1);y.set("feasts.community.profession","unknown");y.save(path.toFile());assertThrows(IllegalArgumentException.class,()->LifeDefinitions.load(path.toFile()));
        y.set("feasts.community.profession","chef");y.set("feasts.community.inputs.minecraft:bread",1.5);y.save(path.toFile());assertThrows(IllegalArgumentException.class,()->LifeDefinitions.load(path.toFile()));
        y.set("feasts.community.inputs.minecraft:bread",8);y.set("maximum-production-bonus",Double.NaN);y.save(path.toFile());assertThrows(IllegalArgumentException.class,()->LifeDefinitions.load(path.toFile()));
    }
    @Test void freeActionsAndImpossibleConcertLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new LifeDefinitions.Feast("free","免费","chef",Map.of(),1,.1,1));
        assertThrows(IllegalArgumentException.class,()->new LifeDefinitions.DecorationRules("artisan",Set.of("bad"),Map.of("minecraft:bread",1L),Map.of("minecraft:bread",1L),1,1,.1));
        assertThrows(IllegalArgumentException.class,()->new LifeDefinitions.ConcertRules("musician",Set.of("minecraft:note_block"),Map.of("minecraft:bread",1L),5000,6000,1000,2000,10,10,5,1,1,.1,1,1));
    }

    @Test void dottedSlashedItemsSurviveActualFeastDecorationAndConcertYaml()throws Exception{
        Path path=temp.resolve("literal-ce-life.yml");String item="server.food:grains/wheat.flour";
        String yaml=Files.readString(Path.of("examples","life.yml"));yaml=replaceFirst(yaml,"inputs: {minecraft:bread: 8}","inputs: {minecraft:bread: 8, \""+item+"\": 2}")
                .replace("registration-cost: {minecraft:oak_planks: 4}","registration-cost: {minecraft:oak_planks: 4, \""+item+"\": 3}")
                .replace("upkeep: {minecraft:bread: 1}","upkeep: {minecraft:bread: 1, \""+item+"\": 4}")
                .replace("instruments: [minecraft:note_block, musicplus:handheld_instrument]","instruments: [minecraft:note_block, musicplus:handheld_instrument, \"server.music:instrument/solo.flute\"]")
                ;yaml=replaceFirst(yaml,"inputs: {minecraft:bread: 8}","inputs: {minecraft:bread: 8, \""+item+"\": 5}");
        Files.writeString(path,yaml);LifeDefinitions loaded=LifeDefinitions.load(path.toFile());
        assertEquals(2L,loaded.feasts().get("community").inputs().get(item));assertEquals(3L,loaded.decorations().registrationCost().get(item));assertEquals(4L,loaded.decorations().upkeep().get(item));
        assertEquals(5L,loaded.concerts().inputs().get(item));assertTrue(loaded.concerts().instruments().contains("server.music:instrument/solo.flute"));assertTrue(loaded.itemIds().contains(item));
    }
}

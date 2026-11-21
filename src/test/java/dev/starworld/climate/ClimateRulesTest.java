package dev.starworld.climate;

import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.Building;
import dev.starworld.domain.WorldState.Site;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;
import static org.junit.jupiter.api.Assertions.*;

class ClimateRulesTest {
    @TempDir Path temp;

    private static final UUID NATION = UUID.randomUUID();
    private static final Site SITE = new Site(UUID.randomUUID(), "world", 0, 64, 0);
    private static final Building FARM = new Building(UUID.randomUUID(), UUID.randomUUID(), NATION, "farm", SITE, 1);
    private static final Building WORKSHOP = new Building(UUID.randomUUID(), UUID.randomUUID(), NATION, "workshop", SITE, 1);

    private static final Definitions DEFINITIONS = new Definitions(300, 12, 20, 100_000, 0,
            Map.of("plains", new Definitions.Terrain("plains", "Plains", 1, 1, 1,
                    Map.of("fertility", 10L, "ore", 10L), Map.of("fertility", 1L))),
            Map.of("agrarian", new Definitions.Civilization("agrarian", "Agrarian", Map.of())),
            Map.of("farm", new Definitions.BuildingType("farm", "Farm", "COMPOSTER", Map.of()),
                    "workshop", new Definitions.BuildingType("workshop", "Workshop", "BLAST_FURNACE", Map.of())),
            List.of(new Definitions.Recipe("harvest", "farm", Map.of(), Map.of("minecraft:wheat", 1L), "fertility", 1),
                    new Definitions.Recipe("bake", "farm", Map.of("minecraft:wheat", 1L), Map.of("minecraft:bread", 1L), null, 0),
                    new Definitions.Recipe("mine", "workshop", Map.of(), Map.of("minecraft:raw_iron", 1L), "ore", 1),
                    new Definitions.Recipe("smelt", "workshop", Map.of("minecraft:raw_iron", 1L), Map.of("minecraft:iron_ingot", 1L), null, 0),
                    new Definitions.Recipe("forge", "workshop", Map.of("minecraft:iron_ingot", 1L), Map.of("minecraft:arrow", 1L), null, 0)),
            Map.of());

    private static final Definitions.Recipe HARVEST = recipe("harvest");
    private static final Definitions.Recipe BAKE = recipe("bake");
    private static final Definitions.Recipe MINE = recipe("mine");
    private static final Definitions.Recipe SMELT = recipe("smelt");
    private static final Definitions.Recipe FORGE = recipe("forge");

    private static Definitions.Recipe recipe(String id) {
        return DEFINITIONS.recipes().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private static ClimateRules.WeatherSnapshot weather(UUID owner, String id, Map<String, Double> modifiers) {
        return new ClimateRules.WeatherSnapshot("world", owner, id, modifiers);
    }

    @Test void nullOrDifferentControllerCannotInfluenceBuilding() {
        ClimateRules rules = new ClimateRules(ClimateDefinitions.defaults());
        var capture = weather(UUID.randomUUID(), "rain", Map.of("agricultural", 1.4));
        assertEquals(1.0, rules.factor(FARM, HARVEST, capture));
        assertEquals(1.0, rules.factor(FARM, HARVEST, weather(null, "rain", Map.of("agricultural", 1.4))));
    }

    @Test void onlyKnownWeatherKeysMapToTheirDocumentedProductionChains() {
        ClimateRules rules = new ClimateRules(ClimateDefinitions.defaults());
        assertEquals(1.2, rules.factor(FARM, HARVEST, weather(NATION, "rain", Map.of("agricultural", 1.2))));
        assertEquals(1.2, rules.factor(FARM, BAKE, weather(NATION, "rain", Map.of("agricultural", 1.2))),
                "All farm recipes share the agriculture factor");
        assertEquals(1.3, rules.factor(WORKSHOP, MINE, weather(NATION, "rain", Map.of("mineral", 1.3))));
        assertEquals(1.0, rules.factor(WORKSHOP, SMELT, weather(NATION, "rain", Map.of("mineral", 1.3))));
        assertEquals(1.0, rules.factor(WORKSHOP, FORGE, weather(NATION, "rain", Map.of("mineral", 1.3))));
        assertEquals(1.0, rules.factor(FARM, HARVEST, weather(NATION, "rain", Map.of("energy", 1.4))));
        assertEquals(1.2,rules.factor(FARM,HARVEST,weather(NATION,"rain",Map.of("agricultural",1.2,"energy",Double.NaN))));
        assertEquals(1.0, rules.factor(WORKSHOP, MINE, weather(NATION, "rain", Map.of("timber", 1.4))));
        assertEquals(1.0, rules.factor(FARM, HARVEST, weather(NATION, null, Map.of("agricultural", 1.4))));
        assertEquals(1.0, rules.factor(FARM, HARVEST,new ClimateRules.WeatherSnapshot("other-world",NATION,"rain",Map.of("agricultural",1.4))));
    }

    @Test void documentedWeatherFactorsClampToConfiguredLimits() {
        ClimateRules rules = new ClimateRules(ClimateDefinitions.defaults());
        assertEquals(0.5, rules.factor(FARM, HARVEST, weather(NATION, "cold", Map.of("agricultural", 0.1))));
        assertEquals(1.5, rules.factor(WORKSHOP, MINE, weather(NATION, "clear", Map.of("mineral", 2.0))));
    }

    @Test void maintainedIrrigationMitigatesOnlyAdverseFarmWeatherAndNeverAddsAWeatherBonus() {
        ClimateRules rules = new ClimateRules(ClimateDefinitions.defaults());
        assertEquals(0.75,rules.factor(FARM,HARVEST,weather(NATION,"snow",Map.of("agricultural",0.2)),Map.of(),true));
        assertEquals(1.2,rules.factor(FARM,HARVEST,weather(NATION,"clear",Map.of("agricultural",1.2)),Map.of(),true));
        assertEquals(1.3,rules.factor(WORKSHOP,MINE,weather(NATION,"rain",Map.of("mineral",1.3)),Map.of(),true));
    }

    @Test void rejectsNonFiniteAndOutOfRangeCapturedWeatherValues() {
        ClimateRules rules = new ClimateRules(ClimateDefinitions.defaults());
        for (double value : List.of(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 10.01)) {
            var snapshot = weather(NATION, "storm", Map.of("agricultural", value));
            assertThrows(IllegalArgumentException.class, () -> rules.factor(FARM, HARVEST, snapshot));
        }
    }

    @Test void typedTechnologyRequiresExplicitExactRecipeMappingAndBoundedModifier() {
        var mapping = new ClimateDefinitions.TechnologyMapping("resource_yield", "workshop", "mine");
        ClimateDefinitions configured = new ClimateDefinitions(0, 10, 0.5, 1.5, 0.5, 1.5,
                -0.5, 0.5, Map.of("resource_yield", mapping));
        ClimateRules rules = new ClimateRules(configured);
        var clear = weather(NATION, "clear", Map.of());
        assertEquals(1.2, rules.factor(WORKSHOP, MINE, clear, Map.of("resource_yield", 0.2)));
        assertEquals(1.0, rules.factor(WORKSHOP, SMELT, clear, Map.of("resource_yield", 0.2)),
                "A technology applies only to its exact configured recipe");
        assertEquals(1.0, new ClimateRules(ClimateDefinitions.defaults()).factor(WORKSHOP, MINE, clear,
                Map.of("resource_yield", 0.2)), "Shipped technology mappings default to empty");
        assertThrows(IllegalArgumentException.class, () -> rules.factor(WORKSHOP, MINE, clear,
                Map.of("resource_yield", 0.51)));
        assertThrows(IllegalArgumentException.class, () -> new ClimateDefinitions.TechnologyMapping("production", "farm", "harvest"));
        assertThrows(IllegalArgumentException.class, () -> new ClimateDefinitions.TechnologyMapping("crop_growth", "farm", "harvest"));
    }

    @Test void definitionsRejectUnsafeRangesAndWrongTechnologyTargets() {
        assertThrows(IllegalArgumentException.class, () -> new ClimateDefinitions(0, 10, 1.2, 0.8, 0.5, 1.5,
                -0.5, 0.5, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ClimateDefinitions(0, 11, 0.5, 1.5, 0.5, 1.5,
                -0.5, 0.5, Map.of()));
        assertThrows(IllegalArgumentException.class,()->new ClimateDefinitions(0,10,.5,1.5,.5,1.5,-.5,.5,1.1,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new ClimateDefinitions(0,1,.5,1.5,.5,1.5,-.5,.5,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new ClimateDefinitions(.5,10,.5,1.5,.5,1.5,-.5,.5,Map.of()));

        Path file = temp.resolve("climate.yml");
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/climate.yml"))) {
            Files.copy(input, file);
            var loaded = ClimateDefinitions.load(file.toFile(), DEFINITIONS);
            assertTrue(loaded.technologyMappings().isEmpty());
            String configured = Files.readString(file);
            YamlConfiguration configYaml = YamlConfiguration.loadConfiguration(file.toFile());
            configYaml.set("input.multiplier-maximum", 1.0);
            configYaml.save(file.toFile());
            assertThrows(IllegalArgumentException.class, () -> ClimateDefinitions.load(file.toFile(), DEFINITIONS));
            Files.writeString(file, configured);
            String yaml = Files.readString(file).replace("technology-mappings: {}", "technology-mappings:\n  resource_yield:\n    building: farm\n    recipe: smelt\n");
            Files.writeString(file, yaml);
            assertThrows(IllegalArgumentException.class, () -> ClimateDefinitions.load(file.toFile(), DEFINITIONS));
        } catch (Exception failure) {
            fail(failure);
        }
    }

    @Test void climateDefaultsAreAdditiveAndNeverOverwriteAdministratorConfiguration()throws Exception{
        Path file=temp.resolve("created-climate.yml");ClimateDefinitions.writeDefaults(file.toFile());
        assertEquals(0.5,ClimateDefinitions.load(file.toFile(),DEFINITIONS).adverseAgricultureMitigation());
        Files.writeString(file,"administrator climate settings");ClimateDefinitions.writeDefaults(file.toFile());
        assertEquals("administrator climate settings",Files.readString(file));
    }
}

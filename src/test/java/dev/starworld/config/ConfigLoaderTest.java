package dev.starworld.config;

import dev.starworld.domain.Definitions;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ConfigLoaderTest {
    @TempDir Path temp;
    private Path config, data;
    private void fixtures() throws Exception {
        config = temp.resolve("config.yml"); data = temp.resolve("definitions.yml");
        try (var in = getClass().getResourceAsStream("/config.yml")) { Files.copy(Objects.requireNonNull(in), config); }
        try (var in = getClass().getResourceAsStream("/definitions.yml")) { Files.copy(Objects.requireNonNull(in), data); }
    }
    // Paper 1.21.11 resolves Material flags through its runtime registries. Mock
    // only that platform boundary; YAML, validation and all SQLite logic are real.
    public static Definitions loadWithPlatformFixture(Path config, Path data) throws Exception {
        try (var materials = mockStatic(Material.class)) {
            Material block = mock(Material.class), item = mock(Material.class);
            when(block.isBlock()).thenReturn(true);
            when(item.isItem()).thenReturn(true);
            for (String id : List.of("COMPOSTER", "STONECUTTER", "BLAST_FURNACE")) materials.when(() -> Material.matchMaterial(id)).thenReturn(block);
            materials.when(() -> Material.matchMaterial("STICK")).thenReturn(item);
            return ConfigLoader.load(config.toFile(), data.toFile());
        }
    }
    private Definitions load() throws Exception { return loadWithPlatformFixture(config, data); }
    private void change(String path, Object value) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(data.toFile()); yaml.set(path, value); yaml.save(data.toFile());
    }

    @Test void shippedExampleContainsPlayableSixThreeTwoThreeMinimumAndNamespacedItems() throws Exception {
        fixtures(); Definitions d = load();
        assertEquals(Set.of("mountains", "river", "plains", "forest", "desert", "snow"), d.terrains().keySet());
        assertEquals(Set.of("farm", "sawmill", "workshop"), d.buildings().keySet());
        assertEquals(Set.of("agrarian", "industrial"), d.civilizations().keySet());
        assertEquals(Set.of("drought", "blizzard", "harvest"), d.events().keySet());
        assertEquals(List.of("harvest", "bake", "fell", "saw", "mine", "smelt", "forge"), d.recipes().stream().map(Definitions.Recipe::id).toList());
        assertTrue(ConfigLoader.items(d).containsAll(Set.of("minecraft:wheat", "minecraft:bread", "minecraft:iron_ingot", "minecraft:arrow")));
        assertThrows(UnsupportedOperationException.class, () -> d.terrains().clear());
    }

    @Test void versionOneMigratesOnceWithExactBackupAndPreservesUserTuning() throws Exception {
        fixtures();
        String old = "config-version: 1\nsimulation:\n  period-seconds: 600\n  stock-limit: 4500\n  event-chance: 0\n";
        Files.writeString(config, old, StandardCharsets.UTF_8);
        Definitions d = load();
        assertEquals(600, d.periodSeconds()); assertEquals(4500, d.stockLimit()); assertEquals(20, d.maxCitiesPerPass());
        assertEquals(old, Files.readString(Path.of(config + ".v1.bak")));
        YamlConfiguration migrated = new YamlConfiguration(); migrated.load(config.toFile());
        assertEquals(2, migrated.getInt("config-version"));
        load();
        assertEquals(old, Files.readString(Path.of(config + ".v1.bak")), "Repeated startup must preserve the original migration backup");
    }

    @Test void futureConfigAndDefinitionSchemasFailWithoutOverwritingFiles() throws Exception {
        fixtures();
        String future = "config-version: 99\nsimulation: {}\n"; Files.writeString(config, future);
        assertThrows(IllegalArgumentException.class, this::load);
        assertEquals(future, Files.readString(config));
        Files.writeString(config, "config-version: 2\n"); change("definition-version", 99);
        assertThrows(IllegalArgumentException.class, this::load);
        assertFalse(Files.exists(Path.of(config + ".v1.bak")));
    }

    @Test void invalidRecipeBuildingAndReserveReferencesAreRejected() throws Exception {
        fixtures(); change("recipes.harvest.building", "missing_facility");
        assertThrows(IllegalArgumentException.class, this::load);
        change("recipes.harvest.building", "farm"); change("recipes.harvest.reserve", "unobtainium");
        assertThrows(IllegalArgumentException.class, this::load);
    }

    @Test void unknownEventOrCivilizationMultiplierCannotSilentlyDoNothing() throws Exception {
        fixtures(); change("events.drought.multipliers.ghost", 1.2);
        assertThrows(IllegalArgumentException.class, this::load);
        change("events.drought.multipliers.ghost", null); change("civilizations.agrarian.multipliers.ghost", 1.2);
        assertThrows(IllegalArgumentException.class, this::load);
    }

    @Test void fractionalOrNegativeAmountsAndInvalidRegionRegenerationAreRejected() throws Exception {
        fixtures(); change("recipes.bake.inputs.minecraft:wheat", 1.5);
        assertThrows(IllegalArgumentException.class, this::load);
        change("recipes.bake.inputs.minecraft:wheat", -1);
        assertThrows(IllegalArgumentException.class, this::load);
        change("recipes.bake.inputs.minecraft:wheat", 6); change("terrains.plains.regeneration.unknown", 3);
        assertThrows(IllegalArgumentException.class, this::load);
    }

    @Test void unsafeSimulationBoundsAndNonFiniteMultipliersFailFast() throws Exception {
        fixtures(); Files.writeString(config, "config-version: 2\nsimulation:\n  period-seconds: 1\n");
        assertThrows(IllegalArgumentException.class, this::load);
        Files.writeString(config, "config-version: 2\n"); change("civilizations.agrarian.multipliers.farm", Double.NaN);
        assertThrows(IllegalArgumentException.class, this::load);
    }

    @Test void malformedYamlMissingSectionsAndNonBlockAnchorsAreRejected() throws Exception {
        fixtures(); change("buildings.farm.anchor", "STICK");
        assertThrows(IllegalArgumentException.class, this::load);
        change("buildings.farm.anchor", "COMPOSTER"); change("terrains", null);
        assertThrows(IllegalArgumentException.class, this::load);
        Files.writeString(data, "terrains: [ invalid\n");
        assertThrows(Exception.class, this::load);
    }

    @Test void customCraftEngineDefinitionsRemainConfigurableWithoutOwningTheirProduction() throws Exception {
        fixtures(); change("recipes.bake.outputs", Map.of("kaleidoscopecookery:baozi", 2));
        Definitions d = load();
        assertTrue(ConfigLoader.items(d).contains("kaleidoscopecookery:baozi"));
        assertEquals(Map.of("kaleidoscopecookery:baozi", 2L), d.recipes().stream().filter(r -> r.id().equals("bake")).findFirst().orElseThrow().outputs());
    }
}

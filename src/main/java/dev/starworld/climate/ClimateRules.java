package dev.starworld.climate;

import dev.starworld.domain.Definitions.Recipe;
import dev.starworld.domain.Simulation;
import dev.starworld.domain.WorldState.Building;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Pure production modifiers derived from an immutable StarCore weather/technology capture. */
public final class ClimateRules {
    private final ClimateDefinitions definitions;

    public ClimateRules(ClimateDefinitions definitions) {
        this.definitions = Objects.requireNonNull(definitions);
    }

    /**
     * Weather multiplier for one recipe. A capture from a different or unknown nation is ignored.
     * StarCore values are direct multipliers (for example 1.2 means +20%), not additive bonuses.
     */
    public double factor(Building building, Recipe recipe, WeatherSnapshot snapshot) {
        return factor(building, recipe, snapshot, Map.of());
    }

    /** Applies only configured, typed effect keys to their exact building/recipe pair. */
    public double factor(Building building, Recipe recipe, WeatherSnapshot snapshot,
                         Map<String, Double> typedTechnologyModifiers) {
        return factor(building, recipe, snapshot, typedTechnologyModifiers, false);
    }

    /** A completed and paid irrigation network only mitigates adverse agricultural weather. */
    public double factor(Building building, Recipe recipe, WeatherSnapshot snapshot,
                         Map<String, Double> typedTechnologyModifiers, boolean irrigationOperating) {
        Objects.requireNonNull(building, "building");
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(typedTechnologyModifiers, "typedTechnologyModifiers");
        if (!recipe.building().equals(building.type()))
            throw new IllegalArgumentException("Recipe does not belong to building");
        boolean weatherApplies = building.site().worldName().equals(snapshot.worldName())
                && building.nationId().equals(snapshot.controllerNation());
        double result = weatherApplies ? weatherFactor(building, recipe, snapshot) : 1.0;
        if (weatherApplies && irrigationOperating && building.type().equals("farm") && result < 1.0) {
            double recovered = (1.0 - result) * definitions.adverseAgricultureMitigation();
            result = Math.min(definitions.agricultureMaximum(), result + recovered);
        }
        for (ClimateDefinitions.TechnologyMapping mapping : definitions.technologyMappings().values()) {
            if (!mapping.building().equals(building.type()) || !mapping.recipe().equals(recipe.id())) continue;
            Double modifier = typedTechnologyModifiers.get(mapping.effect());
            // A changing upstream aggregate must not abort this city's whole settlement.
            if (modifier == null || !Double.isFinite(modifier)) continue;
            double adjustment = Math.max(definitions.technologyAdjustmentMinimum(),
                    Math.min(definitions.technologyAdjustmentMaximum(), modifier));
            result *= 1.0 + adjustment;
        }
        return Math.min(Simulation.MAX_RECIPE_MULTIPLIER, result);
    }

    private double weatherFactor(Building building, Recipe recipe, WeatherSnapshot snapshot) {
        String key;
        double minimum;
        double maximum;
        if (building.type().equals("farm")) {
            key = "agricultural";
            minimum = definitions.agricultureMinimum();
            maximum = definitions.agricultureMaximum();
        } else if (building.type().equals("workshop") && recipe.id().equals("mine")
                && "ore".equals(recipe.reserve())) {
            key = "mineral";
            minimum = definitions.mineralMinimum();
            maximum = definitions.mineralMaximum();
        } else {
            return 1.0;
        }

        if (snapshot.weatherId() == null || snapshot.weatherId().isBlank()) return 1.0;
        Double directMultiplier = snapshot.resourceMultipliers().get(key);
        if (directMultiplier == null || !Double.isFinite(directMultiplier)
                || directMultiplier < definitions.inputMinimum() || directMultiplier > definitions.inputMaximum())
            return 1.0;
        return Math.max(minimum, Math.min(maximum, directMultiplier));
    }

    /** Immutable copy of the per-world weather capture; null controller represents unknown ownership. */
    public record WeatherSnapshot(String worldName, UUID controllerNation, String weatherId, Map<String, Double> resourceMultipliers) {
        public WeatherSnapshot {
            Objects.requireNonNull(resourceMultipliers, "resourceMultipliers");
            if (worldName != null && (worldName.isBlank() || worldName.length() > 128))
                throw new IllegalArgumentException("Invalid climate snapshot world");
            if (resourceMultipliers.size() > 64) throw new IllegalArgumentException("Too many weather modifiers");
            resourceMultipliers = Collections.unmodifiableMap(new HashMap<>(resourceMultipliers));
        }
    }
}

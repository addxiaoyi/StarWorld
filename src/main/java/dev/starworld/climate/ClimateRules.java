package dev.starworld.climate;

import dev.starworld.domain.WorldState.Building;
import dev.starworld.domain.Definitions.Recipe;
import java.util.*;

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
        return factor(building,recipe,snapshot,typedTechnologyModifiers,false);
    }

    /** A completed and paid irrigation network only mitigates adverse agricultural weather. */
    public double factor(Building building,Recipe recipe,WeatherSnapshot snapshot,
                         Map<String,Double> typedTechnologyModifiers,boolean irrigationOperating) {
        Objects.requireNonNull(building, "building");
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(typedTechnologyModifiers, "typedTechnologyModifiers");
        if (!recipe.building().equals(building.type()))
            throw new IllegalArgumentException("Recipe does not belong to building");
        boolean weatherApplies=snapshot.worldName()!=null&&snapshot.worldName().equals(building.site().worldName())
                &&snapshot.controllerNation()!=null&&snapshot.controllerNation().equals(building.nationId());
        double result=1.0;
        if(weatherApplies){validateApplicableWeatherData(building,recipe,snapshot);result=weatherFactor(building,recipe,snapshot);
            if(irrigationOperating&&building.type().equals("farm")&&result<1)result+=(1-result)*definitions.adverseAgricultureMitigation();}
        for (var entry : definitions.technologyMappings().entrySet()) {
            ClimateDefinitions.TechnologyMapping mapping = entry.getValue();
            if (!mapping.building().equals(building.type()) || !mapping.recipe().equals(recipe.id())) continue;
            Double modifier = typedTechnologyModifiers.get(mapping.effect());
            if (modifier == null) continue;
            if (!Double.isFinite(modifier) || modifier < definitions.technologyAdjustmentMinimum()
                    || modifier > definitions.technologyAdjustmentMaximum())
                throw new IllegalArgumentException("Out-of-range climate technology modifier: " + mapping.effect());
            result *= 1.0 + modifier;
        }
        if (!Double.isFinite(result) || result < 0 || result > 100)
            throw new IllegalArgumentException("Climate production factor is out of range");
        return result;
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
        if (directMultiplier == null) return 1.0;
        return Math.max(minimum, Math.min(maximum, directMultiplier));
    }

    private void validateApplicableWeatherData(Building building,Recipe recipe,WeatherSnapshot snapshot) {
        String key=building.type().equals("farm")?"agricultural":building.type().equals("workshop")&&recipe.id().equals("mine")&&"ore".equals(recipe.reserve())?"mineral":null;
        if(key==null)return;
        Double value=snapshot.resourceMultipliers().get(key);if(value==null)return;
        if(!Double.isFinite(value)||value<definitions.inputMinimum()||value>definitions.inputMaximum())
            throw new IllegalArgumentException("Out-of-range StarCore weather resource modifier: " + key);
    }

    /** Immutable copy of the per-world weather capture; null controller represents unknown ownership. */
    public record WeatherSnapshot(String worldName,UUID controllerNation, String weatherId, Map<String, Double> resourceMultipliers) {
        public WeatherSnapshot {
            Objects.requireNonNull(resourceMultipliers, "resourceMultipliers");
            if(worldName!=null&&(worldName.isBlank()||worldName.length()>128))throw new IllegalArgumentException("Invalid climate snapshot world");
            if (resourceMultipliers.size() > 64) throw new IllegalArgumentException("Too many weather modifiers");
            resourceMultipliers = Collections.unmodifiableMap(new HashMap<>(resourceMultipliers));
        }
    }
}

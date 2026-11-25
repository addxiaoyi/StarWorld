package dev.starworld.climate;

import dev.starworld.domain.Definitions;
import java.io.File;
import java.io.IOException;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable, bounded rules for applying captured StarCore weather and typed technology effects. */
public record ClimateDefinitions(double inputMinimum, double inputMaximum,
                                 double agricultureMinimum, double agricultureMaximum,
                                 double mineralMinimum, double mineralMaximum,
                                 double technologyAdjustmentMinimum, double technologyAdjustmentMaximum,
                                 double adverseAgricultureMitigation,
                                 Map<String, TechnologyMapping> technologyMappings) {
    private static final Set<String> SAFE_TECHNOLOGY_EFFECTS = Set.of("resource_yield");

    public ClimateDefinitions {
        range(inputMinimum, inputMaximum, 0.0, 10.0, "input");
        // Pinned StarCore WeatherModule has a legitimate agricultural multiplier as low as 0.2 and as high as 1.5.
        if(inputMinimum>0.2||inputMaximum<1.5)
            throw new IllegalArgumentException("Accepted StarCore weather range must include agricultural values 0.2..1.5");
        range(agricultureMinimum, agricultureMaximum, 0.0, 10.0, "agriculture");
        range(mineralMinimum, mineralMaximum, 0.0, 10.0, "mineral");
        range(technologyAdjustmentMinimum, technologyAdjustmentMaximum, -1.0, 2.0, "technology adjustment");
        if(agricultureMinimum<inputMinimum||agricultureMaximum>inputMaximum||mineralMinimum<inputMinimum||mineralMaximum>inputMaximum)
            throw new IllegalArgumentException("Weather climate ranges must fit the accepted StarCore input range");
        if(!Double.isFinite(adverseAgricultureMitigation)||adverseAgricultureMitigation<0||adverseAgricultureMitigation>1)
            throw new IllegalArgumentException("Invalid adverse agriculture mitigation");
        Map<String, TechnologyMapping> copy = new TreeMap<>();
        technologyMappings.forEach((effect, mapping) -> {
            if (!SAFE_TECHNOLOGY_EFFECTS.contains(effect))
                throw new IllegalArgumentException("Unsupported typed technology effect: " + effect);
            if (!effect.equals(mapping.effect())) throw new IllegalArgumentException("Technology mapping key mismatch: " + effect);
            copy.put(effect, mapping);
        });
        if (copy.size() > 16) throw new IllegalArgumentException("Too many climate technology mappings");
        technologyMappings = Collections.unmodifiableMap(copy);
    }

    public ClimateDefinitions(double inputMinimum,double inputMaximum,double agricultureMinimum,double agricultureMaximum,
                              double mineralMinimum,double mineralMaximum,double technologyAdjustmentMinimum,
                              double technologyAdjustmentMaximum,Map<String,TechnologyMapping> technologyMappings){
        this(inputMinimum,inputMaximum,agricultureMinimum,agricultureMaximum,mineralMinimum,mineralMaximum,
                technologyAdjustmentMinimum,technologyAdjustmentMaximum,0.5,technologyMappings);
    }

    public record TechnologyMapping(String effect, String building, String recipe) {
        public TechnologyMapping {
            id(effect, "technology effect");
            id(building, "technology building");
            id(recipe, "technology recipe");
            if (!SAFE_TECHNOLOGY_EFFECTS.contains(effect))
                throw new IllegalArgumentException("Unsupported typed technology effect: " + effect);
        }
    }

    public static ClimateDefinitions defaults() {
        return new ClimateDefinitions(0.0, 10.0, 0.5, 1.5, 0.5, 1.5, -0.5, 0.5, 0.5, Map.of());
    }

    public static void writeDefaults(File file) throws IOException {
        if (file.exists()) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create climate configuration directory");
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "StarCore weather values are direct resource multipliers.",
                "Only agricultural/farm and mineral/workshop mine rules are built in.",
                "Typed technology mappings are empty until each effect contract is verified."));
        y.set("schema-version", 1);
        y.set("input.multiplier-minimum", 0.0);
        y.set("input.multiplier-maximum", 10.0);
        y.set("weather.agriculture.minimum", 0.5);
        y.set("weather.agriculture.maximum", 1.5);
        y.set("weather.mineral.minimum", 0.5);
        y.set("weather.mineral.maximum", 1.5);
        y.set("technology.adjustment-minimum", -0.5);
        y.set("technology.adjustment-maximum", 0.5);
        y.set("resilience.adverse-agriculture-mitigation", 0.5);
        y.createSection("technology-mappings");
        y.save(file);
    }

    public static ClimateDefinitions load(File file, Definitions definitions) throws Exception {
        Objects.requireNonNull(definitions);
        YamlConfiguration y = new YamlConfiguration();
        y.load(file);
        if (integer(y, "schema-version", 1, 1) != 1)
            throw new IllegalArgumentException("Unsupported climate schema-version");
        double inputMin = decimal(y, "input.multiplier-minimum", 0, 10);
        double inputMax = decimal(y, "input.multiplier-maximum", 0, 10);
        double agricultureMin = decimal(y, "weather.agriculture.minimum", 0, 10);
        double agricultureMax = decimal(y, "weather.agriculture.maximum", 0, 10);
        double mineralMin = decimal(y, "weather.mineral.minimum", 0, 10);
        double mineralMax = decimal(y, "weather.mineral.maximum", 0, 10);
        double technologyMin = decimal(y, "technology.adjustment-minimum", -1, 2);
        double technologyMax = decimal(y, "technology.adjustment-maximum", -1, 2);
        double agricultureMitigation = decimal(y,"resilience.adverse-agriculture-mitigation",0,1);

        ConfigurationSection mappings = y.getConfigurationSection("technology-mappings");
        if (mappings == null) throw new IllegalArgumentException("Missing technology-mappings section");
        if (mappings.getKeys(false).size() > 16) throw new IllegalArgumentException("Too many climate technology mappings");
        Map<String, TechnologyMapping> technology = new TreeMap<>();
        for (String effect : mappings.getKeys(false)) {
            if (!SAFE_TECHNOLOGY_EFFECTS.contains(effect))
                throw new IllegalArgumentException("Unsupported typed technology effect: " + effect);
            ConfigurationSection section = mappings.getConfigurationSection(effect);
            if (section == null) throw new IllegalArgumentException("Invalid technology mapping: " + effect);
            String building = requiredText(section, "building");
            String recipe = requiredText(section, "recipe");
            if (!definitions.buildings().containsKey(building))
                throw new IllegalArgumentException("Unknown technology building: " + building);
            Definitions.Recipe target = definitions.recipes().stream().filter(r -> r.id().equals(recipe)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown technology recipe: " + recipe));
            if (!target.building().equals(building))
                throw new IllegalArgumentException("Technology recipe/building mismatch: " + effect);
            technology.put(effect, new TechnologyMapping(effect, building, recipe));
        }
        return new ClimateDefinitions(inputMin, inputMax, agricultureMin, agricultureMax,
                mineralMin, mineralMax, technologyMin, technologyMax, agricultureMitigation, technology);
    }

    private static long integer(ConfigurationSection section, String key, long min, long max) {
        Object value = section.get(key);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue()
                || n.longValue() < min || n.longValue() > max)
            throw new IllegalArgumentException("Invalid climate integer: " + key);
        return n.longValue();
    }

    private static double decimal(ConfigurationSection section, String key, double min, double max) {
        Object value = section.get(key);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < min || n.doubleValue() > max)
            throw new IllegalArgumentException("Invalid climate number: " + key);
        return n.doubleValue();
    }

    private static String requiredText(ConfigurationSection section, String key) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing climate value: " + key);
        id(value, key);
        return value;
    }

    private static void range(double minimum, double maximum, double allowedMinimum, double allowedMaximum, String label) {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum < allowedMinimum
                || maximum > allowedMaximum || minimum > maximum)
            throw new IllegalArgumentException("Invalid climate " + label + " range");
    }

    private static void id(String value, String label) {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,47}"))
            throw new IllegalArgumentException("Invalid " + label + " ID: " + value);
    }
}

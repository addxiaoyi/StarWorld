package dev.starworld.domain;

import java.util.*;

/** Validated, immutable data definitions. No Bukkit state enters the simulation. */
public record Definitions(long periodSeconds, int maxBuildingsPerCity, int maxCitiesPerPass,
                          long stockLimit, double eventChance, Map<String, Terrain> terrains,
                          Map<String, Civilization> civilizations, Map<String, BuildingType> buildings,
                          List<Recipe> recipes, Map<String, WorldEvent> events) {
    public Definitions {
        if (periodSeconds < 10 || periodSeconds > 86400 || maxBuildingsPerCity < 1 || maxBuildingsPerCity > 64
                || maxCitiesPerPass < 1 || maxCitiesPerPass > 1000 || stockLimit < 64 || stockLimit > 1_000_000_000L
                || !Double.isFinite(eventChance) || eventChance < 0 || eventChance > 1) throw new IllegalArgumentException("Invalid simulation bounds");
        terrains = Map.copyOf(terrains); civilizations = Map.copyOf(civilizations); buildings = Map.copyOf(buildings);
        recipes = List.copyOf(recipes); events = Map.copyOf(events);
        if (terrains.isEmpty() || civilizations.isEmpty() || buildings.isEmpty()) throw new IllegalArgumentException("Empty definitions");
        if (!civilizations.containsKey("agrarian")) throw new IllegalArgumentException("Missing default civilization: agrarian");
        Set<String> ids = new HashSet<>();
        for (Recipe r : recipes) {
            if (!ids.add(r.id()) || !buildings.containsKey(r.building())) throw new IllegalArgumentException("Invalid recipe: " + r.id());
            if (r.reserve() != null && terrains.values().stream().noneMatch(t -> t.reserves().containsKey(r.reserve()))) throw new IllegalArgumentException("Unknown reserve: " + r.reserve());
        }
    }
    public record Terrain(String id, String name, double agriculture, double transport, double military,
                          Map<String, Long> reserves, Map<String, Long> regeneration) {
        public Terrain { Objects.requireNonNull(id); Objects.requireNonNull(name); factor(agriculture); factor(transport); factor(military); reserves=positive(reserves); regeneration=positive(regeneration);
            for (String key : regeneration.keySet()) if (!reserves.containsKey(key)) throw new IllegalArgumentException("Unknown regenerating reserve " + key);
        }
    }
    public record Civilization(String id, String name, Map<String, Double> multipliers) {
        public Civilization { Objects.requireNonNull(id); Objects.requireNonNull(name); multipliers=Map.copyOf(multipliers); multipliers.values().forEach(Definitions::factor); }
    }
    public record BuildingType(String id, String name, String anchor, Map<String, Long> upkeep) {
        public BuildingType { Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(anchor); upkeep=positive(upkeep); }
    }
    public record Recipe(String id, String building, Map<String, Long> inputs, Map<String, Long> outputs,
                         String reserve, long reserveCost) {
        public Recipe { Objects.requireNonNull(id); Objects.requireNonNull(building); inputs=positive(inputs); outputs=positive(outputs);
            if (outputs.isEmpty() || reserveCost < 0 || (reserve == null) != (reserveCost == 0)) throw new IllegalArgumentException("Invalid recipe " + id);
        }
    }
    public record WorldEvent(String id, String name, int duration, int aftermath, Map<String, Double> multipliers, double aftermathMultiplier) {
        public WorldEvent { Objects.requireNonNull(id); Objects.requireNonNull(name); if (duration < 1 || duration > 1000 || aftermath < 0 || aftermath > 1000) throw new IllegalArgumentException("Invalid event duration");
            multipliers=Map.copyOf(multipliers); multipliers.values().forEach(Definitions::factor); factor(aftermathMultiplier);
        }
    }
    public static Map<String, Long> positive(Map<String, Long> values) {
        Map<String,Long> copy = Map.copyOf(values);
        copy.forEach((k,v) -> { if (k == null || k.isBlank() || v < 0 || v > 1_000_000_000L) throw new IllegalArgumentException("Invalid amount: " + k); });
        return copy;
    }
    private static void factor(double d) { if (!Double.isFinite(d) || d < 0 || d > 10) throw new IllegalArgumentException("Invalid multiplier"); }
}

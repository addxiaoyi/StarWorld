package dev.starworld.domain;

import java.util.*;

public final class WorldState {
    private WorldState() {}
    public record CoreCity(UUID cityId, UUID nationId, String cityName, String nationName,
                           double productionMultiplier, String detail) {
        public CoreCity { Objects.requireNonNull(cityId); Objects.requireNonNull(nationId); Objects.requireNonNull(cityName); Objects.requireNonNull(nationName);
            if (!Double.isFinite(productionMultiplier) || productionMultiplier < 0 || productionMultiplier > 10) throw new IllegalArgumentException("Invalid core modifier"); }
    }
    public record Site(UUID worldId, String worldName, int x, int y, int z) {
        public Site { Objects.requireNonNull(worldId); Objects.requireNonNull(worldName); }
        public String regionKey() { return worldId+":"+(x>>4)+":"+(z>>4); }
        public String anchorKey() { return worldId+":"+x+":"+y+":"+z; }
    }
    public record Building(UUID id, UUID cityId, UUID nationId, String type, Site site, int level) {}
    public record Region(String key, String terrain, Map<String, Long> reserves) {
        public Region { reserves=Map.copyOf(reserves); }
    }
    public record EventState(String type, long startPeriod, long endPeriod, long aftermathEndPeriod) {}
    public record CityView(UUID id, UUID nationId, String name, String civilization, long lastPeriod,
                           Map<String,Long> stock, List<Building> buildings, EventState event, String lastReport) {
        public CityView { stock=Map.copyOf(stock); buildings=List.copyOf(buildings); }
    }
    public record Settlement(UUID cityId, long period, boolean applied, Map<String, Long> produced, String report) {
        public Settlement { produced=Map.copyOf(produced); }
    }
    public record Transfer(UUID id, UUID playerId, UUID cityId, String direction, String item, int amount, String state) {}
}

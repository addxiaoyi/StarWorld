package dev.starcore.starcore.module.army;

import java.util.*;
import org.bukkit.Location;

/** Test-only subset of the public army snapshot lookup contract. */
public final class ArmyService {
    public final Map<UUID, Unit> units = new HashMap<>();
    public Optional<Unit> getArmy(UUID id) { return Optional.ofNullable(units.get(id)); }
    public record Unit(UUID nationId, Location location) {}
}

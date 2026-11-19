package dev.starcore.starcore.module.city;

import dev.starcore.starcore.module.city.model.City;
import java.util.Optional;
import java.util.UUID;

/** Test-only public signature fixture. */
public final class CityService {
    public Optional<City> getCity(UUID cityId) { throw new UnsupportedOperationException(); }
    public Optional<City> getPlayerCity(UUID playerId) { throw new UnsupportedOperationException(); }
}

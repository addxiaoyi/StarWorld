package dev.starcore.starcore.module.weather.model;

import dev.starcore.starcore.module.nation.model.NationId;

/** Test-only public WorldWeatherState signature fixture. */
public interface WorldWeatherState {
    WeatherType getCurrentWeather();
    NationId getControlledByNation();
}

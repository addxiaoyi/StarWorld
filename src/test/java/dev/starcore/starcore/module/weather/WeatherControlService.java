package dev.starcore.starcore.module.weather;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.weather.model.WeatherType;
import java.util.Map;
public interface WeatherControlService {
    WeatherType getNationWeather(NationId nationId);
    Map<String,Double> getResourceModifiers(WeatherType type);
}

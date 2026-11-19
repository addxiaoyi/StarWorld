package dev.starcore.starcore.api;

import dev.starcore.starcore.module.nation.NationService;
import java.util.Optional;

/** Test-only subset of the audited f769e627 public contract; never included in the plugin JAR. */
public interface StarCoreApi {
    String version();
    <T> Optional<T> service(Class<T> serviceType);
    Optional<NationService> nationService();
}

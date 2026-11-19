package dev.starcore.starcore.module.resource;

import dev.starcore.starcore.module.nation.model.NationId;
import java.util.Map;

/** Test-only public signature fixture. */
public interface ResourceService {
    Map<String, Long> stockpile(NationId nationId);
}

package dev.starcore.starcore.module.technology;

import dev.starcore.starcore.module.nation.model.NationId;

/** Test-only public signature fixture. */
public interface TechnologyService {
    double getTotalModifier(NationId nationId, String effectType);
}

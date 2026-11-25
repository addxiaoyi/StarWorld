package dev.starcore.starcore.module.prosperity;

import dev.starcore.starcore.module.nation.model.NationId;

/** Test-only public signature fixture. */
public interface ProsperityService {
    double getProsperityValue(NationId nationId);
    double getResourceBonus(NationId nationId);
}

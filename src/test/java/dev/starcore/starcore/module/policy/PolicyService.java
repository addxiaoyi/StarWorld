package dev.starcore.starcore.module.policy;

import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.policy.model.PolicyEffectScope;

/** Test-only public signature fixture. */
public interface PolicyService {
    java.util.Optional<String> activePolicy(NationId nationId);
    double activePolicyModifier(NationId nationId, String effectKey, PolicyEffectScope scope);
}

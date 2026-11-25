package dev.starcore.starcore.core.extension.army;

import dev.starcore.starcore.core.extension.*;
import java.util.*;

/** Reflects the audited core's maximum-damage aggregate rather than a sum/product. */
public final class ArmyCombatExtensionPoint implements ExtensionPoint<ArmyCombatExtensionPoint.CombatResult, ArmyCombatExtensionPoint.CombatContext> {
    public ArmyCombatExtensionPoint() { ExtensionRegistry.getInstance().registerExtensionPoint(this); }
    public String getName() { return "army_combat"; }
    @SuppressWarnings("unchecked")
    public CombatResult execute(CombatContext context) {
        CombatResult best = null;
        for (ExtensionContributor<?, ?> raw : ExtensionRegistry.getInstance().getContributors(getName())) {
            ExtensionContributor<CombatResult, CombatContext> contributor = (ExtensionContributor<CombatResult, CombatContext>) raw;
            if (!contributor.isApplicable(context)) continue; CombatResult result = contributor.contribute(context);
            if (result != null && (best == null || best.damage() < result.damage())) best = result;
        }
        return best == null ? new CombatResult(context.baseDamage(), "default", List.of()) : best;
    }
    public record CombatContext(String attackerId, String defenderId, String attackerType, String defenderType, double baseDamage, int attackerSoldiers, int defenderSoldiers) {}
    public record CombatResult(double damage, String source, List<String> appliedModifiers) {}
}

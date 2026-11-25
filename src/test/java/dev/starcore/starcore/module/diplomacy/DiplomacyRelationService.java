package dev.starcore.starcore.module.diplomacy;
import dev.starcore.starcore.module.nation.model.NationId;
public interface DiplomacyRelationService {
    enum Relation { ALLIED,HOSTILE,NEUTRAL }
    Relation relationBetween(NationId left,NationId right);
}

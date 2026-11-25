package dev.starcore.starcore.module.war;
import dev.starcore.starcore.module.nation.model.NationId;
import java.util.*;
public interface WarService {
    record WarSnapshot(UUID id,NationId left,NationId right,boolean ended){}
    Collection<WarSnapshot> activeWarsOf(NationId nationId);
}

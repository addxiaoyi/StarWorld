package dev.starcore.starcore.module.resource;
import dev.starcore.starcore.module.nation.model.NationId;
import java.util.*;
public interface ProcessingService {
    record Recipe(String recipeId,Map<String,Long> inputs,Map<String,Long> outputs,long processingTime){}
    Collection<Recipe> getAllRecipes();
    Collection<Object> getFactories(NationId nationId);
}

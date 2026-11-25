package dev.starworld.works;

import java.util.*;

/** Immutable, bounded public-works definitions and durable receipts. */
public final class PublicWorksState {
    private PublicWorksState(){}

    public record ProjectDefinition(String id,String name,int buildPeriods,
            Map<String,Long> upfrontCosts,Map<String,Long> constructionCosts,Map<String,Long> upkeepCosts,
            Set<String> facilities,Set<String> terrains,Set<String> plans,Set<String> civilizations,
            long knowledge,long culture,Map<String,Double> multipliers){
        public ProjectDefinition{
            PublicWorksState.id(id);Objects.requireNonNull(name);if(name.isBlank()||name.length()>80)throw new IllegalArgumentException("Invalid works name");
            if(buildPeriods<3||buildPeriods>5||knowledge<0||knowledge>1_000_000||culture<0||culture>1_000_000)throw new IllegalArgumentException("Invalid works thresholds");
            upfrontCosts=costs(upfrontCosts);constructionCosts=costs(constructionCosts);upkeepCosts=costs(upkeepCosts);
            facilities=ids(facilities,8);terrains=ids(terrains,16);plans=ids(plans,16);civilizations=ids(civilizations,16);
            multipliers=Map.copyOf(multipliers);if(multipliers.isEmpty()||multipliers.size()>16)throw new IllegalArgumentException("Invalid works multiplier count");
            multipliers.forEach((key,value)->{PublicWorksState.id(key);if(value==null||!Double.isFinite(value)||value<0.5||value>1.25)throw new IllegalArgumentException("Invalid works multiplier");});
        }
    }
    public record Settings(int schemaVersion,int maxPage,int maxEvidenceFacilities,int historyLimit,Map<String,ProjectDefinition> projects){
        public Settings{
            if(schemaVersion!=1||maxPage<1||maxPage>128||maxEvidenceFacilities<1||maxEvidenceFacilities>256||historyLimit<10||historyLimit>2000)throw new IllegalArgumentException("Invalid works limits");
            projects=Map.copyOf(projects);if(projects.isEmpty()||projects.size()>16)throw new IllegalArgumentException("Invalid works project count");
            projects.forEach((key,value)->{if(!key.equals(value.id()))throw new IllegalArgumentException("Works key/id mismatch");});
        }
    }
    public record Project(UUID requestId,UUID cityId,UUID nationId,ProjectDefinition definition,long requestedPeriod,
            long effectivePeriod,int progress,long lastBuildPeriod,long completedPeriod,long lastOperationPeriod,boolean lastOperationPaid,long lastConstructionAttemptPeriod,
            String startPlan,String startCivilization,long startKnowledge,long startCulture){
        public Project{
            Objects.requireNonNull(requestId);Objects.requireNonNull(cityId);Objects.requireNonNull(nationId);Objects.requireNonNull(definition);id(startPlan);id(startCivilization);
            if(requestedPeriod<0||effectivePeriod<=requestedPeriod||progress<0||progress>definition.buildPeriods()||lastBuildPeriod< -1||completedPeriod< -1||lastOperationPeriod< -1||lastConstructionAttemptPeriod<lastBuildPeriod||startKnowledge<0||startCulture<0||
                    (progress==0&&lastBuildPeriod!=-1)||(progress>0&&lastBuildPeriod<effectivePeriod)||
                    (progress==definition.buildPeriods())!=(completedPeriod>=effectivePeriod)||
                    (completedPeriod>=0&&completedPeriod!=lastBuildPeriod)||(lastOperationPaid&&(progress!=definition.buildPeriods()||lastOperationPeriod<=completedPeriod)))throw new IllegalArgumentException("Invalid saved works project");
        }
        public boolean completed(){return progress==definition.buildPeriods();}
    }
    public record History(UUID requestId,long period,String kind,String detail){
        public History{Objects.requireNonNull(requestId);Objects.requireNonNull(kind);Objects.requireNonNull(detail);if(period<0)throw new IllegalArgumentException("Invalid works history period");}
    }
    public record Completion(long period,boolean inspected,boolean advanced,boolean completedNow,int progress,String reason){
        public Completion{Objects.requireNonNull(reason);if(period<0||progress<0||progress>5||advanced&&!inspected||completedNow&&!advanced)throw new IllegalArgumentException("Invalid works completion");}
    }
    /** Read-only planning projection; this never allocates a slot, debits goods, or grants operation authority. */
    public record Feasibility(String definitionId,String name,boolean eligible,String reason,Map<String,Long> missingUpfrontCosts){
        public Feasibility{Objects.requireNonNull(definitionId);Objects.requireNonNull(name);Objects.requireNonNull(reason);missingUpfrontCosts=Map.copyOf(missingUpfrontCosts);}
    }
    private static Map<String,Long> costs(Map<String,Long> input){
        Map<String,Long> values=Map.copyOf(input);if(values.isEmpty()||values.size()>16)throw new IllegalArgumentException("Invalid works cost count");
        values.forEach((key,value)->{if(!key.matches("[a-z0-9._-]+:[a-z0-9/._-]+")||key.length()>160||value<1||value>1_000_000)throw new IllegalArgumentException("Invalid works physical cost");});return values;
    }
    private static Set<String> ids(Set<String> input,int max){Set<String> result=Set.copyOf(input);if(result.isEmpty()||result.size()>max)throw new IllegalArgumentException("Invalid works requirement count");result.forEach(PublicWorksState::id);return result;}
    private static void id(String value){if(value==null||!value.matches("[a-z][a-z0-9_-]{0,47}"))throw new IllegalArgumentException("Invalid works identifier");}
}

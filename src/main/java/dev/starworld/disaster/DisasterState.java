package dev.starworld.disaster;

import java.util.*;

/** Immutable requests and snapshots for bounded, durable disaster response. */
public final class DisasterState {
    private DisasterState(){}
    /** Budget caps combined profile and reserve recovery; profile fractions consume a rounded-up unit. */
    public record Strategy(String id,String eventType,Set<String> facilities,Map<String,Long> costs,
                           Map<String,Long> recoveryPerStep,int reliefPermille,int maxSteps,
                           long budget,long durationPeriods,int stabilityPerStep,int prosperityPerStep,String importItem,long importUnits,int receiptAgePeriods){
        public Strategy{
            Objects.requireNonNull(id);Objects.requireNonNull(eventType);facilities=Set.copyOf(facilities);
            costs=Map.copyOf(costs);recoveryPerStep=Map.copyOf(recoveryPerStep);
            if(!id.matches("[a-z][a-z0-9_-]{0,47}")||!eventType.matches("[a-z][a-z0-9_-]{0,47}")||
               facilities.isEmpty()||facilities.size()>4||facilities.stream().anyMatch(v->!v.matches("[a-z][a-z0-9_-]{0,47}"))||
               costs.isEmpty()||costs.values().stream().noneMatch(v->v!=null&&v>0)||costs.size()>16||recoveryPerStep.isEmpty()||recoveryPerStep.size()>2||!Set.of("fertility","timber").containsAll(recoveryPerStep.keySet())||reliefPermille<0||reliefPermille>1000||
               maxSteps<1||maxSteps>1000||budget<1||budget>1_000_000_000L||durationPeriods<1||durationPeriods>10000||
               stabilityPerStep<0||stabilityPerStep>3||prosperityPerStep<0||prosperityPerStep>2||
               importUnits<0||importUnits>1_000_000L||receiptAgePeriods<0||receiptAgePeriods>10000||
               ((importItem==null)!=(importUnits==0))||((importItem==null)!=(receiptAgePeriods==0))||
               (importItem!=null&&!importItem.matches("[a-z0-9._-]+:[a-z0-9/._-]+")))throw new IllegalArgumentException("Invalid disaster strategy: "+id);
            amounts(costs,false);amounts(recoveryPerStep,true);
        }
    }
    public record Definitions(int schemaVersion,int maxActivePerCity,int maxPage,int maxEvidenceFacilities,
                              boolean allowAftermath,Map<String,Strategy> strategies){
        public Definitions{if(schemaVersion!=1||maxActivePerCity<1||maxActivePerCity>16||maxPage<1||maxPage>128||maxEvidenceFacilities<1||maxEvidenceFacilities>256)throw new IllegalArgumentException("Invalid disaster response limits");strategies=Map.copyOf(strategies);if(strategies.isEmpty()||strategies.size()>32)throw new IllegalArgumentException("Invalid strategy count");for(var e:strategies.entrySet())if(!e.getKey().equals(e.getValue().id()))throw new IllegalArgumentException("Strategy key/id mismatch");}
    }
    public record Response(UUID requestId,UUID cityId,UUID nationId,String strategyId,String eventType,long eventStart,
                           long eventEnd,long aftermathEnd,long requestedPeriod,long effectivePeriod,long expiresPeriod,
                           long lastAppliedPeriod,int steps,int maxSteps,long remainingBudget,int reliefPermille,boolean allowAftermath,
                           int stabilityPerStep,int prosperityPerStep,
                           Set<String> requiredFacilities,Map<String,Long> costs,Map<String,Long> recoveryPerStep,
                           UUID importReceipt){
        public Response{Objects.requireNonNull(requestId);Objects.requireNonNull(cityId);Objects.requireNonNull(nationId);Objects.requireNonNull(strategyId);Objects.requireNonNull(eventType);requiredFacilities=Set.copyOf(requiredFacilities);costs=Map.copyOf(costs);recoveryPerStep=Map.copyOf(recoveryPerStep);if(eventStart<0||eventEnd<=eventStart||aftermathEnd<eventEnd||requestedPeriod<0||effectivePeriod<=requestedPeriod||expiresPeriod<=effectivePeriod||lastAppliedPeriod< -1||steps<0||maxSteps<steps||remainingBudget<0||reliefPermille<0||reliefPermille>1000||stabilityPerStep<0||stabilityPerStep>3||prosperityPerStep<0||prosperityPerStep>2)throw new IllegalArgumentException("Invalid disaster response");amounts(costs,false);amounts(recoveryPerStep,true);}
    }
    public record History(UUID requestId,long period,String kind,String detail){public History{Objects.requireNonNull(requestId);Objects.requireNonNull(kind);Objects.requireNonNull(detail);if(period<0)throw new IllegalArgumentException("Invalid response history");}}
    public record RecoveryResult(long period,int inspected,int appliedSteps,Map<String,Map<String,Long>> restored){
        public RecoveryResult{if(period<0||inspected<0||appliedSteps<0)throw new IllegalArgumentException("Invalid recovery result");Map<String,Map<String,Long>> copy=new HashMap<>();restored.forEach((k,v)->copy.put(k,Map.copyOf(v)));restored=Map.copyOf(copy);}
    }
    private static void amounts(Map<String,Long> values,boolean positive){
        for(var e:values.entrySet())if(e.getKey()==null||!e.getKey().matches("[a-z0-9_./-]+(:[a-z0-9_./-]+)?")||e.getValue()==null||e.getValue()<(positive?1:0)||e.getValue()>1_000_000_000L)throw new IllegalArgumentException("Invalid disaster amount: "+e.getKey());
    }
}

package dev.starworld.domain;

import dev.starworld.domain.WorldState.*;
import dev.starworld.persistence.WorldStore;
import java.sql.SQLException;
import java.util.*;

/** Deterministic, bounded settlement. Inventory, reserve debits and period receipt commit together. */
public final class Simulation {
    private final WorldStore store;
    private final Definitions definitions;
    private final Set<String> availableItems;
    public Simulation(WorldStore store,Definitions definitions) { this(store,definitions,null); }
    public Simulation(WorldStore store,Definitions definitions,Set<String> availableItems) { this.store=store;this.definitions=definitions;this.availableItems=availableItems==null?null:Set.copyOf(availableItems); }
    public long period(long epochSeconds) { return Math.floorDiv(epochSeconds,definitions.periodSeconds()); }
    public Settlement settle(CoreCity core,long period,Set<UUID> authorizedBuildings) throws SQLException {
        if(period<0)throw new IllegalArgumentException("Invalid period");
        return store.tx(s->{
            store.ensureCity(core);
            CityView city=store.view(core.cityId()).orElseThrow();
            if(city.lastPeriod()>=period || store.settled(city.id(),period))return new Settlement(city.id(),period,false,Map.of(),"此周期已结算/时钟回拨，无重复产出");
            Map<String,Long> stock=new TreeMap<>(city.stock()),produced=new TreeMap<>();List<String> report=new ArrayList<>();
            EventState event=advanceEvent(city,period);Definitions.Civilization civilization=definitions.civilizations().get(city.civilization());
            if(civilization==null)throw new IllegalStateException("Unknown saved civilization: "+city.civilization());
            Set<String> regenerated=new HashSet<>();
            for(Building b:city.buildings()) {
                if(!authorizedBuildings.contains(b.id()) || !b.nationId().equals(core.nationId())) { report.add(b.type()+": 领土/建筑失效，停产");continue; }
                Definitions.BuildingType type=definitions.buildings().get(b.type());
                if(type==null) { report.add(b.type()+": 配置已移除，停产");continue; }
                Region region=store.region(b.site().regionKey());Definitions.Terrain terrain=definitions.terrains().get(region.terrain());
                if(terrain==null) {report.add("未知地形，停产");continue;}
                if(regenerated.add(region.key()))store.regenerate(region.key(),period,terrain);
                region=store.region(region.key());Map<String,Long> reserves=new HashMap<>(region.reserves());
                if(availableItems!=null && !availableItems.containsAll(type.upkeep().keySet())){report.add(type.name()+": 维护物品依赖不可用");continue;}
                if(!canPay(stock,type.upkeep(),b.level())) { report.add(type.name()+": 维护食物不足");continue; }
                debit(stock,type.upkeep(),b.level());
                double factor=core.productionMultiplier()*civilization.multipliers().getOrDefault(type.id(),1.0)*eventFactor(event,period,type.id());
                factor*=type.id().equals("farm") ? terrain.agriculture() : type.id().equals("sawmill") ? 1.0/Math.max(0.25,terrain.transport()) : 1.0;
                final double effectiveFactor=Math.min(factor,10);
                for(Definitions.Recipe recipe:definitions.recipes()) {
                    if(!recipe.building().equals(b.type()) || !canPay(stock,recipe.inputs(),b.level()))continue;
                    if(availableItems!=null && (!availableItems.containsAll(recipe.inputs().keySet()) || !availableItems.containsAll(recipe.outputs().keySet()))){report.add(recipe.id()+": 物品依赖不可用，配方暂停");continue;}
                    long reserveCost=recipe.reserveCost()*b.level();
                    if(recipe.reserve()!=null && reserves.getOrDefault(recipe.reserve(),0L)<reserveCost)continue;
                    Map<String,Long> output=new HashMap<>();
                    recipe.outputs().forEach((item,amount)->output.put(item,(long)Math.floor(amount*b.level()*effectiveFactor)));
                    if(output.values().stream().allMatch(x->x==0L))continue;
                    boolean full=output.entrySet().stream().anyMatch(e->e.getValue()>definitions.stockLimit()-stock.getOrDefault(e.getKey(),0L));
                    if(full) {report.add(recipe.id()+": 仓储已满，原料保留");continue;}
                    debit(stock,recipe.inputs(),b.level());
                    output.forEach((k,v)->{stock.merge(k,v,Long::sum);produced.merge(k,v,Long::sum);});
                    if(recipe.reserve()!=null)reserves.compute(recipe.reserve(),(k,v)->v-reserveCost);
                }
                for(var e:reserves.entrySet())store.reserve(region.key(),e.getKey(),e.getValue());
            }
            for(var e:stock.entrySet())store.stock(city.id(),e.getKey(),e.getValue());
            String summary="产出 "+produced+"；"+String.join("；",report)+(event==null ? "" : "；事件 "+event.type());
            store.finish(city.id(),period,summary);
            return new Settlement(city.id(),period,true,produced,summary);
        });
    }
    private EventState advanceEvent(CityView city,long period) throws SQLException {
        EventState event=city.event();
        if(event!=null && !definitions.events().containsKey(event.type()))throw new IllegalStateException("Unknown saved event: "+event.type());
        if(event!=null && period>=event.aftermathEndPeriod()) {event=null;store.event(city.id(),null);}
        if(event==null && !definitions.events().isEmpty()) {
            Random random=new Random(city.id().getMostSignificantBits()^city.id().getLeastSignificantBits()^period);
            if(random.nextDouble()<definitions.eventChance()) {
                List<String> keys=definitions.events().keySet().stream().sorted().toList();Definitions.WorldEvent d=definitions.events().get(keys.get(random.nextInt(keys.size())));
                event=event(d,period);store.event(city.id(),event);
            }
        }
        return event;
    }
    public EventState trigger(UUID city,String type,long period) throws SQLException {
        Definitions.WorldEvent definition=definitions.events().get(type);if(definition==null)throw new IllegalArgumentException("Unknown event: "+type);
        EventState event=event(definition,period);store.event(city,event);return event;
    }
    private EventState event(Definitions.WorldEvent d,long p) {return new EventState(d.id(),p,Math.addExact(p,d.duration()),Math.addExact(p,(long)d.duration()+d.aftermath()));}
    private double eventFactor(EventState state,long period,String type) {
        if(state==null || period<state.startPeriod())return 1;
        Definitions.WorldEvent d=definitions.events().get(state.type());
        if(period<state.endPeriod())return d.multipliers().getOrDefault(type,1.0);
        return period<state.aftermathEndPeriod() ? d.aftermathMultiplier() : 1;
    }
    private static boolean canPay(Map<String,Long> stock,Map<String,Long> cost,int level) {return cost.entrySet().stream().allMatch(e->stock.getOrDefault(e.getKey(),0L)>=e.getValue()*level);}
    private static void debit(Map<String,Long> stock,Map<String,Long> cost,int level) {cost.forEach((k,v)->stock.put(k,stock.getOrDefault(k,0L)-v*level));}
}

package dev.starworld.evolution;

import dev.starworld.domain.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.evolution.EvolutionState.*;
import dev.starworld.persistence.WorldStore;
import java.sql.SQLException;
import java.util.*;

/** Incremental urban/cultural simulation. Only registered, verified facilities contribute. */
public final class EvolutionService {
    private final WorldStore store;
    private final Definitions definitions;
    private final EvolutionDefinitions options;
    private final CultureDynamics cultureDynamics;
    public EvolutionService(WorldStore store,Definitions definitions,EvolutionDefinitions options)throws SQLException {
        this(store,definitions,options,CultureDynamics.Settings.defaults());
    }
    public EvolutionService(WorldStore store,Definitions definitions,EvolutionDefinitions options,CultureDynamics.Settings cultureSettings)throws SQLException {
        this.store=store;this.definitions=definitions;this.options=options;
        store.tx(s->{
            s.execute("CREATE TABLE IF NOT EXISTS evolution_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
            var versions=s.query("SELECT version FROM evolution_schema WHERE id=1",r->r.getInt(1));if(!versions.isEmpty()&&versions.getFirst()!=1)throw new IllegalStateException("Unsupported evolution schema; preserve database and upgrade plugin");
            s.execute("INSERT OR IGNORE INTO evolution_schema(id,version) VALUES(1,1)");
            s.execute("CREATE TABLE IF NOT EXISTS evolution_city(city TEXT PRIMARY KEY REFERENCES city_state(id),plan TEXT NOT NULL DEFAULT 'balanced',specialty TEXT NOT NULL DEFAULT '',changed INTEGER NOT NULL DEFAULT -1000000,last_period INTEGER NOT NULL DEFAULT -1,prosperity REAL NOT NULL DEFAULT 50,stability REAL NOT NULL DEFAULT 50,knowledge INTEGER NOT NULL DEFAULT 0,supply INTEGER NOT NULL DEFAULT 0,culture INTEGER NOT NULL DEFAULT 0,agriculture INTEGER NOT NULL DEFAULT 0,industry INTEGER NOT NULL DEFAULT 0,commerce INTEGER NOT NULL DEFAULT 0,military INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS evolution_history(city TEXT NOT NULL REFERENCES city_state(id),period INTEGER NOT NULL,kind TEXT NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(city,period,kind))");
            s.execute("CREATE TABLE IF NOT EXISTS evolution_event(city TEXT NOT NULL REFERENCES city_state(id),type TEXT NOT NULL,period INTEGER NOT NULL,PRIMARY KEY(city,type))");
            s.execute("CREATE TABLE IF NOT EXISTS region_culture(region TEXT NOT NULL REFERENCES region(id),tradition TEXT NOT NULL,influence INTEGER NOT NULL CHECK(influence>=0),PRIMARY KEY(region,tradition))");
            s.execute("CREATE TABLE IF NOT EXISTS culture_exchange(receipt TEXT PRIMARY KEY,city_a TEXT NOT NULL,city_b TEXT NOT NULL,period INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS evolution_trade_action(city TEXT NOT NULL REFERENCES city_state(id),receipt TEXT NOT NULL,period INTEGER NOT NULL,PRIMARY KEY(city,receipt))");
            s.execute("CREATE TABLE IF NOT EXISTS evolution_facility(city TEXT NOT NULL REFERENCES city_state(id),building TEXT NOT NULL,period INTEGER NOT NULL,PRIMARY KEY(city,building,period))");
            s.execute("CREATE TABLE IF NOT EXISTS evolution_readiness(city TEXT PRIMARY KEY REFERENCES city_state(id),nation TEXT NOT NULL,period INTEGER NOT NULL,provided INTEGER NOT NULL,required INTEGER NOT NULL,terrain REAL NOT NULL,readiness REAL NOT NULL)");return null;
        });
        cultureDynamics=new CultureDynamics(store,cultureSettings);
    }
    public EvolutionDefinitions options(){return options;}
    public CultureDynamics.Settings cultureSettings(){return cultureDynamics.settings();}
    public Profile profile(UUID city)throws SQLException {
        if(store.view(city).isEmpty())throw new IllegalArgumentException("城市尚未登记");
        store.execute("INSERT OR IGNORE INTO evolution_city(city) VALUES(?)",city);
        return store.query("SELECT * FROM evolution_city WHERE city=?",r->new Profile(city,r.getString("plan"),r.getString("specialty"),r.getLong("changed"),r.getLong("last_period"),r.getDouble("prosperity"),r.getDouble("stability"),r.getLong("knowledge"),r.getLong("supply"),r.getLong("culture"),Map.of("agriculture",r.getLong("agriculture"),"industry",r.getLong("industry"),"commerce",r.getLong("commerce"),"military",r.getLong("military"))),city).getFirst();
    }
    public Profile plan(CoreCity city,String id,long period,String actor)throws SQLException {
        if(!options.plans().containsKey(id))throw new IllegalArgumentException("未知城市规划");
        return store.tx(s->{s.ensureCity(city);Profile p=profile(city.cityId());cooldown(p,period);s.execute("UPDATE evolution_city SET plan=?,changed=? WHERE city=?",id,period,city.cityId());history(city.cityId(),period,"planning",id);s.audit(actor,"PLAN "+city.cityId()+" "+id);return profile(city.cityId());});
    }
    public Profile specialize(CoreCity city,String id,long period,Set<UUID> verified,String actor)throws SQLException {
        EvolutionDefinitions.Specialty specialty=options.specialties().get(id);if(specialty==null)throw new IllegalArgumentException("未知城市专长");
        return store.tx(s->{s.ensureCity(city);Profile p=profile(city.cityId());cooldown(p,period);
            if(p.knowledge()<specialty.knowledge()||city.productionMultiplier()<specialty.coreMultiplier())throw new IllegalArgumentException("教育积累或国家科技/政策产能尚未达到专长条件");
            if(s.buildings(city.cityId()).stream().noneMatch(b->b.type().equals(specialty.building())&&verified.contains(b.id())))throw new IllegalArgumentException("缺少已验证的专业设施 "+specialty.building());
            s.execute("UPDATE evolution_city SET specialty=?,changed=? WHERE city=?",id,period,city.cityId());history(city.cityId(),period,"specialty",id);s.audit(actor,"SPECIALTY "+city.cityId()+" "+id);return profile(city.cityId());});
    }
    private void cooldown(Profile p,long period){if(period<0||period-p.changed()<options.cooldown())throw new IllegalArgumentException("城市规划/专长转型冷却 "+options.cooldown()+" 周期");}
    public List<History> history(UUID city)throws SQLException {return store.query("SELECT period,kind,detail FROM evolution_history WHERE city=? ORDER BY period DESC,kind LIMIT ?",r->new History(r.getLong(1),r.getString(2),r.getString(3)),city,options.historyLimit());}
    public List<Culture> cultures(UUID city)throws SQLException {
        return store.query("SELECT DISTINCT c.region,c.tradition,c.influence FROM region_culture c JOIN building b ON c.region=b.world||':'||(b.x>>4)||':'||(b.z>>4) WHERE b.city=? ORDER BY c.region,c.influence DESC LIMIT 256",r->new Culture(r.getString(1),r.getString(2),r.getLong(3)),city);
    }
    public long capacity(CityView city,Set<UUID> valid)throws SQLException {
        Set<UUID> maintained=new HashSet<>(store.query("SELECT building FROM evolution_facility WHERE city=? AND period=?",r->UUID.fromString(r.getString(1)),city.id(),city.lastPeriod()));
        long warehouses=city.buildings().stream().filter(b->b.type().equals("warehouse")&&b.nationId().equals(city.nationId())&&valid.contains(b.id())&&maintained.contains(b.id())).mapToLong(Building::level).sum();
        return Math.min(definitions.stockLimit(),Math.max(64,definitions.stockLimit()/4)*(1+Math.min(warehouses,3)));
    }
    public Optional<Readiness> readiness(UUID city)throws SQLException{return store.query("SELECT * FROM evolution_readiness WHERE city=?",r->new Readiness(city,UUID.fromString(r.getString("nation")),r.getLong("period"),r.getLong("provided"),r.getLong("required"),r.getDouble("terrain"),r.getDouble("readiness")),city).stream().findFirst();}
    public Simulation.Hooks cycle(CoreCity core,CityView city,long period,Set<UUID> valid,Context context)throws SQLException {
        return cycle(core,city,period,valid,context,"");
    }
    public Simulation.Hooks cycle(CoreCity core,CityView city,long period,Set<UUID> valid,Context context,String policyId)throws SQLException {
        Profile p=profile(city.id());EvolutionDefinitions.Plan plan=options.plans().get(p.plan());
        if(plan==null)throw new IllegalStateException("Unknown saved plan "+p.plan());
        EvolutionDefinitions.Specialty specialty=options.specialties().get(p.specialty());
        Set<UUID> verified=Set.copyOf(valid);long capacity=capacity(city,verified);
        Map<String,Map<String,Long>> influences=new HashMap<>();for(Building b:city.buildings())if(verified.contains(b.id())&&!influences.containsKey(b.site().regionKey())){
            Map<String,Long> values=new HashMap<>();store.query("SELECT tradition,influence FROM region_culture WHERE region=?",r->{values.put(r.getString(1),r.getLong(2));return 0;},b.site().regionKey());influences.put(b.site().regionKey(),Map.copyOf(values));
        }
        List<ResourceDispute> disputes=resourceDisputes(core,city,period,verified,context);
        store.cityCapacity(city.id(),capacity);
        return new Simulation.Hooks(){
            @Override public long capacity(String item){return capacity;}
            @Override public double multiplier(Building b){
                double factor=plan.multipliers().getOrDefault(b.type(),1.0)*context.lifeMultiplier();
                Map<String,Long> culture=influences.getOrDefault(b.site().regionKey(),Map.of());double weighted=0,total=0;
                for(var entry:culture.entrySet()){Definitions.Civilization tradition=definitions.civilizations().get(entry.getKey());if(tradition!=null){weighted+=entry.getValue()*tradition.multipliers().getOrDefault(b.type(),1.0);total+=entry.getValue();}}
                if(total>0){double national=definitions.civilizations().get(city.civilization()).multipliers().getOrDefault(b.type(),1.0);factor*=clamp((weighted/total)/Math.max(0.1,national),0.85,1.15);}
                if(specialty!=null&&p.knowledge()>=specialty.knowledge()&&core.productionMultiplier()>=specialty.coreMultiplier()&&city.buildings().stream().anyMatch(x->x.type().equals(specialty.building())&&verified.contains(x.id())))factor*=specialty.multipliers().getOrDefault(b.type(),1.0);
                // Prosperity is earned by a maintained city, and scarcity carries a real cost.
                factor*=0.8+p.prosperity()/250.0;
                return Math.min(options.maxBonus(),factor);
            }
            @Override public boolean filterEvents(){return true;}
            @Override public Map<String,Double> eventWeights(CityView c,long t)throws SQLException{return candidates(c,p,t,context,verified,disputes);}
            @Override public void completed(CoreCity c,CityView previous,long t,Map<String,Long> produced,Set<UUID> maintained)throws SQLException{complete(c,previous,t,produced,maintained,context,p,policyId,disputes);}
        };
    }
    /** Current local reserves are associated only with matching-period, verified frontier production sites. */
    public List<ResourceDispute> resourceDisputes(CoreCity core,CityView city,long period,Set<UUID> valid,Context context)throws SQLException {
        if(!city.id().equals(core.cityId())||!city.nationId().equals(core.nationId()))return List.of();
        Set<String> regions=new HashSet<>();for(Building b:city.buildings())if(valid.contains(b.id())&&b.cityId().equals(city.id())&&b.nationId().equals(core.nationId()))regions.add(b.site().regionKey());
        List<ResourceDispute> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(BorderWitness witness:context.borderWitnesses()){
            if(witness.period()!=period||!witness.city().equals(city.id())||!witness.nation().equals(core.nationId())||!regions.contains(witness.regionKey()))continue;
            String key=witness.regionKey()+":"+witness.neighborChunkX()+":"+witness.neighborChunkZ()+":"+witness.peerNation();if(!seen.add(key))continue;
            Map<String,Long> reserves=store.region(witness.regionKey()).reserves();
            String resource=java.util.stream.Stream.concat(java.util.stream.Stream.of("ore","timber","fertility"),reserves.keySet().stream().sorted()).filter(id->reserves.getOrDefault(id,0L)>0).findFirst().orElse(null);
            if(resource!=null)result.add(new ResourceDispute(witness,resource,reserves.get(resource)));
        }
        return List.copyOf(result);
    }
    private Map<String,Double> candidates(CityView city,Profile p,long period,Context context,Set<UUID> valid,List<ResourceDispute> disputes)throws SQLException {
        Set<String> terrains=new HashSet<>();for(Building b:city.buildings())if(valid.contains(b.id()))terrains.add(store.region(b.site().regionKey()).terrain());
        Map<String,Long> last=new HashMap<>();store.query("SELECT type,period FROM evolution_event WHERE city=?",r->{last.put(r.getString(1),r.getLong(2));return 0;},city.id());
        long bread=city.stock().getOrDefault("minecraft:bread",0L),arrows=city.stock().getOrDefault("minecraft:arrow",0L);
        Map<String,Double> weights=new LinkedHashMap<>();
        for(var entry:options.events().entrySet()){
            var rule=entry.getValue();if(period-last.getOrDefault(entry.getKey(),-1000000L)<rule.cooldown())continue;
            boolean allowed=switch(rule.condition()){
                case "always"->!valid.isEmpty();
                case "dry"->terrains.contains("desert")||context.weather().toLowerCase(Locale.ROOT).contains("drought");
                case "snow"->terrains.contains("snow")||context.weather().toLowerCase(Locale.ROOT).contains("blizzard");
                case "river"->terrains.contains("river");
                case "famine"->!valid.isEmpty()&&bread<options.foodPerFacility()*valid.size();
                case "crisis"->context.atWar()&&arrows<12||city.buildings().stream().filter(b->valid.contains(b.id())).anyMatch(b->{try{return store.region(b.site().regionKey()).reserves().getOrDefault("ore",1L)==0;}catch(SQLException e){throw new IllegalStateException(e);}});
                case "trade"->context.successfulTrades()>0;
                case "culture"->context.lifeCulture()>0||p.culture()>=20;
                case "border"->!disputes.isEmpty();
                case "decline"->p.prosperity()<30||p.stability()<30;
                case "revival"->p.prosperity()>=30&&p.prosperity()<55&&bread>=10&&valid.size()>=2;
                default->false;
            };if(allowed)weights.put(entry.getKey(),rule.weight());
        }return Map.copyOf(weights);
    }
    private void complete(CoreCity core,CityView city,long period,Map<String,Long> produced,Set<UUID> maintained,Context context,Profile before,String policyId,List<ResourceDispute> disputes)throws SQLException {
        if(before.lastPeriod()>=period)return;
        List<Building> active=city.buildings().stream().filter(b->maintained.contains(b.id())).toList();
        for(Building building:active)store.execute("INSERT OR IGNORE INTO evolution_facility(city,building,period) VALUES(?,?,?)",city.id(),building.id(),period);
        store.execute("DELETE FROM evolution_facility WHERE city=? AND period<?",city.id(),period);
        long academy=levels(active,"academy"),markets=levels(active,"market"),barracks=levels(active,"barracks"),wells=levels(active,"well");
        long food=store.stock(city.id()).getOrDefault("minecraft:bread",0L);boolean fed=food>=options.foodPerFacility()*Math.max(1,active.size());
        long required=barracks*(context.atWar()?12:4)*(before.plan().equals("military")?2:1);
        long arrows=store.stock(city.id()).getOrDefault("minecraft:arrow",0L),supply=Math.min(arrows,required);
        if(supply>0)store.stock(city.id(),"minecraft:arrow",arrows-supply);
        double terrainDefense=0;long defendingLevels=0;for(Building b:active)if(b.type().equals("barracks")){Definitions.Terrain terrain=definitions.terrains().get(store.region(b.site().regionKey()).terrain());terrainDefense+=terrain.military()*b.level();defendingLevels+=b.level();}
        terrainDefense=defendingLevels==0?1:terrainDefense/defendingLevels;
        double readiness=required==0?0:Math.min(2,(double)supply/required*terrainDefense);
        store.execute("INSERT INTO evolution_readiness(city,nation,period,provided,required,terrain,readiness) VALUES(?,?,?,?,?,?,?) ON CONFLICT(city) DO UPDATE SET nation=excluded.nation,period=excluded.period,provided=excluded.provided,required=excluded.required,terrain=excluded.terrain,readiness=excluded.readiness",city.id(),city.nationId(),period,supply,required,terrainDefense,readiness);
        long knowledge=bounded(before.knowledge(),academy*Math.max(1,Math.round(core.productionMultiplier()))*(before.plan().equals("culture")?2:1));
        long culture=bounded(before.culture(),academy+context.lifeCulture()+Math.min(10,context.successfulTrades())*(before.plan().equals("commerce")?2:1));
        double prosperity=clamp(before.prosperity()+(fed?1:-3)+Math.min(3,markets)+Math.min(2,wells)-(city.buildings().size()-active.size())*0.5,0,100);
        double stability=clamp(before.stability()+(fed?0.5:-2)+Math.min(2,wells)+(supply>0?Math.min(2,readiness):context.atWar()?-2:0),0,100);
        EventState event=store.view(city.id()).orElseThrow().event();
        if(event!=null&&(city.event()==null||!event.equals(city.event()))){
            store.execute("INSERT INTO evolution_event(city,type,period) VALUES(?,?,?) ON CONFLICT(city,type) DO UPDATE SET period=excluded.period",city.id(),event.type(),period);
            String frontier="";
            if(event.type().equals("border_dispute"))frontier=" evidence="+disputes.size()+" "+disputes.stream().limit(8).map(d->{BorderWitness b=d.border();return "region="+b.regionKey()+" world="+b.worldName()+" peer="+b.peerNation()+" neighbor="+b.neighborChunkX()+","+b.neighborChunkZ()+" relation="+b.relation()+" resource="+d.resource()+" remaining="+d.remaining()+" captured="+b.period();}).toList();
            history(city.id(),period,"event",event.type()+" until="+event.endPeriod()+" aftermath="+event.aftermathEndPeriod()+" weather="+context.weather()+" era="+context.era()+frontier);
        }
        if(event!=null&&period>=event.startPeriod()&&period<event.aftermathEndPeriod())switch(event.type()){
            case "famine","decline","resource_crisis"->{prosperity=clamp(prosperity-2,0,100);stability=clamp(stability-2,0,100);}
            case "revival","commerce_boom","festival"->prosperity=clamp(prosperity+2,0,100);
            case "border_dispute"->stability=clamp(stability-2,0,100);
            default->{}
        }
        long agriculture=produced.getOrDefault("minecraft:wheat",0L),industry=produced.getOrDefault("minecraft:iron_ingot",0L)+produced.getOrDefault("minecraft:oak_planks",0L);
        store.execute("UPDATE evolution_city SET last_period=?,prosperity=?,stability=?,knowledge=?,supply=?,culture=?,agriculture=MIN(1000000000,agriculture+?),industry=MIN(1000000000,industry+?),commerce=MIN(1000000000,commerce+?),military=MIN(1000000000,military+?) WHERE city=?",period,prosperity,stability,knowledge,supply,culture,Math.min(1000,agriculture),Math.min(1000,industry),Math.min(100,context.successfulTrades()),supply,city.id());
        Set<String> regions=new HashSet<>();for(Building b:active)regions.add(b.site().regionKey());
        cultureDynamics.advance(regions,period);
        for(String region:regions)addCulture(region,city.civilization(),cultureDynamics.contribution(1+Math.min(5,academy),policyId));
        CityView settled=new CityView(city.id(),city.nationId(),city.name(),city.civilization(),period,city.stock(),city.buildings(),city.event(),city.lastReport());
        store.cityCapacity(city.id(),capacity(settled,maintained));
        history(city.id(),period,"city","active="+active.size()+" food="+fed+" prosperity="+prosperity+" knowledge="+knowledge+" militarySupply="+supply);
    }
    /** The runtime captures currently owned, physically valid regions on the Paper main thread. */
    public boolean exchange(String receipt,UUID first,UUID second,long period,Set<String> validRegionsA,Set<String> validRegionsB)throws SQLException {
        if(receipt==null||receipt.isBlank()||receipt.length()>200||first.equals(second)||period<0)throw new IllegalArgumentException("Invalid culture exchange");
        Set<String> allowedA=Set.copyOf(validRegionsA),allowedB=Set.copyOf(validRegionsB);
        return store.tx(s->{if(!s.query("SELECT 1 FROM culture_exchange WHERE receipt=?",r->1,receipt).isEmpty())return false;
            CityView a=s.view(first).orElseThrow(),b=s.view(second).orElseThrow();
            Set<String> regionsA=registeredRegions(a),regionsB=registeredRegions(b);
            if(!regionsA.containsAll(allowedA)||!regionsB.containsAll(allowedB))throw new IllegalArgumentException("Culture evidence contains an unregistered or former-nation region");
            for(String region:allowedA)addCulture(region,b.civilization(),2);
            for(String region:allowedB)addCulture(region,a.civilization(),2);
            s.execute("INSERT INTO culture_exchange(receipt,city_a,city_b,period) VALUES(?,?,?,?)",receipt,first,second,period);
            history(first,period,"trade:"+receipt,"Cultural exchange with "+second);history(second,period,"trade:"+receipt,"Cultural exchange with "+first);return true;});
    }
    private static Set<String> registeredRegions(CityView city){Set<String> result=new HashSet<>();for(Building building:city.buildings())if(building.nationId().equals(city.nationId()))result.add(building.site().regionKey());return result;}
    private void addCulture(String region,String tradition,long amount)throws SQLException {store.execute("INSERT INTO region_culture(region,tradition,influence) VALUES(?,?,?) ON CONFLICT(region,tradition) DO UPDATE SET influence=MIN(1000000000,influence+excluded.influence)",region,tradition,amount);}
    private void history(UUID city,long period,String kind,String detail)throws SQLException {
        store.execute("INSERT OR IGNORE INTO evolution_history(city,period,kind,detail) VALUES(?,?,?,?)",city,period,kind,detail);
        store.execute("DELETE FROM evolution_history WHERE city=? AND rowid NOT IN (SELECT rowid FROM evolution_history WHERE city=? ORDER BY period DESC,rowid DESC LIMIT ?)",city,city,options.historyLimit());
    }
    private static long levels(List<Building> buildings,String type){return buildings.stream().filter(b->b.type().equals(type)).mapToLong(Building::level).sum();}
    private static long bounded(long value,long increment){return Math.min(1000000000,Math.addExact(value,increment));}
    private static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,value));}
}

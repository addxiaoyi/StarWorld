package dev.starworld.disaster;

import dev.starworld.disaster.DisasterState.*;
import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.*;
import dev.starworld.persistence.WorldStore;
import java.sql.*;
import java.util.*;

/**
 * Durable disaster response. Callers capture current CoreCity/verified facilities on Paper's
 * main thread. This storage service rechecks its own snapshot, consumes real stock, and writes
 * response/history/recovery receipts inside the enclosing WorldStore settlement transaction.
 */
public final class DisasterResponseService {
    public static final int MAX_HISTORY_PAGE=128;
    private final WorldStore store;
    private final DisasterResponseDefinitions configuration;
    private final Definitions definitions;

    public DisasterResponseService(WorldStore store,DisasterResponseDefinitions configuration)throws SQLException{
        this.store=Objects.requireNonNull(store);this.configuration=Objects.requireNonNull(configuration);this.definitions=configuration.definitions();
        store.tx(s->{
            s.execute("CREATE TABLE IF NOT EXISTS disaster_response_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
            s.execute("INSERT OR IGNORE INTO disaster_response_schema(id,version) VALUES(1,1)");
            if(s.query("SELECT version FROM disaster_response_schema WHERE id=1",r->r.getInt(1)).getFirst()!=1)throw new SQLException("Unsupported disaster response schema");
            s.execute("CREATE TABLE IF NOT EXISTS disaster_response(request_id TEXT PRIMARY KEY,city TEXT NOT NULL REFERENCES city_state(id),nation TEXT NOT NULL,strategy TEXT NOT NULL,event_type TEXT NOT NULL,event_start INTEGER NOT NULL,event_end INTEGER NOT NULL,aftermath_end INTEGER NOT NULL,requested_period INTEGER NOT NULL,effective_period INTEGER NOT NULL,expires_period INTEGER NOT NULL,last_applied_period INTEGER NOT NULL DEFAULT -1,steps INTEGER NOT NULL DEFAULT 0,max_steps INTEGER NOT NULL,budget INTEGER NOT NULL,remaining_budget INTEGER NOT NULL,relief_permille INTEGER NOT NULL,allow_aftermath INTEGER NOT NULL,stability_step INTEGER NOT NULL,prosperity_step INTEGER NOT NULL,required_facilities TEXT NOT NULL,costs TEXT NOT NULL,recovery TEXT NOT NULL,import_receipt TEXT)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS disaster_response_event ON disaster_response(city,nation,event_type,event_start)");
            s.execute("CREATE INDEX IF NOT EXISTS disaster_response_city ON disaster_response(city,requested_period,request_id)");
            s.execute("CREATE TABLE IF NOT EXISTS disaster_response_history(sequence INTEGER PRIMARY KEY AUTOINCREMENT,request_id TEXT NOT NULL REFERENCES disaster_response(request_id),city TEXT NOT NULL,period INTEGER NOT NULL,kind TEXT NOT NULL,detail TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS disaster_response_history_city ON disaster_response_history(city,sequence)");
            s.execute("CREATE TABLE IF NOT EXISTS disaster_recovery_ledger(request_id TEXT NOT NULL REFERENCES disaster_response(request_id),region TEXT NOT NULL,resource TEXT NOT NULL,period INTEGER NOT NULL,amount INTEGER NOT NULL,PRIMARY KEY(request_id,region,resource,period))");
            return null;
        });
    }

    public DisasterResponseDefinitions configuration(){return configuration;}
    public Optional<Response> response(UUID requestId)throws SQLException{return store.query("SELECT * FROM disaster_response WHERE request_id=?",this::read,requestId.toString()).stream().findFirst();}
    public List<Response> responses(UUID city,int limit,int offset)throws SQLException{page(limit,offset);return store.query("SELECT * FROM disaster_response WHERE city=? ORDER BY requested_period DESC,request_id LIMIT ? OFFSET ?",this::read,city,limit,offset);}
    public List<History> history(UUID city,int limit,int offset)throws SQLException{page(limit,offset);return store.query("SELECT request_id,period,kind,detail FROM disaster_response_history WHERE city=? ORDER BY sequence DESC LIMIT ? OFFSET ?",r->new History(UUID.fromString(r.getString(1)),r.getLong(2),r.getString(3),r.getString(4)),city,limit,offset);}

    /** Trusted request boundary: authorization is captured by the controller; all game-state evidence is checked again here. */
    public Response request(UUID requestId,CoreCity current,String strategyId,long period,Set<UUID> verified,String actor)throws SQLException{
        return request(requestId,current,strategyId,period,verified,actor,null,false);
    }
    public Response request(UUID requestId,CoreCity current,String strategyId,long period,Set<UUID> verified,String actor,EventState expectedEvent)throws SQLException{
        return request(requestId,current,strategyId,period,verified,actor,expectedEvent,true);
    }
    private Response request(UUID requestId,CoreCity current,String strategyId,long period,Set<UUID> verified,String actor,EventState expectedEvent,boolean requireExpectedEvent)throws SQLException{
        Objects.requireNonNull(requestId);Objects.requireNonNull(current);Objects.requireNonNull(verified);Objects.requireNonNull(actor);
        if(period<0)throw new IllegalArgumentException("Invalid disaster response period");
        return store.tx(s->{CityView city=s.view(current.cityId()).orElseThrow(()->new IllegalArgumentException("City is not registered"));
            if(!current.cityId().equals(city.id())||!current.nationId().equals(city.nationId())||!active(city.id()))throw new IllegalArgumentException("Current city authority is not verified");
            Optional<Response> existing=response(requestId);
            if(existing.isPresent()){Response value=existing.get();if(!samePayload(value,current,strategyId))throw new IllegalArgumentException("Response request ID conflicts with an earlier payload");return value;}
            Strategy strategy=configuration.strategies().get(strategyId);if(strategy==null)throw new IllegalArgumentException("Unknown disaster response strategy");
            if(requireExpectedEvent&&!Objects.equals(expectedEvent,city.event()))throw new IllegalArgumentException("The city event changed after authority capture");
            List<Response> duplicate=store.query("SELECT * FROM disaster_response WHERE city=? AND nation=? AND event_type=? AND event_start=?",this::read,current.cityId(),current.nationId(),strategy.eventType(),city.event()==null?-1:city.event().startPeriod());
            if(!duplicate.isEmpty())throw new IllegalArgumentException("A response already exists for this city event");
            if(period<city.lastPeriod())throw new IllegalArgumentException("Current request period is not verified");
            EventState event=city.event();if(event==null||!event.type().equals(strategy.eventType())||period<event.startPeriod()||period>=event.aftermathEndPeriod()||(!configuration.data().allowAftermath()&&period>=event.endPeriod()))throw new IllegalArgumentException("The matching disaster event is no longer active");
            Set<UUID> valid=verifiedBuildings(city,current,verified,strategy);
            if(valid.isEmpty())throw new IllegalArgumentException("Required response facilities are not currently verified");
            long active=store.query("SELECT COUNT(*) FROM disaster_response WHERE city=? AND nation=? AND remaining_budget>0 AND steps<max_steps AND expires_period>?",r->r.getLong(1),current.cityId(),current.nationId(),period).getFirst();
            if(active>=configuration.data().maxActivePerCity())throw new IllegalArgumentException("Too many active disaster responses for this city");
            UUID importReceipt=null;if(strategy.importItem()!=null)importReceipt=recentImportReceipt(current,city,strategy,period);
            for(var cost:strategy.costs().entrySet())if(city.stock().getOrDefault(cost.getKey(),0L)<cost.getValue())throw new IllegalArgumentException("Insufficient physical response stock: "+cost.getKey());
            for(var cost:strategy.costs().entrySet())s.stock(city.id(),cost.getKey(),city.stock().getOrDefault(cost.getKey(),0L)-cost.getValue());
            long effective=Math.addExact(period,1),expires=Math.addExact(effective,strategy.durationPeriods());
            Response result=new Response(requestId,current.cityId(),current.nationId(),strategy.id(),event.type(),event.startPeriod(),event.endPeriod(),event.aftermathEndPeriod(),period,effective,expires,-1,0,strategy.maxSteps(),strategy.budget(),strategy.reliefPermille(),configuration.data().allowAftermath(),strategy.stabilityPerStep(),strategy.prosperityPerStep(),strategy.facilities(),strategy.costs(),strategy.recoveryPerStep(),importReceipt);
            s.execute("INSERT INTO disaster_response(request_id,city,nation,strategy,event_type,event_start,event_end,aftermath_end,requested_period,effective_period,expires_period,last_applied_period,steps,max_steps,budget,remaining_budget,relief_permille,allow_aftermath,stability_step,prosperity_step,required_facilities,costs,recovery,import_receipt) VALUES(?,?,?,?,?,?,?,?,?,?,?,-1,0,?,?,?,?,?,?,?,?,?,?,?)",result.requestId(),result.cityId(),result.nationId(),result.strategyId(),result.eventType(),result.eventStart(),result.eventEnd(),result.aftermathEnd(),result.requestedPeriod(),result.effectivePeriod(),result.expiresPeriod(),result.maxSteps(),result.remainingBudget(),result.remainingBudget(),result.reliefPermille(),result.allowAftermath()?1:0,result.stabilityPerStep(),result.prosperityPerStep(),encodeStrings(result.requiredFacilities()),encodeAmounts(result.costs()),encodeAmounts(result.recoveryPerStep()),result.importReceipt());
            history(result,period,"requested","strategy="+strategy.id()+" cost="+result.costs()+" facilities="+result.requiredFacilities()+" import="+result.importReceipt());s.audit(actor,"DISASTER RESPONSE "+requestId+" "+strategy.id());return response(requestId).orElseThrow();
        });
    }

    /** One response snapshot applies city-wide only to the matching negative event factor. */
    public Cycle cycle(CoreCity current,CityView before,long period,Set<UUID> verified)throws SQLException{
        if(period<0)throw new IllegalArgumentException("Invalid disaster cycle period");
        if(!current.cityId().equals(before.id())||!current.nationId().equals(before.nationId())||!active(before.id()))return new Cycle(List.of(),current,before,period,Set.of());
        if(verified.size()>configuration.data().maxEvidenceFacilities())return new Cycle(List.of(),current,before,period,Set.of());
        List<Response> rows=store.query("SELECT * FROM disaster_response WHERE city=? AND nation=? AND steps<max_steps AND remaining_budget>0 AND expires_period>? AND effective_period<=? ORDER BY requested_period,request_id LIMIT 16",this::read,current.cityId(),current.nationId(),period,period);
        List<Response> effective=rows.stream().filter(r->hasFacilities(before,current,verified,r.requiredFacilities())).toList();
        return new Cycle(List.copyOf(effective),current,before,period,Set.copyOf(verified));
    }

    /** Must be called from Simulation.Hooks.completed inside the exact settlement transaction. */
    private RecoveryResult complete(Cycle cycle,CoreCity current,CityView before,long period,Set<UUID> verified)throws SQLException{
        return store.tx(s->completeInside(cycle,current,before,period,verified));
    }
    private RecoveryResult completeInside(Cycle cycle,CoreCity current,CityView before,long period,Set<UUID> verified)throws SQLException{
        Objects.requireNonNull(cycle);if(cycle.responses().isEmpty())return new RecoveryResult(period,0,0,Map.of());
        CityView persisted=store.view(current.cityId()).orElseThrow();
        if(!persisted.nationId().equals(current.nationId())||!active(current.cityId())||persisted.lastPeriod()>=period||before.lastPeriod()>=period)return new RecoveryResult(period,0,0,Map.of());
        Map<String,Map<String,Long>> restored=new HashMap<>();int steps=0,inspected=0;
        Set<String> allowedRegions=new TreeSet<>();for(Building b:before.buildings())if(verified.contains(b.id())&&b.cityId().equals(current.cityId())&&b.nationId().equals(current.nationId()))allowedRegions.add(b.site().regionKey());
        if(allowedRegions.size()>configuration.data().maxEvidenceFacilities())throw new IllegalStateException("Too many registered disaster regions");
        for(Response response:cycle.responses()){
            inspected++;Response latest=response(response.requestId()).orElseThrow();if(!latest.nationId().equals(current.nationId())||period<latest.effectivePeriod()||period>=latest.expiresPeriod()||latest.lastAppliedPeriod()>=period||latest.steps()>=latest.maxSteps()||latest.remainingBudget()==0)continue;
            ProfileRecovery profile=profileRecovery(latest,latest.remainingBudget());
            long budget=latest.remainingBudget()-profile.cost();Map<String,Long> delta=new TreeMap<>();
            outer:for(String region:allowedRegions){if(budget==0)break;Region value=store.region(region);Definitions.Terrain terrain=definitions.terrains().get(value.terrain());if(terrain==null)continue;
                for(var recovery:new TreeMap<>(latest.recoveryPerStep()).entrySet()){
                    if(budget==0)break outer;if(terrain.regeneration().getOrDefault(recovery.getKey(),0L)<=0||!value.reserves().containsKey(recovery.getKey()))continue;
                    long currentAmount=value.reserves().getOrDefault(recovery.getKey(),0L);long capacity=terrain.reserves().getOrDefault(recovery.getKey(),0L);long amount=Math.min(Math.min(recovery.getValue(),budget),Math.max(0,capacity-currentAmount));if(amount<=0)continue;
                    if(store.query("SELECT 1 FROM disaster_recovery_ledger WHERE request_id=? AND region=? AND resource=? AND period=?",r->1,latest.requestId(),region,recovery.getKey(),period).isEmpty()){
                        store.reserve(region,recovery.getKey(),currentAmount+amount);store.execute("INSERT INTO disaster_recovery_ledger(request_id,region,resource,period,amount) VALUES(?,?,?,?,?)",latest.requestId(),region,recovery.getKey(),period,amount);delta.merge(region+"|"+recovery.getKey(),amount,Math::addExact);budget-=amount;
                    }
                    value=store.region(region);
                }
            }
            store.execute("UPDATE disaster_response SET remaining_budget=?,steps=steps+1,last_applied_period=? WHERE request_id=? AND last_applied_period<?",budget,period,latest.requestId(),period);
            store.execute("UPDATE evolution_city SET stability=MIN(100,stability+?),prosperity=MIN(100,prosperity+?) WHERE city=?",profile.stability(),profile.prosperity(),latest.cityId());
            history(latest,period,"recovery","amounts="+delta+" remainingBudget="+budget+" step="+(latest.steps()+1)+" stability+="+profile.stability()+" prosperity+="+profile.prosperity()+" profileBudget="+profile.cost());steps++;
            for(var e:delta.entrySet()){String[] key=e.getKey().split("\\|",2);restored.computeIfAbsent(key[0],ignored->new TreeMap<>()).merge(key[1],e.getValue(),Math::addExact);}
        }
        return new RecoveryResult(period,inspected,steps,restored);
    }

    /** Shared integer budget: stability, then prosperity, then regional resources; fractional profile gains round up. */
    private ProfileRecovery profileRecovery(Response response,long budget)throws SQLException{
        List<double[]> profiles=store.query("SELECT stability,prosperity FROM evolution_city WHERE city=?",r->new double[]{r.getDouble(1),r.getDouble(2)},response.cityId());
        if(profiles.isEmpty())return new ProfileRecovery(0,0,0);
        double[] profile=profiles.getFirst();
        double stability=Math.min(Math.max(0,100-profile[0]),Math.min(response.stabilityPerStep(),budget));
        long stabilityCost=(long)Math.ceil(stability);
        double prosperity=Math.min(Math.max(0,100-profile[1]),Math.min(response.prosperityPerStep(),budget-stabilityCost));
        return new ProfileRecovery(stability,prosperity,stabilityCost+(long)Math.ceil(prosperity));
    }
    private record ProfileRecovery(double stability,double prosperity,long cost){}

    public final class Cycle{
        private final List<Response> responses;private final CoreCity current;private final CityView before;private final long period;private final Set<UUID> verified;
        private Cycle(List<Response> responses,CoreCity current,CityView before,long period,Set<UUID> verified){this.responses=responses;this.current=current;this.before=before;this.period=period;this.verified=verified;}
        public List<Response> responses(){return responses;}
        public double eventFactor(EventState event,double base){if(event==null||responses.isEmpty()||!Double.isFinite(base)||base<0||base>=1)return base;double result=base;for(Response response:responses)if(response.eventType().equals(event.type())&&response.eventStart()==event.startPeriod()&&period>=response.effectivePeriod()&&period<(response.allowAftermath()?response.aftermathEnd():response.eventEnd()))result=result+(1-result)*(response.reliefPermille()/1000.0);return Math.min(1.0,result);}
        public RecoveryResult completed()throws SQLException{return complete(this,current,before,period,verified);}
    }

    private boolean samePayload(Response r,CoreCity city,String strategy){return r.cityId().equals(city.cityId())&&r.nationId().equals(city.nationId())&&r.strategyId().equals(strategy);}
    private Set<UUID> verifiedBuildings(CityView city,CoreCity current,Set<UUID> verified,Strategy strategy){if(verified.size()>configuration.data().maxEvidenceFacilities())throw new IllegalArgumentException("Too many verified disaster facilities");Set<String> types=new HashSet<>();Set<UUID> ids=new HashSet<>();for(Building b:city.buildings())if(verified.contains(b.id())&&b.cityId().equals(city.id())&&b.nationId().equals(current.nationId())){types.add(b.type());ids.add(b.id());}if(!types.containsAll(strategy.facilities()))throw new IllegalArgumentException("All strategy facilities must remain validated and owned by this nation");return Set.copyOf(ids);}
    private boolean hasFacilities(CityView city,CoreCity current,Set<UUID> verified,Set<String> required){Set<String> types=new HashSet<>();for(Building b:city.buildings())if(verified.contains(b.id())&&b.cityId().equals(current.cityId())&&b.nationId().equals(current.nationId()))types.add(b.type());return types.containsAll(required);}
    private UUID recentImportReceipt(CoreCity current,CityView city,Strategy strategy,long period)throws SQLException{
        List<String> orders=store.query("SELECT o.id FROM logistics_order o JOIN logistics_receipt r ON r.order_id=o.id JOIN logistics_cargo c ON c.order_id=o.id WHERE o.state='COMPLETED' AND r.period=o.completed_period AND r.period<=? AND r.period>=? AND c.item=? AND c.amount>=? AND ((o.target_city=? AND o.target_nation=? AND c.side='OFFERED') OR (o.origin_city=? AND o.origin_nation=? AND c.side='REQUESTED')) ORDER BY r.sequence DESC LIMIT 1",r->r.getString(1),period,Math.max(0,period-strategy.receiptAgePeriods()),strategy.importItem(),strategy.importUnits(),current.cityId(),current.nationId(),current.cityId(),current.nationId());
        if(orders.isEmpty())throw new IllegalArgumentException("A recent completed logistics receipt for the configured import is required");return UUID.fromString(orders.getFirst());
    }
    private void history(Response response,long period,String kind,String detail)throws SQLException{store.execute("INSERT INTO disaster_response_history(request_id,city,period,kind,detail) VALUES(?,?,?,?,?)",response.requestId(),response.cityId(),period,kind,detail);}
    private Response read(ResultSet r)throws SQLException{return new Response(UUID.fromString(r.getString("request_id")),UUID.fromString(r.getString("city")),UUID.fromString(r.getString("nation")),r.getString("strategy"),r.getString("event_type"),r.getLong("event_start"),r.getLong("event_end"),r.getLong("aftermath_end"),r.getLong("requested_period"),r.getLong("effective_period"),r.getLong("expires_period"),r.getLong("last_applied_period"),r.getInt("steps"),r.getInt("max_steps"),r.getLong("remaining_budget"),r.getInt("relief_permille"),r.getInt("allow_aftermath")==1,r.getInt("stability_step"),r.getInt("prosperity_step"),decodeStrings(r.getString("required_facilities")),decodeAmounts(r.getString("costs")),decodeAmounts(r.getString("recovery")),r.getString("import_receipt")==null?null:UUID.fromString(r.getString("import_receipt")));}
    private boolean active(UUID city)throws SQLException{return store.query("SELECT active FROM city_authority WHERE city=?",r->r.getInt(1)==1,city).stream().findFirst().orElse(false);}
    private String encodeStrings(Set<String> values){return String.join(",",values.stream().sorted().toList());}
    private Set<String> decodeStrings(String value)throws SQLException{if(value==null||value.length()>2048)throw new SQLException("Invalid response facility payload");Set<String> result=value.isEmpty()?Set.of():Set.of(value.split(","));if(result.size()>4)throw new SQLException("Too many response facilities");return result;}
    private String encodeAmounts(Map<String,Long> values){StringJoiner result=new StringJoiner("\n");values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->result.add(e.getKey()+"="+e.getValue()));return result.toString();}
    private Map<String,Long> decodeAmounts(String value)throws SQLException{if(value==null||value.length()>4096)throw new SQLException("Invalid response amount payload");Map<String,Long> result=new TreeMap<>();if(!value.isEmpty())for(String line:value.split("\n")){int split=line.indexOf('=');if(split<1)throw new SQLException("Corrupt response amount payload");try{String key=line.substring(0,split);long amount=Long.parseLong(line.substring(split+1));if(!key.matches("[a-z0-9_./-]+(:[a-z0-9_./-]+)?")||amount<0||result.put(key,amount)!=null)throw new SQLException("Corrupt response amount payload");}catch(NumberFormatException e){throw new SQLException("Corrupt response amount payload",e);}}return Map.copyOf(result);}
    private void page(int limit,int offset){if(limit<1||limit>Math.min(MAX_HISTORY_PAGE,configuration.data().maxPage())||offset<0||offset>1_000_000)throw new IllegalArgumentException("Disaster page out of range");}
}

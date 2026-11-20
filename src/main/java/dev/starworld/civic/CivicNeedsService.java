package dev.starworld.civic;

import dev.starworld.civic.CivicNeedsDefinitions.Need;
import dev.starworld.civic.CivicNeedsState.*;
import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.persistence.WorldStore;
import java.sql.*;
import java.util.*;

/** Pure SQLite extension. Call from the existing storage worker after current StarCore authorization. */
public final class CivicNeedsService {
    private static final String REQUEST_COLUMNS="id,city,nation,type,name,actor,requested,expires,cooldown,bucket,lifetime,action_kind,action_count,distinct_sources,stability_reward,prosperity_reward,state,completed,actual_stability,actual_prosperity";
    private final WorldStore store;
    private final CivicNeedsDefinitions definitions;
    public CivicNeedsService(WorldStore store,CivicNeedsDefinitions definitions) { this.store=Objects.requireNonNull(store);this.definitions=Objects.requireNonNull(definitions); }
    public CivicNeedsDefinitions definitions() { return definitions; }
    public void initialize() throws SQLException {
        atomic(s -> {
            for(String table:List.of("life_schema","life_action","life_concert","evolution_schema","evolution_city"))
                if(s.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",r->1,table).isEmpty())throw new IllegalStateException("Initialize LifeService and EvolutionService before civic needs");
            if(s.query("SELECT version FROM life_schema WHERE id=1",r->r.getInt(1)).getFirst()!=1||s.query("SELECT version FROM evolution_schema WHERE id=1",r->r.getInt(1)).getFirst()!=1)throw new IllegalStateException("Unsupported life/evolution schema");
            s.execute("CREATE TABLE IF NOT EXISTS civic_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
            var versions=s.query("SELECT version FROM civic_schema WHERE id=1",r->r.getInt(1));if(!versions.isEmpty()&&(versions.getFirst()<1||versions.getFirst()>2))throw new IllegalStateException("Unsupported civic schema; preserve database and upgrade plugin");
            s.execute("CREATE TABLE IF NOT EXISTS civic_clock(city TEXT PRIMARY KEY REFERENCES city_state(id),period INTEGER NOT NULL CHECK(period>=0))");
            createRequestTable("civic_request");
            createRequestChildren("");
            if(!versions.isEmpty()&&versions.getFirst()==1)migrateCooldownWindows();
            s.execute("INSERT INTO civic_schema(id,version) VALUES(1,2) ON CONFLICT(id) DO UPDATE SET version=2");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS civic_one_open_city ON civic_request(city) WHERE state='OPEN'");
            s.execute("CREATE INDEX IF NOT EXISTS civic_request_city_requested ON civic_request(city,requested,id)");
            s.execute("CREATE INDEX IF NOT EXISTS civic_request_cooldown ON civic_request(city,nation,type,requested)");
            s.execute("CREATE INDEX IF NOT EXISTS civic_action_claim_request ON civic_action_claim(request,action)");
            s.execute("CREATE INDEX IF NOT EXISTS civic_history_city_period ON civic_history(city,period,id)");
            s.execute("CREATE INDEX IF NOT EXISTS civic_history_city_id ON civic_history(city,id)");
            s.execute("CREATE INDEX IF NOT EXISTS civic_life_action_candidates ON life_action(city,nation,kind,period,id)");return null;
        });
    }
    private void createRequestTable(String table)throws SQLException {
        store.execute("CREATE TABLE IF NOT EXISTS "+table+"(id TEXT PRIMARY KEY,city TEXT NOT NULL REFERENCES city_state(id),nation TEXT NOT NULL,type TEXT NOT NULL,name TEXT NOT NULL,actor TEXT NOT NULL,requested INTEGER NOT NULL,expires INTEGER NOT NULL,cooldown INTEGER NOT NULL,bucket INTEGER NOT NULL,lifetime INTEGER NOT NULL,action_kind TEXT NOT NULL,action_count INTEGER NOT NULL,distinct_sources INTEGER NOT NULL,stability_reward REAL NOT NULL,prosperity_reward REAL NOT NULL,state TEXT NOT NULL CHECK(state IN ('OPEN','COMPLETED','EXPIRED','INVALIDATED')),completed INTEGER NOT NULL DEFAULT -1,actual_stability REAL NOT NULL DEFAULT 0,actual_prosperity REAL NOT NULL DEFAULT 0)");
    }
    private void createRequestChildren(String suffix)throws SQLException {
        String parent="civic_request"+suffix;
        store.execute("CREATE TABLE IF NOT EXISTS civic_request_cost"+suffix+"(request TEXT NOT NULL REFERENCES "+parent+"(id),item TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>0),PRIMARY KEY(request,item))");
        // Deliberately no FK to life_action: a claimed action UUID remains spent even if life history is archived.
        store.execute("CREATE TABLE IF NOT EXISTS civic_action_claim"+suffix+"(action TEXT PRIMARY KEY,request TEXT NOT NULL REFERENCES "+parent+"(id),source TEXT NOT NULL,claimed_period INTEGER NOT NULL)");
        store.execute("CREATE TABLE IF NOT EXISTS civic_history"+suffix+"(id INTEGER PRIMARY KEY AUTOINCREMENT,city TEXT NOT NULL REFERENCES city_state(id),request TEXT NOT NULL REFERENCES "+parent+"(id),period INTEGER NOT NULL,kind TEXT NOT NULL,detail TEXT NOT NULL,UNIQUE(request,kind))");
    }
    /** Drop only the obsolete numeric-bucket uniqueness; retain frozen terms, IDs and every FK child. */
    private void migrateCooldownWindows()throws SQLException {
        for(String table:List.of("civic_request","civic_request_cost","civic_action_claim","civic_history"))
            if(!store.query("SELECT 1 FROM sqlite_master WHERE name=?",r->1,table+"_v2").isEmpty())throw new SQLException("Unexpected civic migration staging table; preserve database");
        long historySequence=store.query("SELECT seq FROM sqlite_sequence WHERE name='civic_history'",r->r.getLong(1)).stream().findFirst().orElse(0L);
        createRequestTable("civic_request_v2");createRequestChildren("_v2");
        store.execute("INSERT INTO civic_request_v2("+REQUEST_COLUMNS+") SELECT "+REQUEST_COLUMNS+" FROM civic_request");
        store.execute("INSERT INTO civic_request_cost_v2(request,item,amount) SELECT request,item,amount FROM civic_request_cost");
        store.execute("INSERT INTO civic_action_claim_v2(action,request,source,claimed_period) SELECT action,request,source,claimed_period FROM civic_action_claim");
        store.execute("INSERT INTO civic_history_v2(id,city,request,period,kind,detail) SELECT id,city,request,period,kind,detail FROM civic_history");
        for(String table:List.of("civic_request_cost","civic_action_claim","civic_history","civic_request"))store.execute("DROP TABLE "+table);
        for(String table:List.of("civic_request","civic_request_cost","civic_action_claim","civic_history"))store.execute("ALTER TABLE "+table+"_v2 RENAME TO "+table);
        if(historySequence>0){
            if(store.query("SELECT seq FROM sqlite_sequence WHERE name='civic_history'",r->r.getLong(1)).isEmpty())store.execute("INSERT INTO sqlite_sequence(name,seq) VALUES('civic_history',?)",historySequence);
            else store.execute("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name='civic_history'",historySequence);
        }
        if(!store.query("PRAGMA foreign_key_check",r->r.getString(1)).isEmpty())throw new SQLException("Civic migration foreign key validation failed; changes rolled back");
    }
    public Request request(UUID id,CoreCity current,String type,long period,String actor) throws SQLException {
        Objects.requireNonNull(id);Objects.requireNonNull(current);period(period);actor(actor);
        return atomic(s -> {
            authority(current);Optional<Request> previous=request(id);
            if(previous.isPresent()) { Request found=previous.get();if(!found.cityId().equals(current.cityId())||!found.nationId().equals(current.nationId())||!found.type().equals(type)||found.requestedPeriod()!=period||!found.actor().equals(actor))throw new IllegalArgumentException("城市需求请求 ID 已用于其他参数");return found; }
            Need need=definitions.needs().get(type);if(need==null)throw new IllegalArgumentException("未知城市需求");observe(current.cityId(),period);
            var open=s.query("SELECT id,nation,expires FROM civic_request WHERE city=? AND state='OPEN' LIMIT 1",r->new Open(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),r.getLong(3)),current.cityId());
            for(Open row:open) if(!row.nation().equals(current.nationId())||row.expiry()<period) {
                String state=!row.nation().equals(current.nationId())?"INVALIDATED":"EXPIRED";
                s.execute("UPDATE civic_request SET state=? WHERE id=?",state,row.id());history(current.cityId(),row.id(),period,state,"未领取；不扣料、不奖励");
            }
            if(!s.query("SELECT 1 FROM civic_request WHERE city=? AND state='OPEN' LIMIT 1",r->1,current.cityId()).isEmpty())throw new IllegalArgumentException("城市已有未过期需求");
            long expiry=Math.addExact(period,need.lifetimePeriods()),bucket=period/need.cooldownPeriods();
            long windowStart=Math.multiplyExact(bucket,need.cooldownPeriods()),windowEnd=Math.addExact(windowStart,need.cooldownPeriods());
            // Both windows use their own frozen duration. Comparing bucket numbers across configurations is invalid.
            // Every supported duration is <=240, so any overlap must have been requested after windowStart-240.
            if(!s.query("SELECT 1 FROM civic_request WHERE city=? AND nation=? AND type=? AND requested>? AND requested<? AND bucket*cooldown<? AND (bucket+1)*cooldown>? LIMIT 1",r->1,current.cityId(),current.nationId(),type,windowStart-240,windowEnd,windowEnd,windowStart).isEmpty())throw new IllegalArgumentException("该城市需求的绝对冷却窗口与已有请求重叠");
            s.execute("INSERT INTO civic_request(id,city,nation,type,name,actor,requested,expires,cooldown,bucket,lifetime,action_kind,action_count,distinct_sources,stability_reward,prosperity_reward,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN')",id,current.cityId(),current.nationId(),type,need.name(),actor,period,expiry,need.cooldownPeriods(),bucket,need.lifetimePeriods(),need.actionKind(),need.actionCount(),need.distinctSources()?1:0,need.stabilityReward(),need.prosperityReward());
            for(var cost:need.costs().entrySet())s.execute("INSERT INTO civic_request_cost(request,item,amount) VALUES(?,?,?)",id,cost.getKey(),cost.getValue());
            history(current.cityId(),id,period,"REQUESTED",type);s.audit(actor,"CIVIC_REQUEST "+id+" "+type);return request(id).orElseThrow();
        });
    }
    public Receipt complete(UUID requestId,CoreCity current,long currentPeriod,String actor) throws SQLException {
        Objects.requireNonNull(requestId);Objects.requireNonNull(current);period(currentPeriod);actor(actor);
        return atomic(s -> {
            authority(current);Request request=request(requestId).orElseThrow(()->new IllegalArgumentException("城市需求不存在"));
            if(!request.cityId().equals(current.cityId())||!request.nationId().equals(current.nationId()))throw new IllegalArgumentException("城市需求的国家归属已变化");
            observe(current.cityId(),currentPeriod);
            if(request.status()==Status.COMPLETED) { if(currentPeriod<request.completedPeriod())throw new IllegalArgumentException("城市需求周期不能倒退");return receipt(request,false); }
            if(request.status()!=Status.OPEN||currentPeriod<=request.requestedPeriod()||currentPeriod>request.expiryPeriod())throw new IllegalArgumentException("需求尚未生效或已过期；不补离线进度");
            List<Action> actions=candidates(request,currentPeriod);
            if(actions.size()!=request.definition().actionCount())throw new IllegalArgumentException(request.definition().distinctSources()?"需请求后两件或更多不同真实家具的已提交维护凭据；家具登记不计入，缺少家具 API 时暂停此需求":"缺少请求后已提交的宴会/完成演奏生活贡献");
            Map<String,Long> stock=s.stock(current.cityId());
            for(var cost:request.definition().costs().entrySet())if(stock.getOrDefault(cost.getKey(),0L)<cost.getValue())throw new IllegalArgumentException("城市需求库存不足："+cost.getKey()+" × "+cost.getValue());
            s.execute("INSERT OR IGNORE INTO evolution_city(city) VALUES(?)",current.cityId());
            double[] before=s.query("SELECT stability,prosperity FROM evolution_city WHERE city=?",r->new double[]{r.getDouble(1),r.getDouble(2)},current.cityId()).getFirst();
            for(double value:before)if(!Double.isFinite(value)||value<0||value>100)throw new IllegalStateException("Invalid local city profile");
            double stability=Math.min(request.definition().stabilityReward(),100-before[0]),prosperity=Math.min(request.definition().prosperityReward(),100-before[1]);
            for(var cost:request.definition().costs().entrySet())s.stock(current.cityId(),cost.getKey(),stock.getOrDefault(cost.getKey(),0L)-cost.getValue());
            for(Action action:actions)s.execute("INSERT INTO civic_action_claim(action,request,source,claimed_period) VALUES(?,?,?,?)",action.id(),requestId,action.source(),currentPeriod);
            s.execute("UPDATE evolution_city SET stability=MIN(100,stability+?),prosperity=MIN(100,prosperity+?) WHERE city=?",stability,prosperity,current.cityId());
            s.execute("UPDATE civic_request SET state='COMPLETED',completed=?,actual_stability=?,actual_prosperity=? WHERE id=? AND state='OPEN'",currentPeriod,stability,prosperity,requestId);
            history(current.cityId(),requestId,currentPeriod,"COMPLETED","stability="+stability+",prosperity="+prosperity+",actions="+actions.stream().map(Action::id).toList());
            s.audit(actor,"CIVIC_COMPLETE "+requestId);return receipt(request(requestId).orElseThrow(),true);
        });
    }
    public Optional<Request> request(UUID id) throws SQLException { Objects.requireNonNull(id);return store.query("SELECT * FROM civic_request WHERE id=?",this::readRequest,id).stream().findFirst(); }
    /** Historical completion proof only; never allocates actions, changes clocks, or re-awards a reward. */
    public Optional<Receipt> receipt(UUID requestId) throws SQLException {
        Objects.requireNonNull(requestId);return store.tx(s->{Optional<Request> value=request(requestId);return value.isPresent()&&value.get().status()==Status.COMPLETED?Optional.of(receipt(value.get(),false)):Optional.empty();});
    }
    public List<Request> requests(UUID city,int limit,int offset) throws SQLException { Objects.requireNonNull(city);page(limit,offset);return List.copyOf(store.query("SELECT * FROM civic_request WHERE city=? ORDER BY requested DESC,id LIMIT ? OFFSET ?",this::readRequest,city,limit,offset)); }
    public List<History> history(UUID city,int limit,int offset) throws SQLException { Objects.requireNonNull(city);page(limit,offset);return List.copyOf(store.query("SELECT request,period,kind,detail FROM civic_history WHERE city=? ORDER BY period DESC,id DESC LIMIT ? OFFSET ?",r->new History(uuid(r,"request"),r.getLong("period"),r.getString("kind"),r.getString("detail")),city,limit,offset)); }
    private List<Action> candidates(Request request,long current) throws SQLException {
        String base="SELECT a.id,a.source FROM life_action a INDEXED BY civic_life_action_candidates WHERE a.city=? AND a.nation=? AND a.kind=? AND a.eligible=1 AND a.culture>0 AND a.period>=? AND a.period<=? AND NOT EXISTS(SELECT 1 FROM civic_action_claim used WHERE used.action=a.id)";
        if(request.definition().distinctSources())base+=" AND length(a.source)=36 AND NOT EXISTS(SELECT 1 FROM life_action prior INDEXED BY civic_life_action_candidates WHERE prior.city=a.city AND prior.nation=a.nation AND prior.kind=a.kind AND prior.source=a.source AND prior.eligible=1 AND prior.culture>0 AND prior.period>=? AND prior.period<=? AND (prior.period<a.period OR (prior.period=a.period AND prior.id<a.id)) AND NOT EXISTS(SELECT 1 FROM civic_action_claim used_prior WHERE used_prior.action=prior.id))";
        if(request.definition().actionKind().equals("concert"))base+=" AND EXISTS(SELECT 1 FROM life_concert concert WHERE concert.id=a.id AND concert.city=a.city AND concert.nation=a.nation AND concert.state='completed')";
        base+=" ORDER BY a.period,a.id LIMIT ?";
        List<Object> args=new ArrayList<>(List.of(request.cityId(),request.nationId(),request.definition().actionKind(),request.requestedPeriod()+1,Math.min(current,request.expiryPeriod())));
        if(request.definition().distinctSources()){args.add(request.requestedPeriod()+1);args.add(Math.min(current,request.expiryPeriod()));}args.add(request.definition().actionCount());
        List<Action> result=store.query(base,r->new Action(uuid(r,"id"),r.getString("source")),args.toArray());
        if(request.definition().distinctSources())for(Action action:result)try{UUID.fromString(action.source());}catch(IllegalArgumentException e){throw new SQLException("Invalid committed furniture identity",e);}
        return result;
    }
    private Request readRequest(ResultSet r) throws SQLException {
        UUID id=uuid(r,"id");Map<String,Long> costs=new TreeMap<>();store.query("SELECT item,amount FROM civic_request_cost WHERE request=? ORDER BY item LIMIT 17",row->{costs.put(row.getString(1),row.getLong(2));return 0;},id);
        Need snapshot=new Need(r.getString("type"),r.getString("name"),r.getInt("lifetime"),r.getInt("cooldown"),costs,r.getString("action_kind"),r.getInt("action_count"),r.getInt("distinct_sources")==1,r.getDouble("stability_reward"),r.getDouble("prosperity_reward"));
        return new Request(id,uuid(r,"city"),uuid(r,"nation"),snapshot,r.getLong("requested"),r.getLong("expires"),r.getLong("bucket"),r.getString("actor"),Status.valueOf(r.getString("state")),r.getLong("completed"));
    }
    private Receipt receipt(Request request,boolean applied) throws SQLException {
        List<UUID> actions=store.query("SELECT action FROM civic_action_claim WHERE request=? ORDER BY action LIMIT 5",r->UUID.fromString(r.getString(1)),request.id());
        double[] reward=store.query("SELECT actual_stability,actual_prosperity FROM civic_request WHERE id=?",r->new double[]{r.getDouble(1),r.getDouble(2)},request.id()).getFirst();
        return new Receipt(request.id(),request.cityId(),request.nationId(),request.completedPeriod(),actions,request.definition().costs(),reward[0],reward[1],applied);
    }
    private void authority(CoreCity current) throws SQLException {
        if(store.query("SELECT 1 FROM city_state c JOIN city_authority a ON a.city=c.id WHERE c.id=? AND c.nation=? AND a.active=1",r->1,current.cityId(),current.nationId()).isEmpty())throw new IllegalArgumentException("城市当前国家/有效授权不符，请刷新 StarCore 状态");
    }
    private void observe(UUID city,long period) throws SQLException {
        long[] committed=store.query("SELECT c.last_period,COALESCE(l.last_period,-1) FROM city_state c LEFT JOIN life_city l ON l.city=c.id WHERE c.id=?",r->new long[]{r.getLong(1),r.getLong(2)},city).getFirst();
        if(period<Math.max(committed[0],committed[1]))throw new IllegalArgumentException("城市需求周期落后于已提交的城市/生活状态");
        var previous=store.query("SELECT period FROM civic_clock WHERE city=?",r->r.getLong(1),city);if(!previous.isEmpty()&&period<previous.getFirst())throw new IllegalArgumentException("城市需求周期不能倒退");
        store.execute("INSERT INTO civic_clock(city,period) VALUES(?,?) ON CONFLICT(city) DO UPDATE SET period=excluded.period",city,period);
    }
    /** A failed nested civic call cannot leak partial debits when an outer caller handles its exception. */
    private <T> T atomic(WorldStore.Work<T> work) throws SQLException {
        return store.tx(s -> {
            String savepoint="civic_"+UUID.randomUUID().toString().replace("-","");s.execute("SAVEPOINT "+savepoint);
            try {T result=work.run(s);s.execute("RELEASE SAVEPOINT "+savepoint);return result;}
            catch(Exception|Error failure) {
                try{s.execute("ROLLBACK TO SAVEPOINT "+savepoint);s.execute("RELEASE SAVEPOINT "+savepoint);}catch(SQLException rollback){failure.addSuppressed(rollback);}
                throw failure;
            }
        });
    }
    private void history(UUID city,UUID request,long period,String kind,String detail) throws SQLException {
        store.execute("INSERT INTO civic_history(city,request,period,kind,detail) VALUES(?,?,?,?,?)",city,request,period,kind,detail);
        store.execute("DELETE FROM civic_history WHERE city=? AND id NOT IN(SELECT id FROM civic_history WHERE city=? ORDER BY id DESC LIMIT ?)",city,city,definitions.historyLimit());
    }
    private void page(int limit,int offset) {if(limit<1||limit>definitions.maximumPage()||offset<0||offset>definitions.maximumOffset())throw new IllegalArgumentException("Invalid civic page bounds");}
    private static UUID uuid(ResultSet r,String column) throws SQLException {return UUID.fromString(r.getString(column));}
    private static void period(long period) {if(period<0||period>Long.MAX_VALUE-10000)throw new IllegalArgumentException("Invalid civic period");}
    private static void actor(String actor) {if(actor==null||actor.isBlank()||actor.length()>96||actor.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid civic actor");}
    private record Action(UUID id,String source) {}
    private record Open(UUID id,UUID nation,long expiry) {}
}

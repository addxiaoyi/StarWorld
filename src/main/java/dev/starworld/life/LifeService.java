package dev.starworld.life;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.domain.WorldState.Site;
import dev.starworld.life.LifeDefinitions.*;
import dev.starworld.life.LifeState.*;
import dev.starworld.persistence.WorldStore;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Pure-value life/profession ledger. Every debit and its contribution receipt share one SQLite transaction. */
public final class LifeService {
    private final WorldStore store;
    private final LifeDefinitions definitions;
    public LifeService(WorldStore store, LifeDefinitions definitions) { this.store = Objects.requireNonNull(store); this.definitions = Objects.requireNonNull(definitions); }
    public LifeDefinitions definitions() { return definitions; }
    public void initialize() throws SQLException {
        atomic(s -> {
            s.execute("CREATE TABLE IF NOT EXISTS life_schema (id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
            var versions=s.query("SELECT version FROM life_schema WHERE id=1",r->r.getInt(1));
            if (!versions.isEmpty() && (versions.getFirst()<1 || versions.getFirst()>2)) throw new IllegalStateException("Unsupported life schema; preserve database and upgrade plugin");
            boolean legacy=!versions.isEmpty() && versions.getFirst()==1;
            s.execute("CREATE TABLE IF NOT EXISTS life_profile (player TEXT PRIMARY KEY,profession TEXT NOT NULL,changed_period INTEGER NOT NULL,contributed INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS life_city (city TEXT PRIMARY KEY REFERENCES city_state(id),nation TEXT NOT NULL,culture INTEGER NOT NULL DEFAULT 0,decor_period INTEGER NOT NULL DEFAULT -1,decor_bonus REAL NOT NULL DEFAULT 0,last_period INTEGER NOT NULL DEFAULT -1)");
            s.execute("CREATE TABLE IF NOT EXISTS life_action (id TEXT PRIMARY KEY,city TEXT NOT NULL REFERENCES life_city(city),nation TEXT NOT NULL,actor TEXT NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,period INTEGER NOT NULL,culture INTEGER NOT NULL,bonus REAL NOT NULL,until_period INTEGER NOT NULL,fingerprint TEXT NOT NULL,eligible INTEGER NOT NULL DEFAULT 1)");
            if(!s.query("PRAGMA table_info(life_action)",r->r.getString("name")).contains("eligible"))s.execute("ALTER TABLE life_action ADD COLUMN eligible INTEGER NOT NULL DEFAULT 1");
            s.execute("CREATE INDEX IF NOT EXISTS life_action_city_period ON life_action(city,period)");
            s.execute("CREATE INDEX IF NOT EXISTS life_action_actor_period ON life_action(actor,period)");
            s.execute("CREATE TABLE IF NOT EXISTS life_evolution_action (id TEXT PRIMARY KEY REFERENCES life_action(id),consumed_period INTEGER NOT NULL,credited_culture INTEGER NOT NULL CHECK(typeof(credited_culture)='integer' AND credited_culture>=0))");
            if(!s.query("PRAGMA table_info(life_evolution_action)",r->r.getString("name")).contains("credited_culture")) {
                if(!legacy)throw new SQLException("Life schema lacks durable culture progress; preserve database");
                // Rebuild this owned ledger: SQLite JDBC treats ADD COLUMN with CHECK as a query.
                s.execute("CREATE TABLE life_evolution_action_v2_migration (id TEXT PRIMARY KEY REFERENCES life_action(id),consumed_period INTEGER NOT NULL,credited_culture INTEGER NOT NULL CHECK(typeof(credited_culture)='integer' AND credited_culture>=0))");
                s.execute("INSERT INTO life_evolution_action_v2_migration(id,consumed_period,credited_culture) SELECT e.id,e.consumed_period,a.culture FROM life_evolution_action e LEFT JOIN life_action a ON a.id=e.id");
                s.execute("DROP TABLE life_evolution_action");
                s.execute("ALTER TABLE life_evolution_action_v2_migration RENAME TO life_evolution_action");
            }
            s.execute("CREATE TABLE IF NOT EXISTS life_evolution_pending (id TEXT PRIMARY KEY REFERENCES life_action(id),city TEXT NOT NULL REFERENCES life_city(city),nation TEXT NOT NULL,period INTEGER NOT NULL CHECK(period>=0))");
            s.execute("CREATE INDEX IF NOT EXISTS life_evolution_pending_city ON life_evolution_pending(city,nation,period,id)");
            s.execute("CREATE TABLE IF NOT EXISTS life_evolution_budget (city TEXT NOT NULL REFERENCES life_city(city),nation TEXT NOT NULL,period INTEGER NOT NULL CHECK(typeof(period)='integer' AND period>=0),culture INTEGER NOT NULL CHECK(typeof(culture)='integer' AND culture>=0),PRIMARY KEY(city,nation))");
            if(legacy) {
                // Old rows represent completely consumed actions. Keep IDs and original completion periods unchanged.
                if(!s.query("SELECT 1 FROM life_evolution_action e JOIN life_action a ON a.id=e.id WHERE e.credited_culture<>a.culture OR e.credited_culture<1 OR e.credited_culture>10000 OR typeof(a.culture)<>'integer' OR typeof(e.consumed_period)<>'integer' OR e.consumed_period<0 OR e.consumed_period<a.period LIMIT 1",r->1).isEmpty())throw new SQLException("Invalid legacy life culture progress");
                s.execute("INSERT INTO life_evolution_budget(city,nation,period,culture) SELECT a.city,a.nation,e.consumed_period,SUM(e.credited_culture) FROM life_evolution_action e JOIN life_action a ON a.id=e.id JOIN (SELECT a.city,a.nation,MAX(e.consumed_period) AS latest FROM life_evolution_action e JOIN life_action a ON a.id=e.id GROUP BY a.city,a.nation) last ON last.city=a.city AND last.nation=a.nation AND last.latest=e.consumed_period GROUP BY a.city,a.nation,e.consumed_period");
                s.execute("INSERT INTO life_evolution_pending(id,city,nation,period) SELECT a.id,a.city,a.nation,a.period FROM life_action a LEFT JOIN life_evolution_action e ON e.id=a.id WHERE a.eligible=1 AND e.id IS NULL");
            }
            s.execute("CREATE TABLE IF NOT EXISTS life_decoration (furniture TEXT PRIMARY KEY,meta TEXT NOT NULL UNIQUE,city TEXT NOT NULL REFERENCES life_city(city),nation TEXT NOT NULL,artisan TEXT NOT NULL,item TEXT NOT NULL,world TEXT NOT NULL,world_name TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,registered_period INTEGER NOT NULL,UNIQUE(world,x,y,z))");
            s.execute("CREATE INDEX IF NOT EXISTS life_decoration_city ON life_decoration(city)");
            s.execute("CREATE TABLE IF NOT EXISTS life_concert (id TEXT PRIMARY KEY,city TEXT NOT NULL REFERENCES life_city(city),nation TEXT NOT NULL,performer TEXT NOT NULL,world TEXT NOT NULL,world_name TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,start_period INTEGER NOT NULL,started INTEGER NOT NULL,last_note INTEGER NOT NULL,notes INTEGER NOT NULL DEFAULT 0,pitches INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL,fingerprint TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS life_concert_city ON life_concert(city,state)");
            s.execute("CREATE INDEX IF NOT EXISTS life_concert_performer ON life_concert(performer,state)");
            s.execute("CREATE TABLE IF NOT EXISTS life_note (session TEXT NOT NULL REFERENCES life_concert(id),sequence INTEGER NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(session,sequence))");
            s.execute("CREATE TABLE IF NOT EXISTS life_audience (session TEXT NOT NULL REFERENCES life_concert(id),player TEXT NOT NULL,PRIMARY KEY(session,player))");
            s.execute("INSERT INTO life_schema(id,version) VALUES(1,2) ON CONFLICT(id) DO UPDATE SET version=2");
            return null;
        });
    }
    public ProfessionView chooseProfession(UUID player,String profession,long period) throws SQLException {
        Objects.requireNonNull(player); period(period);
        if (!definitions.professions().containsKey(profession)) throw new IllegalArgumentException("未知生活职业");
        return store.tx(s -> {
            ProfessionView before=profession(player);
            if (profession.equals(before.profession())) return before;
            if (!before.profession().equals("none") && period-before.changedPeriod()<definitions.professionCooldownPeriods()) throw new IllegalArgumentException("生活职业切换冷却中");
            s.execute("INSERT INTO life_profile(player,profession,changed_period) VALUES(?,?,?) ON CONFLICT(player) DO UPDATE SET profession=excluded.profession,changed_period=excluded.changed_period",player,profession,period);
            s.audit(player.toString(),"LIFE_PROFESSION "+profession); return profession(player);
        });
    }
    public ProfessionView profession(UUID player) throws SQLException {
        var rows=store.query("SELECT * FROM life_profile WHERE player=?",r->new ProfessionView(player,r.getString("profession"),r.getLong("changed_period"),r.getLong("contributed")),player);
        return rows.isEmpty()?new ProfessionView(player,"none",-1,0):rows.getFirst();
    }
    public ContributionReceipt feast(UUID requestId,CoreCity city,UUID actor,String recipe,long period,Set<String> availableItems) throws SQLException {
        Objects.requireNonNull(requestId); Objects.requireNonNull(actor); period(period);
        Feast f=definitions.feasts().get(recipe); if(f==null)throw new IllegalArgumentException("未知宴会配方");
        String fingerprint=city.cityId()+"|"+city.nationId()+"|"+actor+"|feast|"+recipe;
        return store.tx(s -> {
            sync(city,period); Optional<ContributionReceipt> replay=replay(requestId,fingerprint); if(replay.isPresent())return replay.get();
            if(!s.query("SELECT id FROM life_concert WHERE id=?",r->r.getString(1),requestId).isEmpty())throw new IllegalArgumentException("请求 ID 已用于演奏会");
            requireProfession(actor,f.profession()); available(f.inputs(),availableItems); budget(city.cityId(),actor,period,f.culture());
            consume(city.cityId(),f.inputs());
            return grant(requestId,city,actor,"feast",recipe,period,f.culture(),f.productionBonus(),period+f.durationPeriods(),fingerprint);
        });
    }
    /** Trusted evidence is created only after checking the actual loaded CE furniture. Registration has no reward. */
    public Decoration registerDecoration(CoreCity city,UUID actor,DecorationEvidence evidence,long period) throws SQLException {
        period(period); Objects.requireNonNull(evidence);
        if(!evidence.city().equals(city.cityId()) || !evidence.nation().equals(city.nationId()) || !evidence.actor().equals(actor))throw new IllegalArgumentException("家具归属证据不匹配");
        if(!definitions.decorations().furnitureIds().contains(evidence.itemId()))throw new IllegalArgumentException("此家具不在装饰定义中");
        return store.tx(s -> {
            sync(city,period); requireProfession(actor,definitions.decorations().profession());
            var existing=s.query("SELECT * FROM life_decoration WHERE furniture=?",this::decoration,evidence.furnitureId());
            if(!existing.isEmpty()) {
                Decoration d=existing.getFirst();
                if(d.city().equals(city.cityId()) && d.nation().equals(city.nationId()) && d.artisan().equals(actor) && d.metaEntityId().equals(evidence.metaEntityId()) && d.site().equals(evidence.site()) && d.itemId().equals(evidence.itemId()))return d;
                throw new IllegalArgumentException("家具已登记；原归属需先注销");
            }
            if(decorations(city.cityId()).size()>=definitions.decorations().maximumPerCity())throw new IllegalArgumentException("城市装饰登记已达上限");
            consume(city.cityId(),definitions.decorations().registrationCost()); Site site=evidence.site();
            s.execute("INSERT INTO life_decoration(furniture,meta,city,nation,artisan,item,world,world_name,x,y,z,registered_period) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",evidence.furnitureId(),evidence.metaEntityId(),city.cityId(),city.nationId(),actor,evidence.itemId(),site.worldId(),site.worldName(),site.x(),site.y(),site.z(),period);
            s.audit(actor.toString(),"LIFE_DECORATION_REGISTER "+evidence.furnitureId());
            return new Decoration(evidence.furnitureId(),evidence.metaEntityId(),city.cityId(),city.nationId(),actor,evidence.itemId(),site,period);
        });
    }
    public void removeDecoration(UUID city,UUID furniture) throws SQLException {
        store.tx(s->{s.execute("DELETE FROM life_decoration WHERE city=? AND furniture=?",city,furniture);s.execute("UPDATE life_city SET decor_bonus=0 WHERE city=?",city);return null;});
    }
    public List<Decoration> decorations(UUID city) throws SQLException {return store.query("SELECT * FROM life_decoration WHERE city=? ORDER BY furniture",this::decoration,city);}
    public LifeView settleDecorations(CoreCity city,long period,Set<UUID> verifiedFurnitureIds) throws SQLException {
        period(period); Set<UUID> verified=Set.copyOf(verifiedFurnitureIds);
        return store.tx(s -> {
            sync(city,period);
            long previous=s.query("SELECT decor_period FROM life_city WHERE city=?",r->r.getLong(1),city.cityId()).getFirst();
            if(previous>=period)return view(city.cityId(),period);
            double bonus=0;
            for(Decoration d:decorations(city.cityId())) {
                if(!d.nation().equals(city.nationId()) || !verified.contains(d.furnitureId()) || !definitions.decorations().furnitureIds().contains(d.itemId()) || !profession(d.artisan()).profession().equals(definitions.decorations().profession()))continue;
                int culture=definitions.decorations().culturePerPeriod();
                if(!hasBudget(city.cityId(),d.artisan(),period,culture) || !canConsume(city.cityId(),definitions.decorations().upkeep()))continue;
                consume(city.cityId(),definitions.decorations().upkeep());
                UUID id=stable("decoration|"+city.cityId()+"|"+city.nationId()+"|"+period+"|"+d.furnitureId());
                grant(id,city,d.artisan(),"decoration",d.furnitureId().toString(),period,culture,0,period,"decoration|"+id);
                bonus+=definitions.decorations().productionBonus();
            }
            s.execute("UPDATE life_city SET decor_period=?,decor_bonus=? WHERE city=?",period,Math.min(bonus,definitions.maximumProductionBonus()),city.cityId());
            return view(city.cityId(),period);
        });
    }
    public ConcertView startConcert(UUID requestId,CoreCity city,UUID actor,Site site,long period,long nowMillis,Set<String> availableItems) throws SQLException {
        Objects.requireNonNull(requestId);Objects.requireNonNull(site);period(period);if(nowMillis<0)throw new IllegalArgumentException("Invalid concert time");
        String fingerprint=city.cityId()+"|"+city.nationId()+"|"+actor+"|"+site.anchorKey();
        return store.tx(s -> {
            sync(city,period);var previous=s.query("SELECT fingerprint FROM life_concert WHERE id=?",r->r.getString(1),requestId);
            if(!previous.isEmpty()){if(!previous.getFirst().equals(fingerprint))throw new IllegalArgumentException("演奏会请求 ID 已用于其他动作");return concert(requestId);}
            if(!s.query("SELECT id FROM life_action WHERE id=?",r->r.getString(1),requestId).isEmpty())throw new IllegalArgumentException("请求 ID 已使用");
            ConcertRules c=definitions.concerts();requireProfession(actor,c.profession());available(c.inputs(),availableItems);budget(city.cityId(),actor,period,c.culture());
            s.execute("UPDATE life_concert SET state='expired' WHERE (performer=? OR city=?) AND state='active' AND started<?",actor,city.cityId(),nowMillis-c.maximumDurationMillis());
            if(!s.query("SELECT id FROM life_concert WHERE performer=? AND state='active'",r->r.getString(1),actor).isEmpty())throw new IllegalArgumentException("已有进行中的演奏会");
            if(s.query("SELECT COUNT(*) FROM life_concert WHERE city=? AND state='active'",r->r.getInt(1),city.cityId()).getFirst()>=32)throw new IllegalArgumentException("城市进行中演奏会已达上限");
            var last=s.query("SELECT MAX(period) FROM life_action WHERE actor=? AND kind='concert'",r->{long v=r.getLong(1);return r.wasNull()?-1000000L:v;},actor).getFirst();
            if(period-last<c.cooldownPeriods())throw new IllegalArgumentException("演奏会贡献冷却中");
            consume(city.cityId(),c.inputs());
            s.execute("INSERT INTO life_concert(id,city,nation,performer,world,world_name,x,y,z,start_period,started,last_note,state,fingerprint) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'active',?)",requestId,city.cityId(),city.nationId(),actor,site.worldId(),site.worldName(),site.x(),site.y(),site.z(),period,nowMillis,nowMillis,fingerprint);
            s.audit(actor.toString(),"LIFE_CONCERT_START "+requestId);return concert(requestId);
        });
    }
    /** Only a platform-created receipt for an actual audible note can advance a session. */
    public ConcertView note(NoteReceipt receipt) throws SQLException {
        Objects.requireNonNull(receipt);return store.tx(s -> {
            ConcertView c=concert(receipt.session());
            if(!c.performer().equals(receipt.performer()) || !c.city().equals(receipt.city()) || !c.nation().equals(receipt.nation()))throw new IllegalArgumentException("演奏凭证归属不符");
            String fingerprint=receipt.pitch()+"|"+receipt.millis()+"|"+receipt.audience().stream().map(UUID::toString).sorted().toList();
            var before=s.query("SELECT fingerprint FROM life_note WHERE session=? AND sequence=?",r->r.getString(1),c.id(),receipt.sequence());
            if(!before.isEmpty()){if(!before.getFirst().equals(fingerprint))throw new IllegalArgumentException("音符序号已使用");return c;}
            if(!c.state().equals("active"))throw new IllegalArgumentException("演奏会已结束");
            if(!currentNation(c.city()).equals(c.nation()))throw new IllegalArgumentException("城市国家已变化，演奏会已失效");
            requireProfession(c.performer(),definitions.concerts().profession());ConcertRules r=definitions.concerts();
            if(receipt.sequence()!=c.notes()+1 || receipt.pitch()<0 || receipt.pitch()>24 || c.notes()>=r.maximumNotes())throw new IllegalArgumentException("音符序号、音高或数量无效");
            long gap=receipt.millis()-c.lastNoteMillis(),duration=receipt.millis()-c.startedMillis();
            if(gap<r.noteGapMillis() || gap>r.maximumSilenceMillis() || duration<0 || duration>r.maximumDurationMillis())throw new IllegalArgumentException("音符间隔或演奏时间无效");
            if(receipt.audience().contains(c.performer()) || receipt.audience().size()<r.audience() || receipt.audience().size()>16)throw new IllegalArgumentException("现场听众不足或无效");
            s.execute("INSERT INTO life_note(session,sequence,fingerprint) VALUES(?,?,?)",c.id(),receipt.sequence(),fingerprint);
            for(UUID player:receipt.audience())s.execute("INSERT OR IGNORE INTO life_audience(session,player) VALUES(?,?)",c.id(),player);
            s.execute("UPDATE life_concert SET notes=notes+1,pitches=?,last_note=? WHERE id=?",c.pitchMask()|(1L<<receipt.pitch()),receipt.millis(),c.id());return concert(c.id());
        });
    }
    public ContributionReceipt finishConcert(UUID session,CoreCity city,UUID actor,long period,long nowMillis) throws SQLException {
        period(period);return store.tx(s -> {
            sync(city,period);ConcertView c=concert(session);
            if(!c.city().equals(city.cityId()) || !c.nation().equals(city.nationId()) || !c.performer().equals(actor))throw new IllegalArgumentException("演奏会归属已失效");
            String fingerprint="concert|"+session;Optional<ContributionReceipt> replay=replay(session,fingerprint);if(replay.isPresent())return replay.get();
            if(!c.state().equals("active"))throw new IllegalArgumentException("演奏会已结束");
            ConcertRules r=definitions.concerts();requireProfession(actor,r.profession());long elapsed=nowMillis-c.startedMillis();
            if(elapsed<r.minimumDurationMillis() || elapsed>r.maximumDurationMillis() || nowMillis<c.lastNoteMillis() || nowMillis-c.lastNoteMillis()>r.maximumSilenceMillis() || c.notes()<r.minimumNotes() || Long.bitCount(c.pitchMask())<r.distinctPitches() || c.audience()<r.audience())throw new IllegalArgumentException("演奏需达到时长、有效音符、多种音高和现场听众要求");
            budget(city.cityId(),actor,period,r.culture());
            var last=s.query("SELECT MAX(period) FROM life_action WHERE actor=? AND kind='concert'",q->{long v=q.getLong(1);return q.wasNull()?-1000000L:v;},actor).getFirst();
            if(period-last<r.cooldownPeriods())throw new IllegalArgumentException("演奏会贡献冷却中");
            s.execute("UPDATE life_concert SET state='completed' WHERE id=?",session);
            return grant(session,city,actor,"concert","performance",period,r.culture(),r.productionBonus(),period+r.durationPeriods(),fingerprint);
        });
    }
    public void cancelConcert(UUID session,UUID performer) throws SQLException {store.execute("UPDATE life_concert SET state='cancelled' WHERE id=? AND performer=? AND state='active'",session,performer);}
    public ConcertView concert(UUID id) throws SQLException {
        var rows=store.query("SELECT c.*,(SELECT COUNT(*) FROM life_audience a WHERE a.session=c.id) AS audience FROM life_concert c WHERE id=?",this::concert,id);
        if(rows.isEmpty())throw new IllegalArgumentException("演奏会不存在");return rows.getFirst();
    }
    public List<ConcertView> concerts(UUID city) throws SQLException {return store.query("SELECT c.*,(SELECT COUNT(*) FROM life_audience a WHERE a.session=c.id) AS audience FROM life_concert c WHERE city=? AND state='active' ORDER BY started LIMIT 32",this::concert,city);}
    public LifeView view(UUID city,long period) throws SQLException {
        period(period);return store.tx(s -> {
            var rows=s.query("SELECT * FROM life_city WHERE city=?",r->new long[]{r.getLong("culture"),r.getLong("decor_period")},city);
            if(rows.isEmpty())return new LifeView(city,currentNation(city),0,1,List.of(),List.of(),-1);
            return new LifeView(city,currentNation(city),rows.getFirst()[0],productionMultiplier(city,period),decorations(city),concerts(city),rows.getFirst()[1]);
        });
    }
    public double productionMultiplier(UUID city,long period) throws SQLException {
        period(period);return store.tx(s -> {
            UUID nation=currentNation(city);
            double sum=s.query("SELECT MAX(bonus) FROM life_action WHERE city=? AND nation=? AND eligible=1 AND period<=? AND until_period>? GROUP BY kind,source",r->r.getDouble(1),city,nation,period,period).stream().mapToDouble(Double::doubleValue).sum();
            var decor=s.query("SELECT decor_bonus FROM life_city WHERE city=? AND nation=? AND decor_period=?",r->r.getDouble(1),city,nation,period);
            if(!decor.isEmpty())sum+=decor.getFirst();return 1+Math.min(definitions.maximumProductionBonus(),Math.max(0,sum));
        });
    }
    /** Only committed contributions in this period and current nation; cumulative culture must not be re-awarded. */
    public long cultureInPeriod(UUID city,long period) throws SQLException {
        period(period);return store.tx(s->s.query("SELECT COALESCE(SUM(culture),0) FROM life_action WHERE city=? AND nation=? AND period=? AND eligible=1",r->r.getLong(1),city,currentNation(city),period).getFirst());
    }
    /** Exact-ID historical read; never grants culture, advances time, or reveals the contributing actor. */
    public Optional<EvolutionCredit> evolutionCredit(UUID actionId) throws SQLException {
        Objects.requireNonNull(actionId);
        var rows=store.query("SELECT a.id,a.city,a.nation,a.culture,COALESCE(e.credited_culture,0),COALESCE(e.consumed_period,-1),p.id IS NOT NULL FROM life_action a LEFT JOIN life_evolution_action e ON e.id=a.id LEFT JOIN life_evolution_pending p ON p.id=a.id WHERE a.id=?",r->{
            long original=integer(r,4),credited=integer(r,5),last=integer(r,6),pending=integer(r,7);
            if(original<1||original>10000||credited<0||credited>original||last< -1||(credited==0)!=(last==-1)||pending<0||pending>1)throw new SQLException("Invalid saved life culture progress");
            return new EvolutionCredit(uuid(r,"id"),uuid(r,"city"),uuid(r,"nation"),original,credited,last,pending==1);
        },actionId);
        return rows.stream().findFirst();
    }
    /** Bounded pending queue; old large actions carry their remaining credit across actual city periods. */
    public long drainEvolutionCulture(UUID city,long period) throws SQLException {
        period(period);return atomic(s->{
            UUID nation=currentNation(city);
            var budget=s.query("SELECT period,culture FROM life_evolution_budget WHERE city=? AND nation=?",r->new long[]{integer(r,1),integer(r,2)},city,nation);
            long used=0;
            if(!budget.isEmpty()) {long[] saved=budget.getFirst();if(saved[0]>period)throw new IllegalArgumentException("生活文化归集周期不能倒退");if(saved[0]<0||saved[1]<0||saved[1]>1_000_000_000L)throw new SQLException("Invalid saved life culture budget");if(saved[0]==period)used=saved[1];}
            long remaining=definitions.cityCulturePerPeriod()-used;if(remaining<=0)return 0L;
            record Pending(UUID id,UUID city,UUID nation,long period,long culture,long credited,long eligible,long lastCredited,long queuedPeriod){}
            var pending=s.query("SELECT a.id,a.city,a.nation,a.period,a.culture,COALESCE(e.credited_culture,0),a.eligible,COALESCE(e.consumed_period,-1),p.period FROM life_evolution_pending p INDEXED BY life_evolution_pending_city JOIN life_action a ON a.id=p.id LEFT JOIN life_evolution_action e ON e.id=a.id WHERE p.city=? AND p.nation=? AND p.period<=? ORDER BY p.period,p.id LIMIT 500",r->new Pending(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),UUID.fromString(r.getString(3)),integer(r,4),integer(r,5),integer(r,6),integer(r,7),integer(r,8),integer(r,9)),city,nation,period);
            long total=0;
            for(Pending action:pending){
                if(!city.equals(action.city())||!nation.equals(action.nation())||action.period()<0||action.period()>period||action.queuedPeriod()!=action.period()||action.culture()<1||action.culture()>10000||action.credited()<0||action.credited()>action.culture()||action.eligible()<0||action.eligible()>1||action.lastCredited()< -1||action.lastCredited()>period||(action.credited()==0)!=(action.lastCredited()==-1))throw new SQLException("Invalid queued life culture progress");
                if(action.eligible()==0||action.credited()==action.culture()){s.execute("DELETE FROM life_evolution_pending WHERE id=?",action.id());continue;}
                long amount=Math.min(remaining-total,action.culture()-action.credited());
                if(amount<=0)break;
                long credited=action.credited()+amount;
                s.execute("INSERT INTO life_evolution_action(id,consumed_period,credited_culture) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET consumed_period=excluded.consumed_period,credited_culture=excluded.credited_culture",action.id(),period,credited);
                if(credited==action.culture())s.execute("DELETE FROM life_evolution_pending WHERE id=?",action.id());
                total+=amount;if(total==remaining)break;
            }
            if(total>0)s.execute("INSERT INTO life_evolution_budget(city,nation,period,culture) VALUES(?,?,?,?) ON CONFLICT(city,nation) DO UPDATE SET period=excluded.period,culture=excluded.culture",city,nation,period,used+total);
            return total;
        });
    }
    private void sync(CoreCity city,long period) throws SQLException {
        store.ensureCity(city);
        store.execute("INSERT OR IGNORE INTO life_city(city,nation) VALUES(?,?)",city.cityId(),city.nationId());
        var rows=store.query("SELECT nation,last_period FROM life_city WHERE city=?",r->new Object[]{r.getString(1),r.getLong(2)},city.cityId());
        if(period<(long)rows.getFirst()[1])throw new IllegalArgumentException("生活结算周期不能倒退");
        if(!city.nationId().toString().equals(rows.getFirst()[0])) {
            store.execute("UPDATE life_city SET nation=?,decor_bonus=0,decor_period=-1 WHERE city=?",city.nationId(),city.cityId());
            store.execute("UPDATE life_action SET eligible=0 WHERE city=?",city.cityId());
            store.execute("DELETE FROM life_evolution_pending WHERE city=?",city.cityId());
            store.execute("UPDATE life_concert SET state='ownership_changed' WHERE city=? AND state='active'",city.cityId());
        }
        store.execute("UPDATE life_city SET last_period=? WHERE city=?",period,city.cityId());
    }
    private UUID currentNation(UUID city) throws SQLException {var rows=store.query("SELECT nation FROM city_state WHERE id=?",r->UUID.fromString(r.getString(1)),city);if(rows.isEmpty())throw new IllegalArgumentException("城市尚未登记");return rows.getFirst();}
    private void requireProfession(UUID actor,String required) throws SQLException {if(!profession(actor).profession().equals(required))throw new IllegalArgumentException("需要生活职业："+required);}
    private void available(Map<String,Long> costs,Set<String> available) {if(!available.containsAll(costs.keySet()))throw new IllegalArgumentException("对应自定义物品未加载；宴会或演奏已停用");}
    private boolean canConsume(UUID city,Map<String,Long> costs) throws SQLException {Map<String,Long> stock=store.stock(city);return costs.entrySet().stream().allMatch(e->stock.getOrDefault(e.getKey(),0L)>=e.getValue());}
    private void consume(UUID city,Map<String,Long> costs) throws SQLException {Map<String,Long> stock=store.stock(city);for(var e:costs.entrySet())if(stock.getOrDefault(e.getKey(),0L)<e.getValue())throw new IllegalArgumentException("城市仓库缺少 "+e.getKey()+" × "+e.getValue());for(var e:costs.entrySet())store.stock(city,e.getKey(),stock.get(e.getKey())-e.getValue());}
    private boolean hasBudget(UUID city,UUID actor,long period,int amount) throws SQLException {
        long player=store.query("SELECT COALESCE(SUM(culture),0) FROM life_action WHERE actor=? AND period=?",r->r.getLong(1),actor,period).getFirst();
        long town=store.query("SELECT COALESCE(SUM(culture),0) FROM life_action WHERE city=? AND period=?",r->r.getLong(1),city,period).getFirst();
        long culture=store.query("SELECT culture FROM life_city WHERE city=?",r->r.getLong(1),city).getFirst();
        return player+amount<=definitions.playerCulturePerPeriod() && town+amount<=definitions.cityCulturePerPeriod() && culture+amount<=definitions.cultureLimit();
    }
    private void budget(UUID city,UUID actor,long period,int amount) throws SQLException {if(!hasBudget(city,actor,period,amount))throw new IllegalArgumentException("本周期个人或城市生活贡献已达上限");}
    private Optional<ContributionReceipt> replay(UUID id,String fingerprint) throws SQLException {
        var rows=store.query("SELECT * FROM life_action WHERE id=?",r->{if(!r.getString("fingerprint").equals(fingerprint))throw new SQLException("请求 ID 已用于其他生活动作");return receipt(r,false);},id);return rows.stream().findFirst();
    }
    private ContributionReceipt grant(UUID id,CoreCity city,UUID actor,String kind,String source,long period,int culture,double bonus,long until,String fingerprint) throws SQLException {
        store.execute("INSERT INTO life_action(id,city,nation,actor,kind,source,period,culture,bonus,until_period,fingerprint) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,city.cityId(),city.nationId(),actor,kind,source,period,culture,bonus,until,fingerprint);
        store.execute("INSERT INTO life_evolution_pending(id,city,nation,period) VALUES(?,?,?,?)",id,city.cityId(),city.nationId(),period);
        store.execute("UPDATE life_city SET culture=culture+? WHERE city=?",culture,city.cityId());
        store.execute("UPDATE life_profile SET contributed=contributed+? WHERE player=?",culture,actor);
        store.audit(actor.toString(),"LIFE_CONTRIBUTION "+id+" "+kind+" "+culture);
        return new ContributionReceipt(id,city.cityId(),actor,kind,source,period,culture,bonus,until,true);
    }
    private ContributionReceipt receipt(ResultSet r,boolean applied) throws SQLException {return new ContributionReceipt(uuid(r,"id"),uuid(r,"city"),uuid(r,"actor"),r.getString("kind"),r.getString("source"),r.getLong("period"),r.getInt("culture"),r.getDouble("bonus"),r.getLong("until_period"),applied);}
    private Decoration decoration(ResultSet r) throws SQLException {return new Decoration(uuid(r,"furniture"),uuid(r,"meta"),uuid(r,"city"),uuid(r,"nation"),uuid(r,"artisan"),r.getString("item"),site(r),r.getLong("registered_period"));}
    private ConcertView concert(ResultSet r) throws SQLException {return new ConcertView(uuid(r,"id"),uuid(r,"city"),uuid(r,"nation"),uuid(r,"performer"),site(r),r.getLong("start_period"),r.getLong("started"),r.getLong("last_note"),r.getInt("notes"),r.getLong("pitches"),r.getInt("audience"),r.getString("state"));}
    private static Site site(ResultSet r) throws SQLException {return new Site(uuid(r,"world"),r.getString("world_name"),r.getInt("x"),r.getInt("y"),r.getInt("z"));}
    private static UUID uuid(ResultSet r,String column) throws SQLException{return UUID.fromString(r.getString(column));}
    private static UUID stable(String input){return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8));}
    private static long integer(ResultSet row,int column)throws SQLException {Object value=row.getObject(column);if(!(value instanceof Integer)&&!(value instanceof Long))throw new SQLException("Non-integral saved life culture progress");return ((Number)value).longValue();}
    private <T> T atomic(WorldStore.Work<T> work)throws SQLException {
        return store.tx(s->{String point="life_"+UUID.randomUUID().toString().replace("-","");s.execute("SAVEPOINT "+point);
            try{T result=work.run(s);s.execute("RELEASE SAVEPOINT "+point);return result;}
            catch(Exception|Error failure){try{s.execute("ROLLBACK TO SAVEPOINT "+point);s.execute("RELEASE SAVEPOINT "+point);}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
        });
    }
    private static void period(long period){if(period<0 || period>Long.MAX_VALUE-10000)throw new IllegalArgumentException("Invalid life period");}
}

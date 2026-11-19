package dev.starworld.persistence;

import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** One connection, serialized transactions. This database contains ONLY StarWorld extension state. */
public final class WorldStore implements AutoCloseable {
    private final Connection connection;
    private final long stockLimit;
    public WorldStore(Path path, long stockLimit) throws Exception {
        this.stockLimit=stockLimit;
        Files.createDirectories(path.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");
        connection=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
        try {
            try (Statement s=connection.createStatement()) {
                try(ResultSet r=s.executeQuery("PRAGMA user_version")) { if(r.next() && r.getInt(1)>2) throw new SQLException("Database newer than this StarWorld version"); }
                s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
            }
            migrate();
        }catch(Exception e){try{connection.close();}catch(SQLException close){e.addSuppressed(close);}throw e;}
    }
    private void migrate() throws SQLException {
        tx(c -> {
            execute("CREATE TABLE IF NOT EXISTS city_state (id TEXT PRIMARY KEY,nation TEXT NOT NULL,name TEXT NOT NULL,civilization TEXT NOT NULL DEFAULT 'agrarian',last_period INTEGER NOT NULL DEFAULT -1,last_report TEXT NOT NULL DEFAULT '',event_type TEXT,event_start INTEGER,event_end INTEGER,aftermath_end INTEGER)");
            execute("CREATE TABLE IF NOT EXISTS stock (city TEXT NOT NULL REFERENCES city_state(id),item TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>=0),PRIMARY KEY(city,item))");
            execute("CREATE TABLE IF NOT EXISTS region (id TEXT PRIMARY KEY,terrain TEXT NOT NULL,last_regen INTEGER NOT NULL DEFAULT -1)");
            execute("CREATE TABLE IF NOT EXISTS nation_state (id TEXT PRIMARY KEY,civilization TEXT NOT NULL DEFAULT 'agrarian',changed_period INTEGER NOT NULL DEFAULT -1000000)");
            execute("CREATE TABLE IF NOT EXISTS reserve (region TEXT NOT NULL REFERENCES region(id),resource TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>=0),PRIMARY KEY(region,resource))");
            execute("CREATE TABLE IF NOT EXISTS building (id TEXT PRIMARY KEY,city TEXT NOT NULL REFERENCES city_state(id),nation TEXT NOT NULL,type TEXT NOT NULL,world TEXT NOT NULL,world_name TEXT NOT NULL,x INTEGER,y INTEGER,z INTEGER,level INTEGER NOT NULL DEFAULT 1,UNIQUE(world,x,y,z))");
            execute("CREATE TABLE IF NOT EXISTS settlement (city TEXT NOT NULL REFERENCES city_state(id),period INTEGER NOT NULL,report TEXT NOT NULL,PRIMARY KEY(city,period))");
            execute("CREATE TABLE IF NOT EXISTS transfer (id TEXT PRIMARY KEY,player TEXT NOT NULL,city TEXT NOT NULL REFERENCES city_state(id),direction TEXT NOT NULL,item TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>0),state TEXT NOT NULL,created INTEGER NOT NULL,decision TEXT)");
            execute("PRAGMA user_version=1");
            execute("CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL)");
            execute("CREATE INDEX IF NOT EXISTS building_city ON building(city)");
            execute("CREATE INDEX IF NOT EXISTS transfer_pending ON transfer(state)");
            execute("PRAGMA user_version=2");
            return null;
        });
    }
    @FunctionalInterface public interface Work<T> { T run(WorldStore store) throws Exception; }
    public synchronized <T> T tx(Work<T> work) throws SQLException {
        if(!connection.getAutoCommit()) { try { return work.run(this); } catch(Exception e) { throw sql(e); } }
        connection.setAutoCommit(false);
        try { T result=work.run(this); connection.commit(); return result; }
        catch(Throwable e) { try{connection.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}if(e instanceof Error error)throw error;throw sql((Exception)e); }
        finally { connection.setAutoCommit(true); }
    }
    private static SQLException sql(Exception e) { return e instanceof SQLException se ? se : new SQLException(e.getMessage(),e); }
    public synchronized void execute(String sql,Object... args) throws SQLException { try(PreparedStatement p=connection.prepareStatement(sql)) { bind(p,args); p.executeUpdate(); } }
    private static void bind(PreparedStatement p,Object[] args) throws SQLException { for(int i=0;i<args.length;i++) p.setObject(i+1,args[i] instanceof UUID ? args[i].toString():args[i]); }
    @FunctionalInterface private interface Mapper<T> { T map(ResultSet r) throws SQLException; }
    private <T> List<T> query(String sql,Mapper<T> mapper,Object... args) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement(sql)) { bind(p,args); try(ResultSet r=p.executeQuery()) { List<T> list=new ArrayList<>();while(r.next()) list.add(mapper.map(r));return list; } }
    }
    public synchronized void ensureCity(CoreCity city) throws SQLException {
        execute("INSERT OR IGNORE INTO nation_state(id) VALUES(?)",city.nationId());
        execute("INSERT INTO city_state(id,nation,name) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET nation=excluded.nation,name=excluded.name",city.cityId(),city.nationId(),city.cityName());
        execute("UPDATE city_state SET civilization=(SELECT civilization FROM nation_state WHERE id=?) WHERE id=?",city.nationId(),city.cityId());
    }
    public synchronized List<UUID> cities(int limit,int offset) throws SQLException { return query("SELECT id FROM city_state ORDER BY id LIMIT ? OFFSET ?",r->UUID.fromString(r.getString(1)),limit,offset); }
    public synchronized List<UUID> cities() throws SQLException { return query("SELECT id FROM city_state ORDER BY id",r->UUID.fromString(r.getString(1))); }
    public synchronized Optional<CityView> view(UUID id) throws SQLException {
        List<CityView> rows=query("SELECT * FROM city_state WHERE id=?",r->new CityView(id,UUID.fromString(r.getString("nation")),r.getString("name"),r.getString("civilization"),r.getLong("last_period"),Map.of(),List.of(),r.getString("event_type")==null ? null : new EventState(r.getString("event_type"),r.getLong("event_start"),r.getLong("event_end"),r.getLong("aftermath_end")),r.getString("last_report")),id);
        if(rows.isEmpty()) return Optional.empty(); CityView c=rows.getFirst();
        return Optional.of(new CityView(c.id(),c.nationId(),c.name(),c.civilization(),c.lastPeriod(),stock(id),buildings(id),c.event(),c.lastReport()));
    }
    public synchronized Map<String,Long> stock(UUID city) throws SQLException {
        Map<String,Long> result=new TreeMap<>();query("SELECT item,amount FROM stock WHERE city=?",r->{ result.put(r.getString(1),r.getLong(2));return 0; },city);return result;
    }
    public synchronized void stock(UUID city,String item,long amount) throws SQLException {
        if(amount<0 || amount>stockLimit) throw new SQLException("Stock capacity exceeded: "+item);
        execute("INSERT INTO stock(city,item,amount) VALUES(?,?,?) ON CONFLICT(city,item) DO UPDATE SET amount=excluded.amount",city,item,amount);
    }
    public synchronized List<Building> buildings(UUID city) throws SQLException { return query("SELECT * FROM building WHERE city=? ORDER BY id",this::building,city); }
    private Building building(ResultSet r) throws SQLException { return new Building(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("city")),UUID.fromString(r.getString("nation")),r.getString("type"),new Site(UUID.fromString(r.getString("world")),r.getString("world_name"),r.getInt("x"),r.getInt("y"),r.getInt("z")),r.getInt("level")); }
    public synchronized void addBuilding(Building b,String terrain,Definitions definitions) throws SQLException {
        tx(s->{
            if(buildings(b.cityId()).size()>=definitions.maxBuildingsPerCity()) throw new IllegalArgumentException("城市设施已达上限");
            if(buildings(b.cityId()).stream().anyMatch(x->x.site().regionKey().equals(b.site().regionKey()) && x.type().equals(b.type()))) throw new IllegalArgumentException("每区块同类设施限一座");
            Definitions.Terrain t=Objects.requireNonNull(definitions.terrains().get(terrain),"Unknown terrain");
            execute("INSERT OR IGNORE INTO region(id,terrain) VALUES(?,?)",b.site().regionKey(),terrain);
            // Only initialize reserves when region is first seen; unregistering/re-registering never refills it.
            for(var e:t.reserves().entrySet()) execute("INSERT OR IGNORE INTO reserve(region,resource,amount) VALUES(?,?,?)",b.site().regionKey(),e.getKey(),e.getValue());
            execute("INSERT INTO building(id,city,nation,type,world,world_name,x,y,z,level) VALUES(?,?,?,?,?,?,?,?,?,?)",b.id(),b.cityId(),b.nationId(),b.type(),b.site().worldId(),b.site().worldName(),b.site().x(),b.site().y(),b.site().z(),b.level());
            audit("system","REGISTER "+b.id()+" "+b.cityId()+" "+b.type());return null;
        });
    }
    public synchronized void removeBuilding(UUID city,UUID building) throws SQLException { execute("DELETE FROM building WHERE city=? AND id=?",city,building); }
    public synchronized Region region(String id) throws SQLException {
        List<String> type=query("SELECT terrain FROM region WHERE id=?",r->r.getString(1),id); if(type.isEmpty()) throw new SQLException("Unknown region");
        Map<String,Long> reserves=new HashMap<>();query("SELECT resource,amount FROM reserve WHERE region=?",r->{reserves.put(r.getString(1),r.getLong(2));return 0;},id);
        return new Region(id,type.getFirst(),reserves);
    }
    public synchronized void reserve(String region,String resource,long amount) throws SQLException { if(amount<0) throw new SQLException("Negative reserve");execute("UPDATE reserve SET amount=? WHERE region=? AND resource=?",amount,region,resource); }
    public synchronized void regenerate(String region,long period,Definitions.Terrain terrain) throws SQLException {
        List<Long> previous=query("SELECT last_regen FROM region WHERE id=?",r->r.getLong(1),region);
        if(previous.isEmpty() || previous.getFirst()>=period)return;
        for(var e:terrain.regeneration().entrySet()) execute("UPDATE reserve SET amount=MIN(?,amount+?) WHERE region=? AND resource=?",terrain.reserves().get(e.getKey()),e.getValue(),region,e.getKey());
        execute("UPDATE region SET last_regen=? WHERE id=?",period,region);
    }
    public synchronized void civilization(UUID city,String civilization,long period) throws SQLException {
        tx(s->{ CityView c=view(city).orElseThrow();
            long previous=query("SELECT changed_period FROM nation_state WHERE id=?",r->r.getLong(1),c.nationId()).getFirst();
            if(period-previous<6)throw new IllegalArgumentException("文明转型冷却为六个生产周期");
            execute("UPDATE nation_state SET civilization=?,changed_period=? WHERE id=?",civilization,period,c.nationId());
            execute("UPDATE city_state SET civilization=? WHERE nation=?",civilization,c.nationId());return null; });
    }
    public synchronized void upgrade(UUID city,UUID id) throws SQLException {
        tx(s->{Building b=buildings(city).stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();
            if(b.level()>=3)throw new IllegalArgumentException("设施已达三级");Map<String,Long> stock=stock(city);
            long planks=16L*b.level(),iron=4L*b.level();if(stock.getOrDefault("minecraft:oak_planks",0L)<planks || stock.getOrDefault("minecraft:iron_ingot",0L)<iron)throw new IllegalArgumentException("升级需要橡木板 "+planks+"、铁锭 "+iron);
            stock(city,"minecraft:oak_planks",stock.get("minecraft:oak_planks")-planks);stock(city,"minecraft:iron_ingot",stock.get("minecraft:iron_ingot")-iron);
            execute("UPDATE building SET level=level+1 WHERE id=? AND city=?",id,city);return null;});
    }
    public synchronized void event(UUID city,EventState event) throws SQLException {
        if(event==null) execute("UPDATE city_state SET event_type=NULL,event_start=NULL,event_end=NULL,aftermath_end=NULL WHERE id=?",city);
        else execute("UPDATE city_state SET event_type=?,event_start=?,event_end=?,aftermath_end=? WHERE id=?",event.type(),event.startPeriod(),event.endPeriod(),event.aftermathEndPeriod(),city);
    }
    public synchronized boolean settled(UUID city,long period) throws SQLException { return !query("SELECT 1 FROM settlement WHERE city=? AND period=?",r->1,city,period).isEmpty(); }
    public synchronized void finish(UUID city,long period,String report) throws SQLException {
        execute("INSERT INTO settlement(city,period,report) VALUES(?,?,?)",city,period,report);
        execute("UPDATE city_state SET last_period=?,last_report=? WHERE id=?",period,report,city);
        execute("DELETE FROM settlement WHERE city=? AND period<?",city,period-1000);
    }
    public synchronized Transfer prepareTransfer(UUID player,UUID city,String direction,String item,int amount) throws SQLException {
        if(amount<1 || amount>2304 || !(direction.equals("DEPOSIT") || direction.equals("WITHDRAW"))) throw new IllegalArgumentException("Invalid transfer");
        return tx(s->{
            if(!query("SELECT id FROM transfer WHERE player=? AND state='PREPARED'",r->r.getString(1),player).isEmpty()) throw new IllegalArgumentException("存在未核对的物品操作，请联系管理员");
            long current=stock(city).getOrDefault(item,0L);
            if(direction.equals("WITHDRAW")) { if(current<amount) throw new IllegalArgumentException("库存不足"); stock(city,item,current-amount); }
            else if(current+amount>stockLimit) throw new IllegalArgumentException("仓库容量不足");
            Transfer t=new Transfer(UUID.randomUUID(),player,city,direction,item,amount,"PREPARED");
            execute("INSERT INTO transfer(id,player,city,direction,item,amount,state,created) VALUES(?,?,?,?,?,?,?,?)",t.id(),player,city,direction,item,amount,t.state(),System.currentTimeMillis());
            return t;
        });
    }
    public synchronized Optional<Transfer> transfer(UUID id) throws SQLException { return query("SELECT * FROM transfer WHERE id=?",r->new Transfer(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("player")),UUID.fromString(r.getString("city")),r.getString("direction"),r.getString("item"),r.getInt("amount"),r.getString("state")),id).stream().findFirst(); }
    public synchronized List<Transfer> pending() throws SQLException { return query("SELECT * FROM transfer WHERE state='PREPARED' ORDER BY created LIMIT 100",r->new Transfer(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("player")),UUID.fromString(r.getString("city")),r.getString("direction"),r.getString("item"),r.getInt("amount"),r.getString("state"))); }
    /** Call only AFTER deposit removal or BEFORE withdrawal delivery. Never automatically replay PREPARED. */
    public synchronized void completeTransfer(UUID id) throws SQLException {
        tx(s->{ Transfer t=transfer(id).orElseThrow(); if(!t.state().equals("PREPARED")) throw new IllegalStateException("Transfer already resolved");
            if(t.direction().equals("DEPOSIT")) stock(t.cityId(),t.item(),Math.addExact(stock(t.cityId()).getOrDefault(t.item(),0L),t.amount()));
            execute("UPDATE transfer SET state='COMPLETED',decision='runtime' WHERE id=? AND state='PREPARED'",id);return null; });
    }
    /** Explicit administrator decision: credit a deposit/restore a withdrawal, or acknowledge without credit. */
    public synchronized void resolveTransfer(UUID id,boolean credit,String actor) throws SQLException {
        tx(s->{ Transfer t=transfer(id).orElseThrow(); if(!t.state().equals("PREPARED")) throw new IllegalStateException("Transfer already resolved");
            if(credit) stock(t.cityId(),t.item(),Math.addExact(stock(t.cityId()).getOrDefault(t.item(),0L),t.amount()));
            execute("UPDATE transfer SET state='RESOLVED',decision=? WHERE id=?",actor+":"+credit,id);audit(actor,"RESOLVE "+id+" credit="+credit);return null; });
    }
    public synchronized void cancelUntouchedTransfer(UUID id) throws SQLException {
        tx(s->{Transfer t=transfer(id).orElseThrow();if(!t.state().equals("PREPARED"))throw new IllegalStateException("Transfer already resolved");
            if(t.direction().equals("WITHDRAW"))stock(t.cityId(),t.item(),Math.addExact(stock(t.cityId()).getOrDefault(t.item(),0L),t.amount()));
            execute("UPDATE transfer SET state='CANCELLED',decision='no inventory mutation' WHERE id=?",id);return null;});
    }
    public synchronized void audit(String actor,String action) throws SQLException { execute("INSERT INTO audit(at,actor,action) VALUES(?,?,?)",System.currentTimeMillis(),actor,action); }
    public synchronized int schemaVersion() throws SQLException { return query("PRAGMA user_version",r->r.getInt(1)).getFirst(); }
    @Override public synchronized void close() throws SQLException { connection.close(); }
}

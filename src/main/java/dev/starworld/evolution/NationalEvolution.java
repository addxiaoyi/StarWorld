package dev.starworld.evolution;

import dev.starworld.domain.Definitions;
import dev.starworld.persistence.WorldStore;
import java.io.File;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Nation-wide development is derived from committed extension cities; StarCore remains the owner of nations. */
public final class NationalEvolution {
    private static final Set<String> DIRECTIONS=Set.of("agriculture","industry","commerce","military","culture");
    private static final long MAX_AGGREGATE=1_000_000_000_000L;
    private final WorldStore store;
    private final Map<String,Specialty> specialties;
    private final int cooldown;

    public record NationDevelopment(UUID nation,String civilization,String specialty,long changedPeriod,
                                    String direction,Map<String,Long> tendencies,long knowledge,long culture) {
        public NationDevelopment {Objects.requireNonNull(nation);tendencies=Map.copyOf(tendencies);}
    }
    public record Specialty(String name,String direction,long minScore,long minKnowledge,Map<String,Double> multipliers) {
        public Specialty {
            if(name==null||name.isBlank()||!DIRECTIONS.contains(direction)||minScore<1||minScore>MAX_AGGREGATE||minKnowledge<0||minKnowledge>MAX_AGGREGATE)throw new IllegalArgumentException("Invalid national specialty");
            multipliers=Map.copyOf(multipliers);
            if(multipliers.isEmpty())throw new IllegalArgumentException("National specialty needs industry effects");
            multipliers.forEach((id,factor)->{if(id==null||id.isBlank()||!Double.isFinite(factor)||factor<.85||factor>1.15)throw new IllegalArgumentException("National multiplier must be within 0.85..1.15");});
        }
    }
    public NationalEvolution(WorldStore store,Definitions definitions,File file)throws Exception {
        this.store=Objects.requireNonNull(store);Objects.requireNonNull(definitions);
        YamlConfiguration y=new YamlConfiguration();y.load(file);
        if(y.getInt("expansion-version")!=1)throw new IllegalArgumentException("Unsupported expansion-version");
        cooldown=integer(y,"national-specialty-cooldown",6,1,10000);
        ConfigurationSection configured=y.getConfigurationSection("national-specialties");
        Map<String,Specialty> values=configured==null?defaults(definitions):read(configured,definitions);
        if(values.isEmpty()||values.size()>64)throw new IllegalArgumentException("Invalid national specialties count");
        specialties=Map.copyOf(values);
        store.tx(s->{
            s.execute("CREATE TABLE IF NOT EXISTS national_evolution_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
            var versions=s.query("SELECT version FROM national_evolution_schema WHERE id=1",r->r.getInt(1));
            if(!versions.isEmpty()&&versions.getFirst()!=1)throw new IllegalStateException("Unsupported national evolution schema; preserve database and upgrade plugin");
            s.execute("INSERT OR IGNORE INTO national_evolution_schema(id,version) VALUES(1,1)");
            s.execute("CREATE TABLE IF NOT EXISTS national_evolution(nation TEXT PRIMARY KEY REFERENCES nation_state(id),specialty TEXT NOT NULL DEFAULT '',changed_period INTEGER NOT NULL DEFAULT -1000000)");
            s.execute("CREATE INDEX IF NOT EXISTS national_city_ownership ON city_state(nation)");
            return null;
        });
    }
    public Map<String,Specialty> specialties(){return specialties;}
    public int cooldown(){return cooldown;}
    /** Current city ownership is supplied by WorldRuntime's StarCore snapshots before settlement. */
    public NationDevelopment view(UUID nation)throws SQLException {
        Objects.requireNonNull(nation);
        return store.tx(s->{
            var civilization=s.query("SELECT civilization FROM nation_state WHERE id=?",r->r.getString(1),nation);
            if(civilization.isEmpty())throw new IllegalArgumentException("国家尚无已登记的 StarWorld 城市");
            s.execute("INSERT OR IGNORE INTO national_evolution(nation) VALUES(?)",nation);
            var selected=s.query("SELECT specialty,changed_period FROM national_evolution WHERE nation=?",r->new Object[]{r.getString(1),r.getLong(2)},nation).getFirst();
            long[] scores=s.query("SELECT COALESCE(SUM(e.agriculture),0),COALESCE(SUM(e.industry),0),COALESCE(SUM(e.commerce),0),COALESCE(SUM(e.military),0),COALESCE(SUM(e.culture),0),COALESCE(SUM(e.knowledge),0) FROM city_state c JOIN city_authority a ON a.city=c.id AND a.active=1 JOIN evolution_city e ON e.city=c.id WHERE c.nation=?",r->new long[]{clamp(r.getLong(1)),clamp(r.getLong(2)),clamp(r.getLong(3)),clamp(r.getLong(4)),clamp(r.getLong(5)),clamp(r.getLong(6))},nation).getFirst();
            Map<String,Long> tendencies=Map.of("agriculture",scores[0],"industry",scores[1],"commerce",scores[2],"military",scores[3],"culture",scores[4]);
            String direction=tendencies.entrySet().stream().filter(e->e.getValue()>0).sorted(Comparator.<Map.Entry<String,Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).findFirst().orElse("balanced");
            return new NationDevelopment(nation,civilization.getFirst(),(String)selected[0],(long)selected[1],direction,tendencies,scores[5],scores[4]);
        });
    }
    /** Caller must already prove StarCore founder/admin authority on the Paper main thread. */
    public NationDevelopment choose(UUID nation,String id,long period,String actor)throws SQLException {
        if(period<0||actor==null||actor.isBlank())throw new IllegalArgumentException("Invalid national decision");
        Specialty choice=specialties.get(id);if(choice==null)throw new IllegalArgumentException("未知国家专长："+specialties.keySet());
        return store.tx(s->{
            NationDevelopment before=view(nation);
            if(id.equals(before.specialty()))return before;
            if(period<before.changedPeriod()||period-before.changedPeriod()<cooldown)throw new IllegalArgumentException("国家专长转型冷却 "+cooldown+" 周期");
            if(!eligible(before,choice))throw new IllegalArgumentException("国家专长需要 "+choice.direction()+" 倾向 "+choice.minScore()+"、知识 "+choice.minKnowledge());
            s.execute("UPDATE national_evolution SET specialty=?,changed_period=? WHERE nation=?",id,period,nation);
            s.audit(actor,"NATIONAL_SPECIALTY "+nation+" "+id+" score="+before.tendencies().get(choice.direction())+" knowledge="+before.knowledge());
            return view(nation);
        });
    }
    /** Specialties stop applying as soon as their real contributing cities/resources no longer meet requirements. */
    public Map<String,Double> multipliers(UUID nation)throws SQLException {
        NationDevelopment profile=view(nation);Specialty specialty=specialties.get(profile.specialty());
        return specialty!=null&&eligible(profile,specialty)?specialty.multipliers():Map.of();
    }
    private static boolean eligible(NationDevelopment nation,Specialty specialty){return nation.knowledge()>=specialty.minKnowledge()&&nation.tendencies().getOrDefault(specialty.direction(),0L)>=specialty.minScore();}
    private static long clamp(long value){return Math.min(MAX_AGGREGATE,Math.max(0,value));}
    private static Map<String,Specialty> read(ConfigurationSection configured,Definitions definitions){
        Map<String,Specialty> result=new LinkedHashMap<>();
        for(String id:configured.getKeys(false)){
            if(!id.matches("[a-z][a-z0-9_-]{0,47}"))throw new IllegalArgumentException("Invalid national specialty ID");
            ConfigurationSection s=configured.getConfigurationSection(id);if(s==null)throw new IllegalArgumentException("Invalid national specialty "+id);
            ConfigurationSection effects=s.getConfigurationSection("multipliers");if(effects==null)throw new IllegalArgumentException("Missing national multipliers");Map<String,Double> factors=new LinkedHashMap<>();
            for(String building:effects.getKeys(false)){if(!definitions.buildings().containsKey(building)||!(effects.get(building) instanceof Number n))throw new IllegalArgumentException("Unknown national industry multiplier "+building);factors.put(building,n.doubleValue());}
            result.put(id,new Specialty(s.getString("name",id),s.getString("direction",id),whole(s,"min-score",20,1,MAX_AGGREGATE),whole(s,"min-knowledge",5,0,MAX_AGGREGATE),factors));
        }return result;
    }
    private static int integer(ConfigurationSection y,String path,int fallback,int min,int max){return Math.toIntExact(whole(y,path,fallback,min,max));}
    private static long whole(ConfigurationSection y,String path,long fallback,long min,long max){Object raw=y.get(path,fallback);if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.longValue()!=n.doubleValue()||n.longValue()<min||n.longValue()>max)throw new IllegalArgumentException("Invalid national number "+path);return n.longValue();}
    private static Map<String,Specialty> defaults(Definitions d){
        Map<String,Specialty> result=new LinkedHashMap<>();
        put(result,d,"agriculture","农耕强国",20,5,Map.of("farm",1.15,"kitchen",1.1,"workshop",.9));
        put(result,d,"industry","工业强国",20,8,Map.of("workshop",1.15,"sawmill",1.1,"farm",.9));
        put(result,d,"commerce","贸易联邦",3,5,Map.of("kitchen",1.15,"sawmill",1.1,"workshop",.95));
        put(result,d,"military","军事强国",20,8,Map.of("workshop",1.15,"farm",.9));
        put(result,d,"culture","文化强国",20,10,Map.of("kitchen",1.15,"farm",1.1,"workshop",.9));
        return result;
    }
    private static void put(Map<String,Specialty> target,Definitions d,String id,String name,long score,long knowledge,Map<String,Double> values){Map<String,Double> effects=new LinkedHashMap<>();values.forEach((k,v)->{if(d.buildings().containsKey(k))effects.put(k,v);});if(!effects.isEmpty())target.put(id,new Specialty(name,id,score,knowledge,effects));}
}

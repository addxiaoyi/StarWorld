package dev.starworld.evolution;

import dev.starworld.persistence.WorldStore;
import java.io.File;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Persistent, bounded cultural aging; called only as part of a committed city settlement. */
public final class CultureDynamics {
    public record Settings(int decayInterval,int decayBasisPoints,Map<String,Double> policyInfluence) {
        public Settings {
            policyInfluence=Map.copyOf(policyInfluence);
            if(decayInterval<1||decayInterval>10000||decayBasisPoints<0||decayBasisPoints>1000||policyInfluence.size()>64)throw new IllegalArgumentException("Invalid culture aging settings");
            policyInfluence.forEach((id,factor)->{if(!id.matches("[a-z][a-z0-9_-]{0,47}")||!Double.isFinite(factor)||factor<0.5||factor>2)throw new IllegalArgumentException("Invalid culture policy influence");});
        }
        public static Settings defaults(){return new Settings(12,500,Map.of("open_diplomacy",1.25,"fortified_borders",0.75));}
        public static Settings load(File file)throws Exception {
            YamlConfiguration y=new YamlConfiguration();y.load(file);Settings defaults=defaults();
            ConfigurationSection culture=y.getConfigurationSection("culture");if(culture==null){if(y.contains("culture"))throw new IllegalArgumentException("culture must be a configuration section");return defaults;}
            ConfigurationSection policies=culture.getConfigurationSection("policy-influence");Map<String,Double> factors=new TreeMap<>();
            if(policies==null&&culture.contains("policy-influence"))throw new IllegalArgumentException("culture.policy-influence must be a configuration section");
            if(policies==null)factors.putAll(defaults.policyInfluence());
            else for(String key:policies.getKeys(false)){Object raw=policies.get(key);if(!(raw instanceof Number n))throw new IllegalArgumentException("Invalid culture policy factor");factors.put(key,n.doubleValue());}
            return new Settings(whole(culture,"decay-interval",defaults.decayInterval()),whole(culture,"decay-basis-points",defaults.decayBasisPoints()),factors);
        }
        private static int whole(ConfigurationSection section,String key,int fallback){Object raw=section.get(key,fallback);if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("Invalid culture setting "+key);return n.intValue();}
    }
    private final WorldStore store;
    private final Settings settings;
    public CultureDynamics(WorldStore store,Settings settings)throws SQLException {
        this.store=Objects.requireNonNull(store);this.settings=Objects.requireNonNull(settings);
        store.tx(s->{
            s.execute("CREATE TABLE IF NOT EXISTS culture_dynamics_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
            var schema=s.query("SELECT version FROM culture_dynamics_schema WHERE id=1",r->r.getInt(1));if(!schema.isEmpty()&&schema.getFirst()!=1)throw new IllegalStateException("Unsupported culture dynamics schema; preserve database");
            s.execute("INSERT OR IGNORE INTO culture_dynamics_schema(id,version) VALUES(1,1)");
            s.execute("CREATE TABLE IF NOT EXISTS culture_decay(region TEXT PRIMARY KEY REFERENCES region(id),last_period INTEGER NOT NULL CHECK(last_period>=0))");return null;
        });
    }
    public Settings settings(){return settings;}
    /** Never catches up more than one aging step, including after restart or a long inactive interval. */
    public void advance(Collection<String> regions,long period)throws SQLException {
        if(period<0||regions.size()>64)throw new IllegalArgumentException("Invalid culture aging batch");
        Set<String> unique=Set.copyOf(regions);
        store.tx(s->{for(String region:unique){
            var previous=store.query("SELECT last_period FROM culture_decay WHERE region=?",r->r.getLong(1),region);
            if(previous.isEmpty()){store.execute("INSERT INTO culture_decay(region,last_period) VALUES(?,?)",region,period);continue;}
            long last=previous.getFirst();if(period<last||period-last<settings.decayInterval())continue;
            store.execute("UPDATE region_culture SET influence=(influence*?)/10000 WHERE region=?",10000-settings.decayBasisPoints(),region);
            store.execute("DELETE FROM region_culture WHERE region=? AND influence=0",region);
            store.execute("UPDATE culture_decay SET last_period=? WHERE region=?",period,region);
        }return null;});
    }
    /** A known public StarCore policy changes the rate of new culture; unknown/missing policies are neutral. */
    public long contribution(long base,String policyId) {
        if(base<1||base>6)throw new IllegalArgumentException("Culture contribution must be 1–6");
        return Math.max(1,Math.min(12,Math.round(base*settings.policyInfluence().getOrDefault(policyId==null?"":policyId,1.0))));
    }
}

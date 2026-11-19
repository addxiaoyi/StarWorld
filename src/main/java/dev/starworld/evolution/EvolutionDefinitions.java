package dev.starworld.evolution;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import dev.starworld.domain.Definitions;

/** Separate versioned, additive expansion configuration; old city saves keep their IDs. */
public record EvolutionDefinitions(Map<String,Plan> plans, Map<String,Specialty> specialties,
                                   int cooldown, int historyLimit, long foodPerFacility,
                                   double maxBonus, Map<String,Rule> events) {
    public EvolutionDefinitions {
        plans=Map.copyOf(plans);specialties=Map.copyOf(specialties);events=Map.copyOf(events);
        if(!plans.containsKey("balanced")||cooldown<1||cooldown>10000||historyLimit<10||historyLimit>1000||foodPerFacility<1||foodPerFacility>1000||!Double.isFinite(maxBonus)||maxBonus<1||maxBonus>3)throw new IllegalArgumentException("Invalid evolution bounds");
    }
    public record Plan(String name,Map<String,Double> multipliers) {
        public Plan {multipliers=Map.copyOf(multipliers);multipliers.values().forEach(EvolutionDefinitions::factor);}
    }
    public record Specialty(String name,String building,long knowledge,double coreMultiplier,Map<String,Double> multipliers) {
        public Specialty {multipliers=Map.copyOf(multipliers);multipliers.values().forEach(EvolutionDefinitions::factor);if(knowledge<0||knowledge>1000000||!Double.isFinite(coreMultiplier)||coreMultiplier<0||coreMultiplier>10)throw new IllegalArgumentException("Invalid specialty");}
    }
    public record Rule(String condition,double weight,int cooldown) {
        public Rule {if(!Set.of("always","dry","snow","river","famine","crisis","trade","culture","border","decline","revival").contains(condition)||!Double.isFinite(weight)||weight<=0||weight>100||cooldown<1||cooldown>10000)throw new IllegalArgumentException("Invalid event rule");}
    }
    public static EvolutionDefinitions load(File file,Definitions definitions)throws Exception {
        YamlConfiguration y=new YamlConfiguration();y.load(file);
        if(y.getInt("expansion-version")!=1)throw new IllegalArgumentException("Unsupported expansion-version");
        Map<String,Plan> plans=new LinkedHashMap<>();ConfigurationSection p=required(y,"planning");
        for(String id:p.getKeys(false)){id(id);ConfigurationSection s=required(p,id);Map<String,Double> f=factors(s,"multipliers",definitions);plans.put(id,new Plan(s.getString("name",id),f));}
        Map<String,Specialty> specialties=new LinkedHashMap<>();ConfigurationSection sp=required(y,"specialties");
        for(String id:sp.getKeys(false)){id(id);ConfigurationSection s=required(sp,id);String b=s.getString("building");if(!definitions.buildings().containsKey(b))throw new IllegalArgumentException("Unknown specialty building "+b);specialties.put(id,new Specialty(s.getString("name",id),b,s.getLong("knowledge"),s.getDouble("core-multiplier",0),factors(s,"multipliers",definitions)));}
        Map<String,Rule> rules=new LinkedHashMap<>();ConfigurationSection rs=required(y,"event-rules");
        for(String id:rs.getKeys(false)){if(!definitions.events().containsKey(id))throw new IllegalArgumentException("Unknown event rule "+id);ConfigurationSection s=required(rs,id);rules.put(id,new Rule(s.getString("condition","always"),s.getDouble("weight",1),s.getInt("cooldown",6)));}
        return new EvolutionDefinitions(plans,specialties,y.getInt("evolution.cooldown",6),y.getInt("evolution.history-limit",100),y.getLong("evolution.food-per-facility",1),y.getDouble("evolution.max-bonus",2),rules);
    }
    private static ConfigurationSection required(ConfigurationSection y,String key){ConfigurationSection s=y.getConfigurationSection(key);if(s==null)throw new IllegalArgumentException("Missing "+key);return s;}
    private static Map<String,Double> factors(ConfigurationSection y,String key,Definitions d){Map<String,Double> r=new LinkedHashMap<>();ConfigurationSection s=required(y,key);for(String id:s.getKeys(false)){if(!d.buildings().containsKey(id))throw new IllegalArgumentException("Unknown building "+id);if(!(s.get(id) instanceof Number n))throw new IllegalArgumentException("Invalid multiplier");r.put(id,n.doubleValue());}return r;}
    private static void factor(double n){if(!Double.isFinite(n)||n<0.1||n>2)throw new IllegalArgumentException("Invalid evolution multiplier");}
    private static void id(String id){if(!id.matches("[a-z][a-z0-9_-]{0,47}"))throw new IllegalArgumentException("Invalid ID "+id);}
}

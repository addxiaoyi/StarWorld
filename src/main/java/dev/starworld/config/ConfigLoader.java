package dev.starworld.config;

import dev.starworld.domain.Definitions;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class ConfigLoader {
    private ConfigLoader() {}
    public static Definitions load(File configFile,File definitionsFile) throws Exception {
        return load(configFile,definitionsFile,null);
    }
    /** Additive expansion definitions preserve every existing administrator override. */
    public static Definitions load(File configFile,File definitionsFile,File expansionFile) throws Exception {
        YamlConfiguration config=read(configFile),data=read(definitionsFile);
        if(expansionFile!=null) {
            YamlConfiguration expansion=read(expansionFile);
            if(expansion.getInt("expansion-version")!=1)throw new IllegalArgumentException("Unsupported expansion-version");
            for(String category:List.of("civilizations","buildings","recipes","events")) {
                ConfigurationSection additions=expansion.getConfigurationSection(category);
                if(additions!=null)for(String id:additions.getKeys(false))
                    if(!data.contains(category+"."+id))data.set(category+"."+id,additions.get(id));
            }
        }
        int version=config.getInt("config-version",1);
        if(version>2 || version<1)throw new IllegalArgumentException("Unsupported config-version: "+version);
        if(version==1) {
            Files.copy(configFile.toPath(),Path.of(configFile+".v1.bak"),StandardCopyOption.REPLACE_EXISTING);
            if(!config.contains("simulation.max-cities-per-pass"))config.set("simulation.max-cities-per-pass",20);
            config.set("config-version",2);config.save(configFile);
        }
        if(data.getInt("definition-version")!=1)throw new IllegalArgumentException("Unsupported definition-version");
        Map<String,Definitions.Terrain> terrains=new LinkedHashMap<>();
        ConfigurationSection ts=section(data,"terrains");
        for(String id:ts.getKeys(false)) {validateId(id);ConfigurationSection s=section(ts,id);terrains.put(id,new Definitions.Terrain(id,required(s,"name"),s.getDouble("agriculture"),s.getDouble("transport"),s.getDouble("military"),amounts(s,"reserves"),amounts(s,"regeneration")));}
        Map<String,Definitions.Civilization> civs=new LinkedHashMap<>();ConfigurationSection cs=section(data,"civilizations");
        for(String id:cs.getKeys(false)) {validateId(id);ConfigurationSection s=section(cs,id);civs.put(id,new Definitions.Civilization(id,required(s,"name"),factors(s,"multipliers")));}
        Map<String,Definitions.BuildingType> buildings=new LinkedHashMap<>();ConfigurationSection bs=section(data,"buildings");
        for(String id:bs.getKeys(false)) {validateId(id);ConfigurationSection s=section(bs,id);String anchor=required(s,"anchor");Material m=Material.matchMaterial(anchor);
            if(m==null || !m.isBlock() || m.isAir())throw new IllegalArgumentException("Invalid anchor "+anchor);
            buildings.put(id,new Definitions.BuildingType(id,required(s,"name"),anchor,amounts(s,"upkeep")));}
        List<Definitions.Recipe> recipes=new ArrayList<>();ConfigurationSection rs=section(data,"recipes");
        for(String id:rs.getKeys(false)) {validateId(id);ConfigurationSection s=section(rs,id);recipes.add(new Definitions.Recipe(id,required(s,"building"),amounts(s,"inputs"),amounts(s,"outputs"),s.getString("reserve"),s.getLong("reserve-cost",0)));}
        Map<String,Definitions.WorldEvent> events=new LinkedHashMap<>();ConfigurationSection es=section(data,"events");
        for(String id:es.getKeys(false)) {validateId(id);ConfigurationSection s=section(es,id);events.put(id,new Definitions.WorldEvent(id,required(s,"name"),s.getInt("duration"),s.getInt("aftermath"),factors(s,"multipliers"),s.getDouble("aftermath-multiplier",1)));}
        for(var c:civs.values())validateTypes(c.multipliers(),buildings);
        for(var e:events.values())validateTypes(e.multipliers(),buildings);
        return new Definitions(config.getLong("simulation.period-seconds",300),config.getInt("simulation.max-buildings-per-city",12),config.getInt("simulation.max-cities-per-pass",20),config.getLong("simulation.stock-limit",1000000),config.getDouble("simulation.event-chance",0.08),terrains,civs,buildings,recipes,events);
    }
    public static Set<String> items(Definitions d) {
        Set<String> ids=new HashSet<>();d.buildings().values().forEach(b->ids.addAll(b.upkeep().keySet()));d.recipes().forEach(r->{ids.addAll(r.inputs().keySet());ids.addAll(r.outputs().keySet());});return Set.copyOf(ids);
    }
    private static void validateTypes(Map<String,Double> factors,Map<String,Definitions.BuildingType> buildings) {for(String key:factors.keySet())if(!buildings.containsKey(key))throw new IllegalArgumentException("Unknown building multiplier: "+key);}
    private static void validateId(String id) {if(!id.matches("[a-z][a-z0-9_-]{0,47}"))throw new IllegalArgumentException("Invalid id "+id);}
    private static YamlConfiguration read(File file) throws Exception {YamlConfiguration y=new YamlConfiguration();y.load(file);return y;}
    private static ConfigurationSection section(ConfigurationSection s,String key) {ConfigurationSection r=s.getConfigurationSection(key);if(r==null)throw new IllegalArgumentException("Missing section: "+s.getCurrentPath()+"."+key);return r;}
    private static String required(ConfigurationSection s,String key) {String r=s.getString(key);if(r==null || r.isBlank())throw new IllegalArgumentException("Missing "+s.getCurrentPath()+"."+key);return r;}
    private static Map<String,Long> amounts(ConfigurationSection s,String key) {
        ConfigurationSection a=section(s,key);Map<String,Long> result=new LinkedHashMap<>();
        for(String id:a.getKeys(false)) {if(!id.matches("[a-z0-9_./-]+(:[a-z0-9_./-]+)?"))throw new IllegalArgumentException("Invalid item/reserve "+id);Object raw=a.get(id);if(!(raw instanceof Number n)||n.doubleValue()!=n.longValue())throw new IllegalArgumentException("Non-integral amount: "+id);result.put(id,n.longValue());}return result;
    }
    private static Map<String,Double> factors(ConfigurationSection s,String key) {ConfigurationSection a=section(s,key);Map<String,Double> r=new HashMap<>();for(String id:a.getKeys(false)){if(!(a.get(id) instanceof Number n))throw new IllegalArgumentException("Invalid factor "+id);r.put(id,n.doubleValue());}return r;}
}

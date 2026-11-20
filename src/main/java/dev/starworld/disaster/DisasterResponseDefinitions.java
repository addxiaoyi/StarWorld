package dev.starworld.disaster;

import dev.starworld.domain.Definitions;
import dev.starworld.disaster.DisasterState.*;
import java.io.File;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Versioned, bounded player response definitions; never edits the shared event catalog. */
public record DisasterResponseDefinitions(Definitions definitions,DisasterState.Definitions data){
    public DisasterResponseDefinitions{Objects.requireNonNull(definitions);Objects.requireNonNull(data);}
    public Map<String,Strategy> strategies(){return data.strategies();}
    public Set<String> itemIds(){Set<String> ids=new HashSet<>();strategies().values().forEach(s->ids.addAll(s.costs().keySet()));return Set.copyOf(ids);}
    public static void writeDefaults(File file)throws Exception{
        if(file.exists())return;File parent=file.getParentFile();if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("Cannot create disaster response config directory");
        YamlConfiguration y=new YamlConfiguration();y.options().setHeader(List.of("Budget covers stability, then prosperity, then regional reserve recovery.","Each point costs one unit; fractional profile gains round up."));y.set("schema-version",1);y.set("limits.max-active-per-city",4);y.set("limits.max-page",64);y.set("limits.max-evidence-facilities",64);y.set("allow-aftermath",true);
        add(y,"drought_irrigation","drought",List.of("farm","well"),Map.of("minecraft:bread",12L,"minecraft:oak_planks",8L),Map.of("fertility",5L),500,8,160,12,1,0,null,0,0);
        add(y,"drought_rationing","drought",List.of("warehouse","kitchen"),Map.of("minecraft:wheat",16L,"minecraft:bread",8L),Map.of("fertility",3L),300,6,96,10,2,0,null,0,0);
        add(y,"flood_drainage","flood",List.of("well","warehouse"),Map.of("minecraft:oak_planks",16L,"minecraft:bread",8L),Map.of("timber",5L,"fertility",2L),450,8,180,12,1,0,null,0,0);
        add(y,"flood_relocation","flood",List.of("market","warehouse"),Map.of("minecraft:oak_planks",24L,"minecraft:bread",8L),Map.of("timber",3L),250,6,96,10,2,1,null,0,0);
        add(y,"famine_kitchen","famine",List.of("kitchen","warehouse"),Map.of("minecraft:wheat",20L,"minecraft:oak_planks",4L),Map.of("fertility",2L),550,8,128,12,2,0,null,0,0);
        add(y,"famine_import","famine",List.of("market","warehouse"),Map.of("minecraft:oak_planks",4L),Map.of("fertility",3L,"timber",2L),700,10,180,14,2,1,"minecraft:bread",8,24);
        y.save(file);
    }
    private static void add(YamlConfiguration y,String id,String event,List<String> facilities,Map<String,Long> costs,Map<String,Long> recovery,int relief,int steps,long budget,int duration,int stability,int prosperity,String importItem,long importUnits,int receiptAge){
        String base="strategies."+id;y.set(base+".event",event);y.set(base+".facilities",facilities);y.set(base+".costs",costs);y.set(base+".recovery-per-step",recovery);y.set(base+".relief-permille",relief);y.set(base+".max-steps",steps);y.set(base+".budget",budget);y.set(base+".duration-periods",duration);y.set(base+".stability-per-step",stability);y.set(base+".prosperity-per-step",prosperity);if(importItem!=null){y.set(base+".import-item",importItem);y.set(base+".import-units",importUnits);y.set(base+".receipt-age-periods",receiptAge);}
    }
    public static DisasterResponseDefinitions load(File file,Definitions existing)throws Exception{
        YamlConfiguration y=new YamlConfiguration();y.load(file);if(integral(y,"schema-version",1,1)!=1)throw new IllegalArgumentException("Unsupported disaster-response schema-version");
        ConfigurationSection limits=required(y,"limits");int maxActive=(int)integral(limits,"max-active-per-city",1,16),maxPage=(int)integral(limits,"max-page",1,128),maxEvidence=(int)integral(limits,"max-evidence-facilities",1,256);Object aftermathValue=y.get("allow-aftermath");if(!(aftermathValue instanceof Boolean))throw new IllegalArgumentException("allow-aftermath must be boolean");boolean aftermath=(boolean)aftermathValue;
        ConfigurationSection input=required(y,"strategies");Map<String,Strategy> strategies=new LinkedHashMap<>();if(input.getKeys(false).size()>32)throw new IllegalArgumentException("Too many disaster response strategies");
        for(String id:input.getKeys(false)){
            if(!id.matches("[a-z][a-z0-9_-]{0,47}"))throw new IllegalArgumentException("Invalid response strategy ID: "+id);
            ConfigurationSection s=required(input,id);String event=requiredText(s,"event");if(!existing.events().containsKey(event))throw new IllegalArgumentException("Unknown disaster event: "+event);
            Set<String> facilities=new LinkedHashSet<>(s.getStringList("facilities"));if(facilities.isEmpty()||facilities.size()!=s.getStringList("facilities").size())throw new IllegalArgumentException("Invalid/duplicate response facilities: "+id);
            for(String type:facilities)if(!existing.buildings().containsKey(type))throw new IllegalArgumentException("Unknown response facility: "+type);
            Map<String,Long> costs=amounts(required(s,"costs"),false),recovery=amounts(required(s,"recovery-per-step"),true);
            String importItem=s.getString("import-item");long importUnits=s.contains("import-units")?integral(s,"import-units",1,1_000_000):0;int receiptAge=s.contains("receipt-age-periods")?(int)integral(s,"receipt-age-periods",1,10000):0;
            if(importItem!=null){if(!importItem.matches("[a-z0-9._-]+:[a-z0-9/._-]+")||importUnits<1||importUnits>1_000_000||receiptAge<1||receiptAge>10000)throw new IllegalArgumentException("Invalid imported response requirement: "+id);Map<String,Long> merged=new HashMap<>(costs);merged.merge(importItem,importUnits,Math::addExact);costs=Map.copyOf(merged);}
            else if(importUnits!=0||receiptAge!=0)throw new IllegalArgumentException("Import units/age require import-item: "+id);
            strategies.put(id,new Strategy(id,event,facilities,costs,recovery,(int)integral(s,"relief-permille",0,1000),(int)integral(s,"max-steps",1,1000),integral(s,"budget",1,1_000_000_000),integral(s,"duration-periods",1,10000),(int)integral(s,"stability-per-step",0,3),(int)integral(s,"prosperity-per-step",0,2),importItem,importUnits,receiptAge));
        }
        return new DisasterResponseDefinitions(existing,new DisasterState.Definitions(1,maxActive,maxPage,maxEvidence,aftermath,strategies));
    }
    private static ConfigurationSection required(ConfigurationSection section,String key){ConfigurationSection value=section.getConfigurationSection(key);if(value==null)throw new IllegalArgumentException("Missing disaster response section: "+key);return value;}
    private static String requiredText(ConfigurationSection section,String key){String value=section.getString(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing disaster response value: "+section.getCurrentPath()+"."+key);return value;}
    private static long integral(ConfigurationSection section,String key,long min,long max){Object raw=section.get(key);if(!(raw instanceof Number value)||!Double.isFinite(value.doubleValue())||value.doubleValue()!=value.longValue()||value.longValue()<min||value.longValue()>max)throw new IllegalArgumentException("Invalid integral disaster response value: "+section.getCurrentPath()+"."+key);return value.longValue();}
    private static Map<String,Long> amounts(ConfigurationSection section,boolean positive){if(section.getKeys(true).size()>256)throw new IllegalArgumentException("Too many disaster response amount nodes");Map<String,Long> values=new TreeMap<>();for(String key:section.getKeys(true)){if(section.isConfigurationSection(key))continue;if(key.length()>160||!key.matches("[a-z0-9_./-]+(:[a-z0-9_./-]+)?"))throw new IllegalArgumentException("Invalid response item/resource: "+key);Object raw=section.get(key);if(!(raw instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()!=number.longValue())throw new IllegalArgumentException("Nonintegral disaster response amount: "+key);long amount=number.longValue();if(amount<(positive?1:0)||amount>1_000_000_000L)throw new IllegalArgumentException("Out of range disaster response amount: "+key);values.put(key,amount);}if(values.isEmpty()||values.size()>32)throw new IllegalArgumentException("Empty or excessive disaster response amounts: "+section.getCurrentPath());return Map.copyOf(values);}
}

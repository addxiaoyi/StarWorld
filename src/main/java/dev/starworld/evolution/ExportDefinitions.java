package dev.starworld.evolution;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import dev.starworld.integration.CoreExtensionPort;

/** Explicit conversion contracts: physical items and national abstract resources remain distinct. */
public record ExportDefinitions(Map<String,Contract> contracts) {
    public ExportDefinitions {contracts=Map.copyOf(contracts);if(contracts.isEmpty()||contracts.size()>100)throw new IllegalArgumentException("Invalid export contracts");}
    public record Contract(String name,String facility,Map<String,Long> costs,Map<String,Long> resources,int maxBatches){
        public Contract {Objects.requireNonNull(name);Objects.requireNonNull(facility);costs=Map.copyOf(costs);resources=CoreExtensionPort.resourceAmounts(resources);if(costs.isEmpty()||costs.size()>64||maxBatches<1||maxBatches>1000)throw new IllegalArgumentException("Invalid contract");costs.forEach((id,n)->{if(!id.matches("[a-z0-9._-]+:[a-z0-9/._-]+")||n<1||n>1000000)throw new IllegalArgumentException("Invalid export cost");});}
        public Map<String,Long> cost(int batches){return scaled(costs,batches);}
        public Map<String,Long> output(int batches){return scaled(resources,batches);}
        private Map<String,Long> scaled(Map<String,Long> source,int batches){if(batches<1||batches>maxBatches)throw new IllegalArgumentException("Invalid export batch count");Map<String,Long> result=new TreeMap<>();source.forEach((k,v)->result.put(k,Math.multiplyExact(v,batches)));return Map.copyOf(result);}
    }
    public static ExportDefinitions load(File file)throws Exception {
        YamlConfiguration y=new YamlConfiguration();y.load(file);if(y.getInt("export-version")!=1)throw new IllegalArgumentException("Unsupported export-version");
        ConfigurationSection s=y.getConfigurationSection("contracts");if(s==null)throw new IllegalArgumentException("Missing export contracts");Map<String,Contract> result=new LinkedHashMap<>();
        for(String id:s.getKeys(false)){if(!id.matches("[a-z][a-z0-9_-]{0,47}"))throw new IllegalArgumentException("Invalid contract id");ConfigurationSection c=s.getConfigurationSection(id);if(c==null)throw new IllegalArgumentException("Invalid contract");result.put(id,new Contract(c.getString("name",id),c.getString("facility","warehouse"),amounts(c,"costs"),amounts(c,"resources"),c.getInt("max-batches",10)));}return new ExportDefinitions(result);
    }
    private static Map<String,Long> amounts(ConfigurationSection s,String path){ConfigurationSection a=s.getConfigurationSection(path);if(a==null)throw new IllegalArgumentException("Missing export "+path);Map<String,Long> result=new TreeMap<>();for(String key:a.getKeys(true)){if(a.isConfigurationSection(key)){if(a.getConfigurationSection(key).getKeys(false).isEmpty())throw new IllegalArgumentException("Empty export amount mapping "+key);continue;}Object raw=a.get(key);if(!(raw instanceof Number n)||n.longValue()!=n.doubleValue())throw new IllegalArgumentException("Invalid export amount");result.put(key,n.longValue());}return result;}
}

package dev.starworld.works;

import dev.starworld.domain.Definitions;
import dev.starworld.evolution.EvolutionDefinitions;
import dev.starworld.works.PublicWorksState.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Independent versioned definitions; an allocated project's complete terms are frozen in its receipt. */
public record PublicWorksDefinitions(Definitions definitions,EvolutionDefinitions evolution,Settings data){
    public PublicWorksDefinitions{
        Objects.requireNonNull(definitions);Objects.requireNonNull(evolution);Objects.requireNonNull(data);
        for(ProjectDefinition project:data.projects().values()){
            known(definitions.buildings().keySet(),project.facilities(),"facility");known(definitions.buildings().keySet(),project.multipliers().keySet(),"multiplier facility");
            known(definitions.terrains().keySet(),project.terrains(),"terrain");known(evolution.plans().keySet(),project.plans(),"city plan");known(definitions.civilizations().keySet(),project.civilizations(),"national civilization");
        }
    }
    public Map<String,ProjectDefinition> projects(){return data.projects();}
    public Set<String> itemIds(){Set<String> result=new HashSet<>();projects().values().forEach(p->{result.addAll(p.upfrontCosts().keySet());result.addAll(p.constructionCosts().keySet());result.addAll(p.upkeepCosts().keySet());});return Set.copyOf(result);}
    public static void writeDefaults(File file)throws IOException{
        if(file.exists())return;Path path=file.toPath();Path parent=path.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);
        try(InputStream source=PublicWorksDefinitions.class.getResourceAsStream("/public-works.yml")){
            if(source==null)throw new IOException("Missing packaged public-works.yml");Files.copy(source,path);
        }
    }
    public static PublicWorksDefinitions load(File file,Definitions definitions,EvolutionDefinitions evolution)throws Exception{
        // Item namespaces and resource paths may legally contain dots; they are literal YAML keys.
        YamlConfiguration yaml=new YamlConfiguration();yaml.options().pathSeparator('\u001f');yaml.load(file);
        int schema=(int)integer(yaml,"schema-version",1,1);ConfigurationSection limits=section(yaml,"limits");
        int page=(int)integer(limits,"max-page",1,128),evidence=(int)integer(limits,"max-evidence-facilities",1,256),history=(int)integer(limits,"history-limit",10,2000);
        ConfigurationSection projects=section(yaml,"projects");if(projects.getKeys(false).isEmpty()||projects.getKeys(false).size()>16)throw new IllegalArgumentException("Invalid public works definition count");
        Map<String,ProjectDefinition> result=new TreeMap<>();
        for(String id:projects.getKeys(false)){
            ConfigurationSection project=section(projects,id);
            ProjectDefinition definition=new ProjectDefinition(id,string(project,"name"),(int)integer(project,"build-periods",3,5),
                    costs(section(project,"upfront-costs")),costs(section(project,"construction-costs")),costs(section(project,"upkeep-costs")),
                    ids(project,"facilities",8),ids(project,"terrains",16),ids(project,"plans",16),ids(project,"civilizations",16),
                    integer(project,"knowledge",0,1_000_000),integer(project,"culture",0,1_000_000),factors(section(project,"multipliers")));
            result.put(id,definition);
        }
        return new PublicWorksDefinitions(definitions,evolution,new Settings(schema,page,evidence,history,result));
    }
    private static void known(Set<String> known,Set<String> required,String kind){for(String id:required)if(!known.contains(id))throw new IllegalArgumentException("Unknown public works "+kind+": "+id);}
    private static ConfigurationSection section(ConfigurationSection parent,String key){ConfigurationSection result=parent.getConfigurationSection(key);if(result==null)throw new IllegalArgumentException("Missing public works section: "+key);return result;}
    private static String string(ConfigurationSection parent,String key){Object raw=parent.get(key);if(!(raw instanceof String value)||value.isBlank())throw new IllegalArgumentException("Invalid public works text: "+key);return value;}
    private static long integer(ConfigurationSection parent,String key,long min,long max){Object raw=parent.get(key);if(!(raw instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()!=number.longValue()||number.longValue()<min||number.longValue()>max)throw new IllegalArgumentException("Invalid integral public works field: "+parent.getCurrentPath()+"."+key);return number.longValue();}
    private static Set<String> ids(ConfigurationSection parent,String key,int max){Object raw=parent.get(key);if(!(raw instanceof List<?> list)||list.isEmpty()||list.size()>max)throw new IllegalArgumentException("Invalid public works list: "+key);Set<String> result=new LinkedHashSet<>();for(Object entry:list)if(!(entry instanceof String id)||!id.matches("[a-z][a-z0-9_-]{0,47}")||!result.add(id))throw new IllegalArgumentException("Invalid or duplicate public works list entry: "+key);return Set.copyOf(result);}
    private static Map<String,Long> costs(ConfigurationSection section){Map<String,Object> values=section.getValues(false);if(values.isEmpty()||values.size()>16)throw new IllegalArgumentException("Invalid public works cost count");Map<String,Long> result=new TreeMap<>();for(var entry:values.entrySet()){Object raw=entry.getValue();if(!(raw instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()!=number.longValue()||number.longValue()<1||number.longValue()>1_000_000)throw new IllegalArgumentException("Invalid integral public works cost: "+entry.getKey());result.put(entry.getKey(),number.longValue());}return Map.copyOf(result);}
    private static Map<String,Double> factors(ConfigurationSection section){if(section.getKeys(false).isEmpty()||section.getKeys(false).size()>16)throw new IllegalArgumentException("Invalid public works multiplier count");Map<String,Double> result=new TreeMap<>();for(String id:section.getKeys(false)){Object raw=section.get(id);if(!(raw instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()<0.5||number.doubleValue()>1.25)throw new IllegalArgumentException("Invalid public works multiplier: "+id);result.put(id,number.doubleValue());}return Map.copyOf(result);}
}

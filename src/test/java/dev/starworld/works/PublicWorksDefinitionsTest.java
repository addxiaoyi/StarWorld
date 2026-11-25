package dev.starworld.works;

import dev.starworld.domain.Definitions;
import dev.starworld.evolution.EvolutionDefinitions;
import dev.starworld.works.PublicWorksState.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PublicWorksDefinitionsTest {
    @TempDir Path temp;
    final Definitions definitions=domain();
    final EvolutionDefinitions evolution=options();
    static Definitions domain(){
        Map<String,Definitions.Terrain> terrains=new HashMap<>();for(String id:List.of("plains","river","mountains","forest","desert","snow"))terrains.put(id,new Definitions.Terrain(id,id,1,1,1,Map.of("fertility",100L),Map.of("fertility",1L)));
        Map<String,Definitions.Civilization> civilizations=new HashMap<>();for(String id:List.of("agrarian","industrial","mercantile","martial","scholarly"))civilizations.put(id,new Definitions.Civilization(id,id,Map.of()));
        Map<String,Definitions.BuildingType> buildings=new HashMap<>();for(String id:List.of("farm","well","warehouse","workshop","barracks","academy","kitchen","market"))buildings.put(id,new Definitions.BuildingType(id,id,"COMPOSTER",Map.of()));
        return new Definitions(10,64,20,10000,0,terrains,civilizations,buildings,List.of(),Map.of());
    }
    static EvolutionDefinitions options(){Map<String,EvolutionDefinitions.Plan> plans=new HashMap<>();for(String id:List.of("balanced","agriculture","industry","military","culture","commerce"))plans.put(id,new EvolutionDefinitions.Plan(id,Map.of()));return new EvolutionDefinitions(plans,Map.of(),6,100,1,2,Map.of());}
    Path defaults()throws Exception{Path file=temp.resolve(UUID.randomUUID()+".yml");PublicWorksDefinitions.writeDefaults(file.toFile());return file;}
    PublicWorksDefinitions load(Path file)throws Exception{return PublicWorksDefinitions.load(file.toFile(),definitions,evolution);}
    void invalid(String path,Object value)throws Exception{Path file=defaults();YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file.toFile());yaml.set(path,value);yaml.save(file.toFile());assertThrows(Exception.class,()->load(file),path+"="+value);}
    @Test void packagedDefaultsHaveThreeTradeoffsAndWriteOnceWithoutChangingAdministratorSettings()throws Exception{
        Path file=defaults();byte[] source;try(var in=getClass().getResourceAsStream("/public-works.yml")){source=Objects.requireNonNull(in).readAllBytes();}assertArrayEquals(source,Files.readAllBytes(file));
        PublicWorksDefinitions config=load(file);assertEquals(3,config.projects().size());assertEquals(200,config.data().historyLimit());assertEquals(3,config.projects().get("irrigation_network").buildPeriods());assertEquals(5,config.projects().get("arsenal_district").buildPeriods());assertEquals(4,config.projects().get("cultural_quarter").buildPeriods());
        assertEquals(Map.of("farm",1.15,"workshop",0.95),config.projects().get("irrigation_network").multipliers());assertEquals(Map.of("workshop",1.15,"farm",0.95),config.projects().get("arsenal_district").multipliers());assertEquals(Map.of("kitchen",1.15,"farm",1.05,"workshop",0.95),config.projects().get("cultural_quarter").multipliers());
        assertFalse(config.projects().get("arsenal_district").civilizations().contains("agrarian"));assertFalse(config.projects().get("irrigation_network").terrains().contains("desert"));assertTrue(config.itemIds().contains("minecraft:iron_ingot"));
        Files.writeString(file,"administrator content");PublicWorksDefinitions.writeDefaults(file.toFile());assertEquals("administrator content",Files.readString(file));
    }
    @Test void futureSchemaFractionalIntegersZeroCostsAndUnboundedFieldsAreRejected()throws Exception{
        invalid("schema-version",2);invalid("schema-version",1.5);invalid("limits.max-page",129);invalid("limits.max-evidence-facilities",0);invalid("limits.history-limit",2001);invalid("limits.history-limit",9);
        invalid("projects.irrigation_network.build-periods",2);invalid("projects.irrigation_network.build-periods",6);invalid("projects.irrigation_network.build-periods",3.5);invalid("projects.irrigation_network.knowledge",-1);invalid("projects.irrigation_network.culture",0.5);
        invalid("projects.irrigation_network.upfront-costs.minecraft:bread",0);invalid("projects.irrigation_network.construction-costs.minecraft:bread",-1);invalid("projects.irrigation_network.upkeep-costs.minecraft:bread",0.5);invalid("projects.irrigation_network.upkeep-costs.minecraft:bread",1_000_001);invalid("projects.irrigation_network.upkeep-costs",Map.of());
        invalid("projects.irrigation_network.upfront-costs",null);invalid("projects.irrigation_network.name",42);invalid("projects.irrigation_network.name","x".repeat(81));
    }
    @Test void unknownIdsDuplicateOrMixedListsAndNonfiniteOrOutOfRangeMultipliersFailClosed()throws Exception{
        invalid("projects.irrigation_network.facilities",List.of("farm","unknown"));invalid("projects.irrigation_network.terrains",List.of("ocean"));invalid("projects.irrigation_network.plans",List.of("unknown"));invalid("projects.irrigation_network.civilizations",List.of("unknown"));
        invalid("projects.irrigation_network.facilities",List.of("farm","farm"));invalid("projects.irrigation_network.terrains",List.of("plains",1));invalid("projects.irrigation_network.plans",List.of());
        invalid("projects.irrigation_network.multipliers.farm",0.49);invalid("projects.irrigation_network.multipliers.farm",1.26);invalid("projects.irrigation_network.multipliers.farm",Double.NaN);invalid("projects.irrigation_network.multipliers.farm",Double.POSITIVE_INFINITY);invalid("projects.irrigation_network.multipliers.unknown",1.1);invalid("projects.irrigation_network.upfront-costs.not_namespaced",2);
    }
    @Test void definitionsAndPlanningCollectionsAreImmutableAndProgrammaticDefinitionsKeepTheSameBounds()throws Exception{
        PublicWorksDefinitions config=load(defaults());ProjectDefinition project=config.projects().get("irrigation_network");assertThrows(UnsupportedOperationException.class,()->config.projects().clear());assertThrows(UnsupportedOperationException.class,()->config.itemIds().clear());assertThrows(UnsupportedOperationException.class,()->project.facilities().clear());assertThrows(UnsupportedOperationException.class,()->project.multipliers().clear());assertThrows(UnsupportedOperationException.class,()->project.upkeepCosts().clear());
        assertThrows(IllegalArgumentException.class,()->new Settings(1,64,64,0,config.projects()));
        assertThrows(IllegalArgumentException.class,()->new ProjectDefinition("invalid","Invalid",3,Map.of("minecraft:bread",1L),Map.of("minecraft:bread",1L),Map.of("minecraft:bread",1L),Set.of("farm"),Set.of("plains"),Set.of("balanced"),Set.of("agrarian"),0,0,Map.of("farm",1.3)));
        assertThrows(IllegalArgumentException.class,()->new PublicWorksDefinitions(definitions,evolution,new Settings(1,64,64,100,Map.of("unknown",new ProjectDefinition("unknown","Unknown",3,Map.of("minecraft:bread",1L),Map.of("minecraft:bread",1L),Map.of("minecraft:bread",1L),Set.of("unknown"),Set.of("plains"),Set.of("balanced"),Set.of("agrarian"),0,0,Map.of("farm",1.1))))));
    }
    @Test void literalCraftEngineNamespacesAndResourcePathsWithDotsAndSlashesRemainSingleItemKeys()throws Exception{
        Path file=defaults();YamlConfiguration yaml=new YamlConfiguration();yaml.options().pathSeparator('\u001f');yaml.load(file.toFile());
        var project=yaml.getConfigurationSection("projects").getConfigurationSection("irrigation_network");
        project.set("upfront-costs",Map.of("server.food:grains/wheat.flour",7L));project.set("construction-costs",Map.of("server.parts:timber/oak.beam",3L));project.set("upkeep-costs",Map.of("server.food:bread/roll",2L));yaml.save(file.toFile());
        PublicWorksDefinitions loaded=load(file);ProjectDefinition definition=loaded.projects().get("irrigation_network");assertEquals(Map.of("server.food:grains/wheat.flour",7L),definition.upfrontCosts());assertEquals(Map.of("server.parts:timber/oak.beam",3L),definition.constructionCosts());assertEquals(Map.of("server.food:bread/roll",2L),definition.upkeepCosts());assertTrue(loaded.itemIds().containsAll(Set.of("server.food:grains/wheat.flour","server.parts:timber/oak.beam","server.food:bread/roll")));
    }
}

package dev.starworld.disaster;

import dev.starworld.domain.Definitions;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DisasterResponseDefinitionsTest {
    @TempDir Path temp;
    Definitions base(){Map<String,Definitions.BuildingType> facilities=new HashMap<>();for(String id:List.of("farm","well","warehouse","kitchen","market"))facilities.put(id,new Definitions.BuildingType(id,id,"COMPOSTER",Map.of()));Map<String,Definitions.WorldEvent> events=new HashMap<>();for(String id:List.of("drought","flood","famine"))events.put(id,new Definitions.WorldEvent(id,id,5,5,Map.of(),1));return new Definitions(10,64,20,10000,0,Map.of("plains",new Definitions.Terrain("plains","plains",1,1,1,Map.of("fertility",100L),Map.of("fertility",1L))),Map.of("agrarian",new Definitions.Civilization("agrarian","agrarian",Map.of())),facilities,List.of(),events);}
    Path defaults()throws Exception{Path file=temp.resolve(UUID.randomUUID()+".yml");DisasterResponseDefinitions.writeDefaults(file.toFile());return file;}
    @Test void sixDefaultsAndPackagedAssetLoadEquallyWithoutOverwritingAdministratorChanges()throws Exception{
        Path generated=defaults(),asset=temp.resolve("packaged.yml");try(InputStream input=getClass().getResourceAsStream("/disaster-response.yml")){assertNotNull(input);Files.copy(input,asset);}
        var first=DisasterResponseDefinitions.load(generated.toFile(),base());var second=DisasterResponseDefinitions.load(asset.toFile(),base());assertEquals(first,second);assertEquals(6,first.strategies().size());assertEquals(2,first.strategies().values().stream().filter(s->s.eventType().equals("drought")).count());assertEquals(8,first.strategies().get("famine_import").costs().get("minecraft:bread"));assertEquals(1,first.strategies().get("flood_relocation").prosperityPerStep());assertTrue(first.itemIds().containsAll(Set.of("minecraft:bread","minecraft:wheat","minecraft:oak_planks")));Files.writeString(generated,Files.readString(generated)+"\n# administrator\n");DisasterResponseDefinitions.writeDefaults(generated.toFile());assertTrue(Files.readString(generated).endsWith("# administrator\n"));assertThrows(UnsupportedOperationException.class,()->first.strategies().clear());
    }
    @Test void futureVersionsUnknownFacilitiesBadRecoveryAndFractionalLimitsAreRejected()throws Exception{
        for(var change:Map.<String,Object>of("schema-version",99,"limits.max-active-per-city",17,"limits.max-page",1.5,"strategies.drought_irrigation.facilities",List.of("farm","missing"),"strategies.drought_irrigation.recovery-per-step.ore",1,"strategies.drought_irrigation.relief-permille",1001,"strategies.drought_irrigation.duration-periods",0,"strategies.drought_irrigation.stability-per-step",4,"strategies.drought_irrigation.prosperity-per-step",3,"allow-aftermath","maybe").entrySet()){
            Path file=defaults();YamlConfiguration y=new YamlConfiguration();y.load(file.toFile());y.set(change.getKey(),change.getValue());y.save(file.toFile());assertThrows(IllegalArgumentException.class,()->DisasterResponseDefinitions.load(file.toFile(),base()),change.getKey());
        }
    }
    @Test void negativeOrNonintegralCostsAndBrokenImportsCannotCreateFreePolicies()throws Exception{
        for(var change:Map.<String,Object>of("strategies.drought_irrigation.costs.minecraft:bread",-1,"strategies.drought_irrigation.costs.minecraft:oak_planks",1.5,"strategies.famine_import.import-units",0,"strategies.famine_import.receipt-age-periods",0,"strategies.drought_irrigation.import-units",2,"strategies.drought_irrigation.event","unknown").entrySet()){
            Path file=defaults();YamlConfiguration y=new YamlConfiguration();y.load(file.toFile());y.set(change.getKey(),change.getValue());y.save(file.toFile());assertThrows(IllegalArgumentException.class,()->DisasterResponseDefinitions.load(file.toFile(),base()),change.getKey());
        }
    }
    @Test void validCraftEngineItemIdsWithDotsAndSlashesRemainLiteralCostKeys()throws Exception{
        Path file=defaults();YamlConfiguration y=new YamlConfiguration();y.load(file.toFile());y.set("strategies.drought_irrigation.costs",Map.of("server.food:grains/wheat",3L));y.save(file.toFile());
        var loaded=DisasterResponseDefinitions.load(file.toFile(),base());assertEquals(Map.of("server.food:grains/wheat",3L),loaded.strategies().get("drought_irrigation").costs());assertTrue(loaded.itemIds().contains("server.food:grains/wheat"));
    }
}

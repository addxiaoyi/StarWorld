package dev.starworld.runtime;

import dev.starworld.civic.CivicNeedsDefinitions;
import dev.starworld.life.LifeDefinitions;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CivicCapabilityTest {
    private final CivicNeedsDefinitions civic=CivicNeedsDefinitions.defaults();
    private final LifeDefinitions life=LifeDefinitions.defaults();
    private Optional<String> unavailable(String type,Predicate<String> loadedItems,boolean furnitureApi){return ExpansionRuntime.civicUnavailable(civic,life,type,loadedItems,furnitureApi,id->true);}
    private LifeDefinitions withRules(LifeDefinitions.DecorationRules decoration,LifeDefinitions.ConcertRules concert){return new LifeDefinitions(life.professionCooldownPeriods(),life.playerCulturePerPeriod(),life.cityCulturePerPeriod(),life.cultureLimit(),life.maximumProductionBonus(),life.professions(),life.feasts(),decoration,concert);}

    @Test void missingNamespacedMaterialAndUnknownNeedFailWithSpecificReasons(){
        Optional<String> material=unavailable("community_meals",id->!id.equals("minecraft:wheat"),true);
        assertTrue(material.orElseThrow().contains("minecraft:wheat"));
        assertTrue(unavailable("unknown",id->true,true).orElseThrow().contains("未知"));
        assertTrue(unavailable("festival",id->false,true).orElseThrow().contains("未加载"));
    }
    @Test void itemCatalogFeastRecipesVanillaConcertAndFurnitureApiGateEachActionChain(){
        Set<String> meals=Set.of("minecraft:wheat","minecraft:bread");
        assertTrue(unavailable("community_meals",meals::contains,true).isEmpty());
        assertTrue(unavailable("community_meals",id->id.startsWith("kaleidoscope"),true).orElseThrow().contains("所需物品未加载"));
        assertTrue(unavailable("public_spaces",id->id.equals("minecraft:oak_planks"),false).orElseThrow().contains("CraftEngine"));
        assertTrue(unavailable("public_spaces",Set.of("minecraft:oak_planks","minecraft:bread")::contains,true).isEmpty());
        assertTrue(unavailable("festival",Set.of("minecraft:paper","minecraft:bread","minecraft:note_block")::contains,false).isEmpty());
        assertTrue(unavailable("festival",Set.of("minecraft:paper","minecraft:bread")::contains,false).orElseThrow().contains("音符盒"));
        LifeDefinitions onlyCustomFeasts=new LifeDefinitions(life.professionCooldownPeriods(),life.playerCulturePerPeriod(),life.cityCulturePerPeriod(),life.cultureLimit(),life.maximumProductionBonus(),life.professions(),Map.of("cookery",life.feasts().get("cookery")),life.decorations(),life.concerts());
        Set<String> communityCosts=civic.needs().get("community_meals").costs().keySet();
        assertTrue(ExpansionRuntime.civicUnavailable(civic,onlyCustomFeasts,"community_meals",communityCosts::contains,true,id->true).orElseThrow().contains("宴会"));
    }
    @Test void craftEngineApiAndAnItemAloneDoNotImplyAnyAllowedFurnitureDefinitionIsLoaded(){
        assertTrue(ExpansionRuntime.civicUnavailable(civic,life,"public_spaces",id->true,true,id->false).orElseThrow().contains("家具定义均未加载"));
        assertTrue(ExpansionRuntime.civicUnavailable(civic,life,"public_spaces",id->true,true,id->id.equals("other:chair")).orElseThrow().contains("家具定义均未加载"));
        assertTrue(ExpansionRuntime.civicUnavailable(civic,life,"public_spaces",id->true,true,id->id.equals("kaleidoscope_deco:chair/oak")).isEmpty());
    }
    @Test void availableFurnitureStillRequiresLoadedRegistrationAndMaintenanceInputs(){
        var d=life.decorations();
        LifeDefinitions custom=withRules(new LifeDefinitions.DecorationRules(d.profession(),d.furnitureIds(),Map.of("fixture:fastener",1L),Map.of("fixture:varnish",1L),d.maximumPerCity(),d.culturePerPeriod(),d.productionBonus()),life.concerts());
        for(String missing:List.of("fixture:fastener","fixture:varnish")){
            Optional<String> reason=ExpansionRuntime.civicUnavailable(civic,custom,"public_spaces",id->!id.equals(missing),true,id->true);
            assertTrue(reason.orElseThrow().contains(missing));assertTrue(reason.orElseThrow().contains("登记/维护物资未加载"));
        }
        assertTrue(ExpansionRuntime.civicUnavailable(civic,custom,"public_spaces",id->true,true,id->true).isEmpty());
    }
    @Test void aLoadedInstrumentCannotAllocateFestivalWithMissingCustomConcertSupplies(){
        var c=life.concerts();
        LifeDefinitions custom=withRules(life.decorations(),new LifeDefinitions.ConcertRules(c.profession(),c.instruments(),Map.of("fixture:music_sheet",2L),c.minimumDurationMillis(),c.maximumDurationMillis(),c.noteGapMillis(),c.maximumSilenceMillis(),c.minimumNotes(),c.maximumNotes(),c.distinctPitches(),c.audience(),c.culture(),c.productionBonus(),c.durationPeriods(),c.cooldownPeriods()));
        Optional<String> reason=ExpansionRuntime.civicUnavailable(civic,custom,"festival",id->!id.equals("fixture:music_sheet"),true,id->true);
        assertTrue(reason.orElseThrow().contains("演奏补给未加载"));assertTrue(reason.orElseThrow().contains("fixture:music_sheet"));
        assertTrue(ExpansionRuntime.civicUnavailable(civic,custom,"festival",id->true,true,id->true).isEmpty());
    }
    @Test void anUnselectableConfiguredProfessionCannotSupplyAnyNewNeed(){
        LifeDefinitions unavailable=mock(LifeDefinitions.class);when(unavailable.professions()).thenReturn(Map.of());when(unavailable.feasts()).thenReturn(life.feasts());when(unavailable.decorations()).thenReturn(life.decorations());when(unavailable.concerts()).thenReturn(life.concerts());
        for(String type:List.of("community_meals","public_spaces","festival"))assertTrue(ExpansionRuntime.civicUnavailable(civic,unavailable,type,id->true,true,id->true).orElseThrow().contains("职业"));
    }
    @Test void mealAndConcertCapabilityNeverProbeFurnitureDefinitions(){
        Predicate<String> unexpected=id->{throw new AssertionError("Unrelated furniture lookup: "+id);};
        assertTrue(ExpansionRuntime.civicUnavailable(civic,life,"community_meals",id->true,true,unexpected).isEmpty());
        assertTrue(ExpansionRuntime.civicUnavailable(civic,life,"festival",id->true,true,unexpected).isEmpty());
    }
}

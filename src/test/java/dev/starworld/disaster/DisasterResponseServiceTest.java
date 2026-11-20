package dev.starworld.disaster;

import dev.starworld.disaster.DisasterState.*;
import dev.starworld.domain.Definitions;
import dev.starworld.domain.WorldState.*;
import dev.starworld.evolution.*;
import dev.starworld.logistics.*;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DisasterResponseServiceTest {
    @TempDir Path temp;
    static final String BREAD="minecraft:bread",PLANKS="minecraft:oak_planks";
    final UUID world=UUID.randomUUID();
    final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"city","nation",1,"test");
    final Definitions definitions=new Definitions(10,64,20,10000,0,
            Map.of("plains",new Definitions.Terrain("plains","plains",1,1,1,Map.of("fertility",100L,"timber",100L,"ore",100L),Map.of("fertility",1L,"timber",2L)),
                   "dry",new Definitions.Terrain("dry","dry",1,1,1,Map.of("fertility",100L,"timber",100L,"ore",100L),Map.of())),
            Map.of("agrarian",new Definitions.Civilization("agrarian","agrarian",Map.of())),
            Map.of("farm",building("farm"),"well",building("well"),"warehouse",building("warehouse"),"kitchen",building("kitchen"),"market",building("market")),List.of(),
            Map.of("drought",new Definitions.WorldEvent("drought","drought",5,5,Map.of("farm",0.4),0.7),"famine",new Definitions.WorldEvent("famine","famine",5,5,Map.of("farm",0.4),0.7),"flood",new Definitions.WorldEvent("flood","flood",5,5,Map.of("farm",0.4),0.7)));
    static Definitions.BuildingType building(String id){return new Definitions.BuildingType(id,id,"COMPOSTER",Map.of());}
    Strategy strategy(String id,String event,Set<String> facilities,long budget,int steps,long duration){return new Strategy(id,event,facilities,Map.of(BREAD,10L),Map.of("fertility",5L,"timber",3L),500,steps,budget,duration,1,1,null,0,0);}
    DisasterResponseDefinitions config(Strategy... strategies){Map<String,Strategy> map=new HashMap<>();for(Strategy strategy:strategies)map.put(strategy.id(),strategy);return new DisasterResponseDefinitions(definitions,new DisasterState.Definitions(1,4,64,64,true,map));}
    final class Rig implements AutoCloseable{
        final Path db=temp.resolve(UUID.randomUUID()+".db");WorldStore store;DisasterResponseService service;EvolutionService evolution;DisasterResponseDefinitions config;final Set<UUID> valid=new HashSet<>();
        Rig(DisasterResponseDefinitions config)throws Exception{this.config=config;open();store.ensureCity(city);evolution.profile(city.cityId());store.stock(city.cityId(),BREAD,100);store.stock(city.cityId(),PLANKS,100);add("farm",0,"plains");add("well",1,"plains");store.event(city.cityId(),new EventState("drought",10,15,20));}
        void open()throws Exception{store=new WorldStore(db,10000);evolution=new EvolutionService(store,definitions,new EvolutionDefinitions(Map.of("balanced",new EvolutionDefinitions.Plan("balanced",Map.of())),Map.of(),6,100,1,2,Map.of()));service=new DisasterResponseService(store,config);}
        Building add(String type,int chunk,String terrain)throws Exception{Building b=new Building(UUID.randomUUID(),city.cityId(),city.nationId(),type,new Site(world,"world",chunk*16,64,0),1);store.addBuilding(b,terrain,definitions);valid.add(b.id());return b;}
        CityView view()throws Exception{return store.view(city.cityId()).orElseThrow();}
        Response request(String id)throws Exception{return service.request(UUID.randomUUID(),city,id,10,valid,"actor");}
        DisasterResponseService.Cycle cycle(long period)throws Exception{return service.cycle(city,view(),period,valid);}
        RecoveryResult complete(long period)throws Exception{return store.tx(s->{RecoveryResult result=cycle(period).completed();s.finish(city.cityId(),period,"actual test settlement");return result;});}
        void restart()throws Exception{store.close();open();}
        public void close()throws Exception{store.close();}
    }
    @Test void requestConsumesExactStockOnceAndOneResponsePerEventIsPersistent()throws Exception{
        Strategy first=strategy("irrigate","drought",Set.of("farm","well"),100,4,20),other=strategy("ration","drought",Set.of("farm","well"),100,4,20);
        try(Rig r=new Rig(config(first,other))){UUID request=UUID.randomUUID();Response response=r.service.request(request,city,"irrigate",10,r.valid,"actor");assertEquals(90,r.store.stock(city.cityId()).get(BREAD));assertEquals(11,response.effectivePeriod());assertEquals(31,response.expiresPeriod());assertEquals(0,response.steps());assertEquals(response,r.service.request(request,city,"irrigate",100,r.valid,"actor"));assertThrows(SQLException.class,()->r.service.request(request,city,"ration",10,r.valid,"actor"));assertThrows(SQLException.class,()->r.request("ration"));assertEquals(90,r.store.stock(city.cityId()).get(BREAD));r.restart();assertEquals(response,r.service.response(request).orElseThrow());assertEquals(1,r.service.history(city.cityId(),64,0).size());assertThrows(UnsupportedOperationException.class,()->response.costs().clear());assertThrows(UnsupportedOperationException.class,()->r.service.responses(city.cityId(),64,0).clear());}
    }
    @Test void historicalRetryRequiresCurrentAuthorityButNotTheOldEventOrSettlementPeriod()throws Exception{
        try(Rig r=new Rig(config(strategy("irrigate","drought",Set.of("farm","well"),100,4,20)))){
            EventState captured=r.view().event();Response response=r.request("irrigate");
            r.store.event(city.cityId(),new EventState("flood",40,45,50));r.store.finish(city.cityId(),50,"later settlement");
            assertEquals(response,r.service.request(response.requestId(),city,"irrigate",10,r.valid,"actor",captured));
            r.store.invalidateCity(city.cityId());
            assertThrows(SQLException.class,()->r.service.request(response.requestId(),city,"irrigate",10,r.valid,"actor",captured));
            CoreCity transferred=new CoreCity(city.cityId(),UUID.randomUUID(),"city","conqueror",1,"core");r.store.ensureCity(transferred);
            assertThrows(SQLException.class,()->r.service.request(response.requestId(),city,"irrigate",10,r.valid,"actor",captured));
            assertThrows(SQLException.class,()->r.service.request(response.requestId(),transferred,"irrigate",50,r.valid,"actor",captured));
            assertEquals(90,r.store.stock(city.cityId()).get(BREAD));assertEquals(1,r.service.history(city.cityId(),64,0).size());
        }
    }
    @Test void staleOrMissingEventFacilitiesStockAndCaptureAreRejectedWithoutPartialCost()throws Exception{
        try(Rig r=new Rig(config(strategy("irrigate","drought",Set.of("farm","well"),100,4,20)))){EventState expected=r.view().event();assertThrows(SQLException.class,()->r.service.request(UUID.randomUUID(),city,"irrigate",10,Set.of(),"actor"));r.store.stock(city.cityId(),BREAD,9);assertThrows(SQLException.class,()->r.request("irrigate"));assertEquals(9,r.store.stock(city.cityId()).get(BREAD));r.store.stock(city.cityId(),BREAD,100);assertThrows(SQLException.class,()->r.service.request(UUID.randomUUID(),city,"irrigate",20,r.valid,"actor"));assertThrows(SQLException.class,()->r.service.request(UUID.randomUUID(),city,"irrigate",10,r.valid,"actor",null));r.store.event(city.cityId(),new EventState("drought",11,16,21));assertThrows(SQLException.class,()->r.service.request(UUID.randomUUID(),city,"irrigate",11,r.valid,"actor",expected));assertEquals(100,r.store.stock(city.cityId()).get(BREAD));assertTrue(r.service.responses(city.cityId(),64,0).isEmpty());}
    }
    @Test void responseStartsNextUnsettledPeriodOnlyAndNeverAmplifiesPositiveEvents()throws Exception{
        try(Rig r=new Rig(config(strategy("irrigate","drought",Set.of("farm","well"),100,4,20)))){Response response=r.request("irrigate");EventState event=r.view().event();assertEquals(0.4,r.cycle(10).eventFactor(event,0.4));assertEquals(0.7,r.cycle(11).eventFactor(event,0.4),0.00001);assertEquals(1.4,r.cycle(11).eventFactor(event,1.4));assertEquals(0.4,r.cycle(11).eventFactor(new EventState("drought",11,16,21),0.4));assertEquals(0.4,r.cycle(11).eventFactor(new EventState("flood",10,15,20),0.4));assertEquals(0,r.service.response(response.requestId()).orElseThrow().steps());assertEquals(50,r.evolution.profile(city.cityId()).stability());}
    }
    @Test void aftermathPolicyIsExplicitAndRecoveryCannotExceedConfiguredMaximumSteps()throws Exception{
        Strategy strategy=strategy("irrigate","drought",Set.of("farm","well"),1000,1,20);DisasterResponseDefinitions allowed=config(strategy);
        try(Rig r=new Rig(new DisasterResponseDefinitions(definitions,new DisasterState.Definitions(1,4,64,64,false,allowed.strategies())))){assertThrows(SQLException.class,()->r.service.request(UUID.randomUUID(),city,"irrigate",16,r.valid,"actor"));assertEquals(100,r.store.stock(city.cityId()).get(BREAD));}
        try(Rig r=new Rig(allowed)){Response response=r.service.request(UUID.randomUUID(),city,"irrigate",16,r.valid,"actor");assertTrue(response.allowAftermath());assertEquals(0.85,r.cycle(17).eventFactor(r.view().event(),0.7),0.00001);assertEquals(1,r.complete(17).appliedSteps());assertEquals(0,r.complete(18).appliedSteps());assertEquals(1,r.service.response(response.requestId()).orElseThrow().steps());assertEquals(51,r.evolution.profile(city.cityId()).stability());}
    }
    @Test void recoveryIsBoundedRegenerativeOnlyAndCannotDuplicateAfterRestartOrPeriodReplay()throws Exception{
        try(Rig r=new Rig(config(strategy("irrigate","drought",Set.of("farm","well"),9,4,20)))){Response response=r.request("irrigate");for(Building b:r.view().buildings()){r.store.reserve(b.site().regionKey(),"fertility",95);r.store.reserve(b.site().regionKey(),"timber",0);r.store.reserve(b.site().regionKey(),"ore",0);}RecoveryResult result=r.complete(11);assertEquals(1,result.appliedSteps());long restored=result.restored().values().stream().flatMap(m->m.values().stream()).mapToLong(Long::longValue).sum();assertEquals(7,restored);assertEquals(0,r.service.response(response.requestId()).orElseThrow().remainingBudget());assertEquals(51,r.evolution.profile(city.cityId()).stability());assertEquals(51,r.evolution.profile(city.cityId()).prosperity());for(Building b:r.view().buildings()){assertEquals(0,r.store.region(b.site().regionKey()).reserves().get("ore"));assertTrue(r.store.region(b.site().regionKey()).reserves().get("fertility")<=100);}r.restart();assertEquals(0,r.cycle(11).completed().appliedSteps());assertEquals(51,r.evolution.profile(city.cityId()).stability());assertEquals(1,r.service.response(response.requestId()).orElseThrow().steps());}
    }
    @Test void sharedBudgetPrioritizesProfilesRoundsFractionalGainsUpAndDoesNotChargeCappedProfiles()throws Exception{
        try(Rig r=new Rig(config(strategy("irrigate","drought",Set.of("farm","well"),1,4,20)))){
            Response response=r.request("irrigate");for(Building b:r.view().buildings())r.store.reserve(b.site().regionKey(),"fertility",0);
            assertTrue(r.complete(11).restored().isEmpty());assertEquals(51,r.evolution.profile(city.cityId()).stability());assertEquals(50,r.evolution.profile(city.cityId()).prosperity());assertEquals(0,r.service.response(response.requestId()).orElseThrow().remainingBudget());
        }
        try(Rig r=new Rig(config(strategy("irrigate","drought",Set.of("farm","well"),2,4,20)))){
            Response response=r.request("irrigate");r.store.execute("UPDATE evolution_city SET stability=99.5,prosperity=99.5 WHERE city=?",city.cityId());for(Building b:r.view().buildings())r.store.reserve(b.site().regionKey(),"fertility",0);
            assertTrue(r.complete(11).restored().isEmpty());assertEquals(100,r.evolution.profile(city.cityId()).stability());assertEquals(100,r.evolution.profile(city.cityId()).prosperity());assertEquals(0,r.service.response(response.requestId()).orElseThrow().remainingBudget());assertTrue(r.service.history(city.cityId(),64,0).getFirst().detail().contains("profileBudget=2"));
        }
        try(Rig r=new Rig(config(strategy("irrigate","drought",Set.of("farm","well"),3,4,20)))){
            Response response=r.request("irrigate");r.store.execute("UPDATE evolution_city SET stability=100,prosperity=100 WHERE city=?",city.cityId());for(Building b:r.view().buildings())r.store.reserve(b.site().regionKey(),"fertility",0);
            long restored=r.complete(11).restored().values().stream().flatMap(m->m.values().stream()).mapToLong(Long::longValue).sum();assertEquals(3,restored);assertEquals(0,r.service.response(response.requestId()).orElseThrow().remainingBudget());
        }
    }
    @Test void lostAuthorityFacilitiesAndLargeOfflineGapsPauseWithoutCatchup()throws Exception{
        try(Rig r=new Rig(config(strategy("irrigate","drought",Set.of("farm","well"),1000,3,1000)))){Response response=r.request("irrigate");r.complete(11);Set<UUID> valid=Set.copyOf(r.valid);r.valid.clear();assertEquals(0,r.complete(100).appliedSteps());assertEquals(1,r.service.response(response.requestId()).orElseThrow().steps());r.valid.addAll(valid);assertEquals(1,r.complete(101).appliedSteps());assertEquals(2,r.service.response(response.requestId()).orElseThrow().steps());r.store.invalidateCity(city.cityId());assertEquals(0,r.cycle(102).completed().appliedSteps());CoreCity other=new CoreCity(city.cityId(),UUID.randomUUID(),"city","newnation",1,"core");r.store.ensureCity(other);assertTrue(r.service.cycle(other,r.view(),103,r.valid).responses().isEmpty());assertEquals(2,r.service.response(response.requestId()).orElseThrow().steps());}
    }
    @Test void responseHistoryFailureRollsBackCostAndRecoveryFailureRollsBackBudgetProfileAndReserve()throws Exception{
        try(Rig r=new Rig(config(strategy("irrigate","drought",Set.of("farm","well"),1000,3,20)))){r.store.execute("CREATE TRIGGER reject_response BEFORE INSERT ON disaster_response_history BEGIN SELECT RAISE(ABORT,'request crash'); END");assertThrows(SQLException.class,()->r.request("irrigate"));assertEquals(100,r.store.stock(city.cityId()).get(BREAD));assertTrue(r.service.responses(city.cityId(),64,0).isEmpty());r.store.execute("DROP TRIGGER reject_response");Response response=r.request("irrigate");Building first=r.view().buildings().getFirst();r.store.reserve(first.site().regionKey(),"fertility",0);r.store.execute("CREATE TRIGGER reject_recovery BEFORE INSERT ON disaster_response_history WHEN NEW.kind='recovery' BEGIN SELECT RAISE(ABORT,'recovery crash'); END");assertThrows(SQLException.class,()->r.complete(11));assertEquals(0,r.store.region(first.site().regionKey()).reserves().get("fertility"));assertEquals(50,r.evolution.profile(city.cityId()).stability());assertEquals(0,r.service.response(response.requestId()).orElseThrow().steps());assertEquals(1000,r.service.response(response.requestId()).orElseThrow().remainingBudget());assertEquals(-1,r.view().lastPeriod());r.store.execute("DROP TRIGGER reject_recovery");assertEquals(1,r.complete(11).appliedSteps());assertEquals(1,r.service.response(response.requestId()).orElseThrow().steps());}
    }
    @Test void profilesCapAtHundredAndFrozenResponseSurvivesConfigChanges()throws Exception{
        Strategy original=strategy("irrigate","drought",Set.of("farm","well"),1000,3,20);
        try(Rig r=new Rig(config(original))){Response response=r.request("irrigate");r.store.execute("UPDATE evolution_city SET stability=99.5,prosperity=99.5 WHERE city=?",city.cityId());r.config=config(new Strategy("irrigate","drought",Set.of("farm","well"),Map.of(BREAD,99L),Map.of("fertility",50L),1000,100,999,100,3,2,null,0,0));r.restart();r.complete(11);assertEquals(100,r.evolution.profile(city.cityId()).stability());assertEquals(100,r.evolution.profile(city.cityId()).prosperity());Response saved=r.service.response(response.requestId()).orElseThrow();assertEquals(500,saved.reliefPermille());assertEquals(3,saved.maxSteps());assertEquals(1,saved.stabilityPerStep());assertEquals(10,saved.costs().get(BREAD));assertEquals(90,r.store.stock(city.cityId()).get(BREAD));}
    }
    @Test void longTermRecoveryContinuesAfterEventEndsButStopsAtTermAndNonrenewingTerrainIsNotRefilled()throws Exception{
        try(Rig r=new Rig(config(strategy("irrigate","drought",Set.of("farm","well"),1000,100,20)))){Response response=r.request("irrigate");r.store.event(city.cityId(),null);for(Building b:r.view().buildings()){r.store.execute("UPDATE region SET terrain='dry' WHERE id=?",b.site().regionKey());r.store.reserve(b.site().regionKey(),"fertility",0);}RecoveryResult result=r.complete(25);assertEquals(1,result.appliedSteps());assertTrue(result.restored().isEmpty());assertEquals(0,r.complete(response.expiresPeriod()).appliedSteps());assertEquals(1,r.service.response(response.requestId()).orElseThrow().steps());}
    }
    @Test void excessiveEvidenceOnlyPausesResponseAndUnknownFutureSchemaIsRejected()throws Exception{
        DisasterResponseDefinitions normal=config(strategy("irrigate","drought",Set.of("farm","well"),1000,3,20));
        try(Rig r=new Rig(normal)){r.request("irrigate");Set<UUID> huge=new HashSet<>(r.valid);for(int i=0;i<70;i++)huge.add(UUID.randomUUID());assertTrue(r.service.cycle(city,r.view(),11,huge).responses().isEmpty());assertEquals(0,r.service.cycle(city,r.view(),11,huge).completed().appliedSteps());assertEquals(50,r.evolution.profile(city.cityId()).stability());r.store.execute("UPDATE disaster_response_schema SET version=99");assertThrows(SQLException.class,()->new DisasterResponseService(r.store,normal));}
    }
    @Test void importRationRequiresRealRecentCompletedInboundCargoAndConsumesImportedStock()throws Exception{
        Strategy imported=new Strategy("imported","famine",Set.of("market","warehouse"),Map.of(BREAD,8L,PLANKS,4L),Map.of("fertility",3L),700,4,100,20,2,1,BREAD,8,24);
        try(Rig r=new Rig(config(imported))){r.add("market",2,"plains");r.add("warehouse",3,"plains");r.store.event(city.cityId(),new EventState("famine",10,30,40));assertThrows(SQLException.class,()->r.service.request(UUID.randomUUID(),city,"imported",10,r.valid,"actor"));
            CoreCity source=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"source","foreign",1,"core");r.store.ensureCity(source);r.store.stock(source.cityId(),BREAD,100);r.store.stock(source.cityId(),PLANKS,100);
            LogisticsService logistics=new LogisticsService(r.store,LogisticsDefinitions.defaults());Site a=new Site(world,"world",320,64,0),b=new Site(world,"world",0,65,0);Set<String> anchors=Set.of(a.anchorKey(),b.anchorKey());Set<UUID> nodeIds=new HashSet<>();
            java.util.function.LongFunction<AuthoritySnapshot> authority=p->new AuthoritySnapshot(UUID.randomUUID(),p,Map.of(source.cityId(),source.nationId(),city.cityId(),city.nationId()),Set.of(source.cityId(),city.cityId()),anchors,nodeIds,Set.of(TradePair.of(source.cityId(),city.cityId())));
            UUID left=logistics.registerNode(UUID.randomUUID(),source.cityId(),NodeType.ROAD,a,"plains",authority.apply(9)).id();nodeIds.add(left);UUID right=logistics.registerNode(UUID.randomUUID(),city.cityId(),NodeType.ROAD,b,"plains",authority.apply(9)).id();nodeIds.add(right);UUID route=logistics.createRoute(UUID.randomUUID(),source.cityId(),city.cityId(),List.of(left,right),Mode.LAND,authority.apply(9)).id();UUID order=UUID.randomUUID();logistics.createOffer(order,route,Map.of(BREAD,16L),Map.of(PLANKS,1L),authority.apply(10));logistics.accept(order,authority.apply(10));assertThrows(SQLException.class,()->r.service.request(UUID.randomUUID(),city,"imported",10,r.valid,"actor"));for(int p=11;p<=15;p++)logistics.settle(authority.apply(p),10);assertEquals(OrderStatus.COMPLETED,logistics.order(order).orElseThrow().status());
            r.store.event(city.cityId(),new EventState("famine",37,45,50));assertThrows(SQLException.class,()->r.service.request(UUID.randomUUID(),city,"imported",39,r.valid,"actor"));r.store.event(city.cityId(),new EventState("famine",10,30,40));long bread=r.store.stock(city.cityId()).get(BREAD);Response response=r.service.request(UUID.randomUUID(),city,"imported",15,r.valid,"actor");assertEquals(order,response.importReceipt());assertEquals(bread-8,r.store.stock(city.cityId()).get(BREAD));assertEquals(2,response.stabilityPerStep());
        }
    }
    @Test void overlappingHistoricalResponsesReportTheSumOfActualRecoveryForEachRegionAndResource()throws Exception{
        try(Rig r=new Rig(config(strategy("irrigate","drought",Set.of("farm","well"),1000,4,20),strategy("drain","flood",Set.of("farm","well"),1000,4,20)))){r.request("irrigate");r.store.event(city.cityId(),new EventState("flood",11,16,21));r.service.request(UUID.randomUUID(),city,"drain",11,r.valid,"actor");for(Building b:r.view().buildings()){r.store.reserve(b.site().regionKey(),"fertility",0);r.store.reserve(b.site().regionKey(),"timber",0);}RecoveryResult result=r.complete(12);assertEquals(2,result.appliedSteps());for(Building b:r.view().buildings()){assertEquals(10,result.restored().get(b.site().regionKey()).get("fertility"));assertEquals(6,result.restored().get(b.site().regionKey()).get("timber"));assertEquals(10,r.store.region(b.site().regionKey()).reserves().get("fertility"));assertEquals(6,r.store.region(b.site().regionKey()).reserves().get("timber"));}}
    }
    @Test void importEvidenceRejectsOutgoingFoodAndFutureReceiptsButAcceptsInboundRequestedCargo()throws Exception{
        Strategy imported=new Strategy("imported","famine",Set.of("market","warehouse"),Map.of(BREAD,8L,PLANKS,4L),Map.of("fertility",3L),700,4,100,20,2,1,BREAD,8,24);
        for(boolean inbound:List.of(false,true))try(Rig r=new Rig(config(imported))){
            r.add("market",2,"plains");r.add("warehouse",3,"plains");r.store.event(city.cityId(),new EventState("famine",10,30,40));
            CoreCity other=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"other","foreign",1,"core");r.store.ensureCity(other);r.store.stock(other.cityId(),BREAD,100);r.store.stock(other.cityId(),PLANKS,100);
            LogisticsService logistics=new LogisticsService(r.store,LogisticsDefinitions.defaults());Site a=new Site(world,"world",0,65,0),b=new Site(world,"world",320,64,0);Set<String> anchors=Set.of(a.anchorKey(),b.anchorKey());Set<UUID> nodes=new HashSet<>();
            java.util.function.LongFunction<AuthoritySnapshot> authority=p->new AuthoritySnapshot(UUID.randomUUID(),p,Map.of(city.cityId(),city.nationId(),other.cityId(),other.nationId()),Set.of(city.cityId(),other.cityId()),anchors,nodes,Set.of(TradePair.of(city.cityId(),other.cityId())));
            UUID left=logistics.registerNode(UUID.randomUUID(),city.cityId(),NodeType.ROAD,a,"plains",authority.apply(9)).id();nodes.add(left);UUID right=logistics.registerNode(UUID.randomUUID(),other.cityId(),NodeType.ROAD,b,"plains",authority.apply(9)).id();nodes.add(right);
            UUID route=logistics.createRoute(UUID.randomUUID(),city.cityId(),other.cityId(),List.of(left,right),Mode.LAND,authority.apply(9)).id(),order=UUID.randomUUID();
            logistics.createOffer(order,route,inbound?Map.of(PLANKS,1L):Map.of(BREAD,16L),inbound?Map.of(BREAD,16L):Map.of(PLANKS,1L),authority.apply(10));logistics.accept(order,authority.apply(10));for(int p=11;p<=15;p++)logistics.settle(authority.apply(p),10);
            Order completed=logistics.order(order).orElseThrow();assertEquals(OrderStatus.COMPLETED,completed.status());long bread=r.store.stock(city.cityId()).get(BREAD);
            assertThrows(SQLException.class,()->r.service.request(UUID.randomUUID(),city,"imported",completed.completedPeriod()-1,r.valid,"actor"));
            if(inbound){Response response=r.service.request(UUID.randomUUID(),city,"imported",15,r.valid,"actor");assertEquals(order,response.importReceipt());assertEquals(bread-8,r.store.stock(city.cityId()).get(BREAD));}
            else{assertThrows(SQLException.class,()->r.service.request(UUID.randomUUID(),city,"imported",15,r.valid,"actor"));assertEquals(bread,r.store.stock(city.cityId()).get(BREAD));assertTrue(r.service.responses(city.cityId(),64,0).isEmpty());}
        }
    }
}

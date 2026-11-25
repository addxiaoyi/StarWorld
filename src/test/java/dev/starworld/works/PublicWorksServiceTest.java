package dev.starworld.works;

import dev.starworld.domain.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.evolution.*;
import dev.starworld.persistence.WorldStore;
import dev.starworld.works.PublicWorksState.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PublicWorksServiceTest {
    @TempDir Path temp;
    static final String BREAD="minecraft:bread",PLANKS="minecraft:oak_planks";
    final UUID world=UUID.randomUUID();
    final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"city","nation",1,"core");
    final Definitions definitions=new Definitions(10,64,20,10000,0,
            Map.of("plains",terrain("plains"),"river",terrain("river"),"mountains",terrain("mountains")),
            Map.of("agrarian",new Definitions.Civilization("agrarian","agrarian",Map.of()),"industrial",new Definitions.Civilization("industrial","industrial",Map.of())),
            Map.of("farm",building("farm"),"well",building("well"),"workshop",building("workshop"),"warehouse",building("warehouse")),
            List.of(new Definitions.Recipe("bread","farm",Map.of(),Map.of(BREAD,10L),null,0)),Map.of());
    final EvolutionDefinitions evolutionOptions=new EvolutionDefinitions(Map.of("balanced",new EvolutionDefinitions.Plan("balanced",Map.of()),"industry",new EvolutionDefinitions.Plan("industry",Map.of())),Map.of(),6,100,1,2,Map.of());
    static Definitions.Terrain terrain(String id){return new Definitions.Terrain(id,id,1,1,1,Map.of("fertility",100L),Map.of("fertility",1L));}
    static Definitions.BuildingType building(String id){return new Definitions.BuildingType(id,id,"COMPOSTER",Map.of());}
    ProjectDefinition standard(){return new ProjectDefinition("irrigation_network","Irrigation",3,Map.of(BREAD,10L),Map.of(PLANKS,3L),Map.of(BREAD,2L),Set.of("farm","well"),Set.of("plains","river"),Set.of("balanced"),Set.of("agrarian"),2,1,Map.of("farm",1.15,"workshop",0.95));}
    PublicWorksDefinitions config(ProjectDefinition definition){return config(definition,200);}
    PublicWorksDefinitions config(ProjectDefinition definition,int history){return new PublicWorksDefinitions(definitions,evolutionOptions,new Settings(1,64,64,history,Map.of(definition.id(),definition)));}
    final class Rig implements AutoCloseable {
        final Path db=temp.resolve(UUID.randomUUID()+".db");WorldStore store;EvolutionService evolution;PublicWorksService service;PublicWorksDefinitions configuration;final Set<UUID> valid=new HashSet<>();
        Rig()throws Exception{this(config(standard()));}
        Rig(PublicWorksDefinitions configuration)throws Exception{this.configuration=configuration;open();store.ensureCity(city);evolution.profile(city.cityId());store.execute("UPDATE evolution_city SET knowledge=10,culture=10 WHERE city=?",city.cityId());store.stock(city.cityId(),BREAD,100);store.stock(city.cityId(),PLANKS,100);add(city,"farm",0,"plains");add(city,"well",1,"plains");add(city,"workshop",2,"plains");}
        void open()throws Exception{store=new WorldStore(db,10000);evolution=new EvolutionService(store,definitions,evolutionOptions);service=new PublicWorksService(store,configuration);}
        Building add(CoreCity owner,String type,int chunk,String terrain)throws Exception{Building building=new Building(UUID.randomUUID(),owner.cityId(),owner.nationId(),type,new Site(world,"world",chunk*16,64,0),1);store.addBuilding(building,terrain,definitions);valid.add(building.id());return building;}
        CityView view()throws Exception{return store.view(city.cityId()).orElseThrow();}
        Project start()throws Exception{return service.start(UUID.randomUUID(),city,"irrigation_network",10,valid,"actor");}
        Completion settle(long period)throws Exception{return settle(period,Set.copyOf(valid));}
        Completion settle(long period,Set<UUID> maintained)throws Exception{return store.tx(s->{CityView before=view();PublicWorksService.Cycle cycle=service.cycle(city,before,period,valid);Completion completion=cycle.completed(maintained);s.finish(city.cityId(),period,"test settlement");return completion;});}
        Settlement simulate(long period,boolean evolve,boolean failAfterWorks)throws Exception{return service.atomicSettlement(s->{CityView before=view();PublicWorksService.Cycle works=service.cycle(city,before,period,valid);Simulation.Hooks base=evolve?evolution.cycle(city,before,period,valid,EvolutionState.Context.empty()):Simulation.Hooks.NONE;return new Simulation(store,definitions).settle(city,period,valid,new Simulation.Hooks(){
            @Override public double multiplier(Building building)throws SQLException{return base.multiplier(building)*works.multiplier(building);}
            @Override public void completed(CoreCity core,CityView previous,long period,Map<String,Long> produced,Set<UUID> maintained)throws SQLException{base.completed(core,previous,period,produced,maintained);works.completed(maintained);if(failAfterWorks)throw new SQLException("later module failed");}
        });});}
        Project saved(UUID request)throws Exception{return service.project(request).orElseThrow();}
        void finishConstruction()throws Exception{settle(11);settle(12);assertTrue(settle(13).completedNow());}
        void restart()throws Exception{store.close();open();}
        public void close()throws Exception{store.close();}
    }
    @Test void upfrontCostAndNationalSlotAreDurableIdempotentAndImmutable()throws Exception{
        try(Rig rig=new Rig()){
            UUID request=UUID.randomUUID();Project project=rig.service.start(request,city,"irrigation_network",10,rig.valid,"actor");assertEquals(90,rig.store.stock(city.cityId()).get(BREAD));assertEquals(11,project.effectivePeriod());assertEquals(0,project.progress());assertEquals(10,project.startKnowledge());
            assertEquals(project,rig.service.start(request,city,"irrigation_network",0,Set.of(),"actor"));assertThrows(SQLException.class,()->rig.service.start(request,city,"another_definition",10,rig.valid,"actor"));assertThrows(SQLException.class,rig::start);
            assertEquals(90,rig.store.stock(city.cityId()).get(BREAD));rig.restart();assertEquals(project,rig.saved(request));assertEquals(project,rig.service.project(city.cityId(),city.nationId()).orElseThrow());
            assertThrows(UnsupportedOperationException.class,()->project.definition().upfrontCosts().clear());assertThrows(UnsupportedOperationException.class,()->rig.service.projects(city.cityId(),64,0).clear());assertThrows(UnsupportedOperationException.class,()->rig.service.history(city.cityId(),64,0).clear());
        }
    }
    @Test void constructionRequiresActualMaintainedFacilitiesAndOnlyAdvancesOnceWithoutOfflineCatchup()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();UUID farm=rig.view().buildings().stream().filter(b->b.type().equals("farm")).findFirst().orElseThrow().id();
            assertFalse(rig.settle(10).advanced());assertFalse(rig.settle(11,Set.of(farm)).advanced());assertEquals(100,rig.store.stock(city.cityId()).get(PLANKS));
            rig.store.tx(s->{PublicWorksService.Cycle cycle=rig.service.cycle(city,rig.view(),100,rig.valid);assertTrue(cycle.completed(rig.valid).advanced());assertFalse(cycle.completed(rig.valid).advanced());s.finish(city.cityId(),100,"gap");return null;});
            assertEquals(1,rig.saved(project.requestId()).progress());assertEquals(97,rig.store.stock(city.cityId()).get(PLANKS));rig.restart();assertFalse(rig.service.cycle(city,rig.view(),100,rig.valid).completed(rig.valid).advanced());assertEquals(1,rig.saved(project.requestId()).progress());
        }
    }
    @Test void completionDoesNotProduceGoodsOrOperateUntilTheNextPeriodAndOperatingDebitPrecedesSimulationStock()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();rig.settle(11);rig.settle(12);Building farm=rig.view().buildings().stream().filter(b->b.type().equals("farm")).findFirst().orElseThrow();
            rig.store.tx(s->{PublicWorksService.Cycle cycle=rig.service.cycle(city,rig.view(),13,rig.valid);assertFalse(cycle.operating());assertEquals(1,cycle.multiplier(farm));assertTrue(cycle.completed(rig.valid).completedNow());assertEquals(1,cycle.multiplier(farm));s.finish(city.cityId(),13,"complete");return null;});
            assertEquals(90,rig.store.stock(city.cityId()).get(BREAD));assertEquals(91,rig.store.stock(city.cityId()).get(PLANKS));assertTrue(rig.saved(project.requestId()).completed());
            Settlement settlement=rig.simulate(14,false,false);assertEquals(11,settlement.produced().get(BREAD));assertEquals(99,rig.store.stock(city.cityId()).get(BREAD));assertEquals(14,rig.saved(project.requestId()).lastOperationPeriod());assertTrue(rig.saved(project.requestId()).lastOperationPaid());
            assertFalse(rig.simulate(14,false,false).applied());assertEquals(99,rig.store.stock(city.cityId()).get(BREAD));assertThrows(SQLException.class,rig::start);
        }
    }
    @Test void operationCannotSpendThisPeriodsFutureProductionAndRecoversAfterTheNextFundedPeriod()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();rig.finishConstruction();rig.store.stock(city.cityId(),BREAD,0);
            assertEquals(10,rig.simulate(14,false,false).produced().get(BREAD));assertEquals(10,rig.store.stock(city.cityId()).get(BREAD));assertFalse(rig.saved(project.requestId()).lastOperationPaid());
            assertEquals(11,rig.simulate(15,false,false).produced().get(BREAD));assertEquals(19,rig.store.stock(city.cityId()).get(BREAD));assertTrue(rig.saved(project.requestId()).lastOperationPaid());
        }
    }
    @Test void samePeriodOperationIsChargedOnceAndReadQueriesNeverMutateWatermarks()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();rig.finishConstruction();long before=rig.store.stock(city.cityId()).get(BREAD);
            rig.store.tx(s->{PublicWorksService.Cycle first=rig.service.cycle(city,rig.view(),14,rig.valid),second=rig.service.cycle(city,rig.view(),14,rig.valid);assertTrue(first.operating());assertTrue(second.operating());assertEquals(before-2,rig.store.stock(city.cityId()).get(BREAD));s.finish(city.cityId(),14,"paid once");return null;});
            Project saved=rig.saved(project.requestId());int histories=rig.service.history(city.cityId(),64,0).size();for(int i=0;i<4;i++){rig.service.inspect(city,rig.view(),15,rig.valid);rig.service.projects(city.cityId(),64,0);rig.service.history(city.cityId(),64,0);}
            assertEquals(saved,rig.saved(project.requestId()));assertEquals(histories,rig.service.history(city.cityId(),64,0).size());assertEquals(before-2,rig.store.stock(city.cityId()).get(BREAD));rig.restart();assertFalse(rig.service.cycle(city,rig.view(),14,rig.valid).operating());
        }
    }
    @Test void identityLossFreezesTheOriginalSlotAndNewNationCannotReuseItsUuidOrFacilities()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();rig.store.invalidateCity(city.cityId());assertThrows(SQLException.class,()->rig.service.start(project.requestId(),city,"irrigation_network",10,rig.valid,"actor"));assertFalse(rig.service.cycle(city,rig.view(),11,rig.valid).completed(rig.valid).advanced());
            CoreCity conqueror=new CoreCity(city.cityId(),UUID.randomUUID(),"city","conqueror",1,"core");rig.store.ensureCity(conqueror);
            assertThrows(SQLException.class,()->rig.service.start(project.requestId(),city,"irrigation_network",10,rig.valid,"actor"));assertThrows(SQLException.class,()->rig.service.start(project.requestId(),conqueror,"irrigation_network",10,rig.valid,"actor"));assertThrows(SQLException.class,()->rig.service.start(UUID.randomUUID(),conqueror,"irrigation_network",10,rig.valid,"actor"));
            rig.add(conqueror,"farm",3,"plains");rig.add(conqueror,"well",4,"plains");Project replacement=rig.service.start(UUID.randomUUID(),conqueror,"irrigation_network",10,rig.valid,"actor");assertEquals(conqueror.nationId(),replacement.nationId());assertEquals(2,rig.service.projects(city.cityId(),64,0).size());assertEquals(0,rig.saved(project.requestId()).progress());
        }
    }
    @Test void missingMaterialsDoNotPartiallyDebitAndCompletedWorksPauseWhenFacilityOrTerrainBecomesInvalid()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();rig.store.stock(city.cityId(),PLANKS,2);assertFalse(rig.settle(11).advanced());assertEquals(2,rig.store.stock(city.cityId()).get(PLANKS));rig.store.stock(city.cityId(),PLANKS,100);rig.settle(12);rig.settle(13);rig.settle(14);
            Building well=rig.view().buildings().stream().filter(b->b.type().equals("well")).findFirst().orElseThrow();long bread=rig.store.stock(city.cityId()).get(BREAD);rig.store.execute("UPDATE region SET terrain='mountains' WHERE id=?",well.site().regionKey());
            assertFalse(rig.service.cycle(city,rig.view(),15,rig.valid).operating());assertEquals(bread,rig.store.stock(city.cityId()).get(BREAD));rig.store.execute("UPDATE region SET terrain='plains' WHERE id=?",well.site().regionKey());rig.valid.remove(well.id());assertFalse(rig.service.cycle(city,rig.view(),16,rig.valid).operating());assertEquals(bread,rig.store.stock(city.cityId()).get(BREAD));rig.valid.add(well.id());assertTrue(rig.service.cycle(city,rig.view(),17,rig.valid).operating());assertEquals(bread-2,rig.store.stock(city.cityId()).get(BREAD));assertTrue(rig.saved(project.requestId()).completed());
        }
    }
    @Test void planCivilizationEducationCultureTerrainAndUpfrontStockAreRecheckedWithNoPartialStart()throws Exception{
        try(Rig rig=new Rig()){
            rig.store.execute("UPDATE evolution_city SET plan='industry' WHERE city=?",city.cityId());assertThrows(SQLException.class,rig::start);rig.store.execute("UPDATE evolution_city SET plan='balanced',knowledge=1 WHERE city=?",city.cityId());assertThrows(SQLException.class,rig::start);
            rig.store.execute("UPDATE evolution_city SET knowledge=10,culture=0 WHERE city=?",city.cityId());assertThrows(SQLException.class,rig::start);rig.store.execute("UPDATE evolution_city SET culture=10 WHERE city=?",city.cityId());rig.store.civilization(city.cityId(),"industrial",1);assertThrows(SQLException.class,rig::start);rig.store.civilization(city.cityId(),"agrarian",7);
            Building well=rig.view().buildings().stream().filter(b->b.type().equals("well")).findFirst().orElseThrow();rig.store.execute("UPDATE region SET terrain='mountains' WHERE id=?",well.site().regionKey());assertThrows(SQLException.class,rig::start);rig.store.execute("UPDATE region SET terrain='plains' WHERE id=?",well.site().regionKey());rig.store.stock(city.cityId(),BREAD,9);
            List<Feasibility> preview=rig.service.inspect(city,rig.view(),10,rig.valid);assertFalse(preview.getFirst().eligible());assertEquals(1,preview.getFirst().missingUpfrontCosts().get(BREAD));assertThrows(SQLException.class,rig::start);assertEquals(9,rig.store.stock(city.cityId()).get(BREAD));assertTrue(rig.service.projects(city.cityId(),64,0).isEmpty());assertTrue(rig.service.history(city.cityId(),64,0).isEmpty());
            rig.store.stock(city.cityId(),BREAD,100);assertTrue(rig.service.inspect(city,rig.view(),10,rig.valid).getFirst().eligible());assertThrows(UnsupportedOperationException.class,()->preview.getFirst().missingUpfrontCosts().clear());
        }
    }
    @Test void configurationReplacementCannotRewritePaidCostsRequirementsThresholdsOrMultipliers()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();ProjectDefinition replacement=new ProjectDefinition("irrigation_network","Replacement",5,Map.of(BREAD,99L),Map.of(PLANKS,99L),Map.of(BREAD,99L),Set.of("warehouse"),Set.of("mountains"),Set.of("industry"),Set.of("industrial"),999,999,Map.of("farm",1.25));rig.configuration=config(replacement);rig.restart();rig.finishConstruction();
            assertEquals(project.definition(),rig.saved(project.requestId()).definition());assertEquals(91,rig.store.stock(city.cityId()).get(PLANKS));assertEquals(11,rig.simulate(14,false,false).produced().get(BREAD));assertEquals(99,rig.store.stock(city.cityId()).get(BREAD));
        }
    }
    @Test void requestHistoryFailureRollsBackSlotAndUpfrontCost()throws Exception{
        try(Rig rig=new Rig()){
            rig.store.execute("CREATE TRIGGER reject_works_start BEFORE INSERT ON public_works_history BEGIN SELECT RAISE(ABORT,'start failed'); END");assertThrows(SQLException.class,rig::start);assertEquals(100,rig.store.stock(city.cityId()).get(BREAD));assertTrue(rig.service.project(city.cityId(),city.nationId()).isEmpty());assertTrue(rig.service.history(city.cityId(),64,0).isEmpty());
            rig.store.execute("DROP TRIGGER reject_works_start");assertEquals(0,rig.start().progress());
        }
    }
    @Test void constructionHistoryFailureRollsBackBaseProductionEvolutionMaterialsProgressAndSettlement()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();rig.store.execute("CREATE TRIGGER reject_works_build BEFORE INSERT ON public_works_history WHEN NEW.kind='construction' BEGIN SELECT RAISE(ABORT,'build failed'); END");
            assertThrows(SQLException.class,()->rig.simulate(11,true,false));assertEquals(90,rig.store.stock(city.cityId()).get(BREAD));assertEquals(100,rig.store.stock(city.cityId()).get(PLANKS));assertEquals(0,rig.saved(project.requestId()).progress());assertEquals(-1,rig.saved(project.requestId()).lastConstructionAttemptPeriod());assertEquals(-1,rig.evolution.profile(city.cityId()).lastPeriod());assertEquals(-1,rig.view().lastPeriod());assertFalse(rig.store.settled(city.cityId(),11));
            rig.store.execute("DROP TRIGGER reject_works_build");assertTrue(rig.simulate(11,true,false).applied());assertEquals(1,rig.saved(project.requestId()).progress());
        }
    }
    @Test void laterModuleFailureRollsBackOperatingDebitMultiplierReceiptAndCanRetryExactlyOnce()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();rig.finishConstruction();assertThrows(SQLException.class,()->rig.simulate(14,false,true));assertEquals(90,rig.store.stock(city.cityId()).get(BREAD));assertEquals(-1,rig.saved(project.requestId()).lastOperationPeriod());assertEquals(13,rig.view().lastPeriod());assertFalse(rig.store.settled(city.cityId(),14));
            rig.restart();assertEquals(11,rig.simulate(14,false,false).produced().get(BREAD));assertEquals(99,rig.store.stock(city.cityId()).get(BREAD));assertEquals(14,rig.saved(project.requestId()).lastOperationPeriod());
        }
    }
    @Test void historyIsBoundedButPermanentOperationWatermarkSurvivesPruningAndRestart()throws Exception{
        try(Rig rig=new Rig(config(standard(),10))){
            Project project=rig.start();rig.finishConstruction();for(int period=14;period<40;period++)rig.settle(period);assertEquals(10,rig.service.history(city.cityId(),64,0).size());assertEquals(39,rig.saved(project.requestId()).lastOperationPeriod());long bread=rig.store.stock(city.cityId()).get(BREAD);
            rig.restart();assertFalse(rig.service.cycle(city,rig.view(),14,rig.valid).operating());assertEquals(bread,rig.store.stock(city.cityId()).get(BREAD));assertEquals(39,rig.saved(project.requestId()).lastOperationPeriod());assertEquals(3,rig.saved(project.requestId()).progress());
        }
    }
    @Test void evidenceAndPaginationAreBoundedAndFutureDatabaseSchemaFailsClosed()throws Exception{
        try(Rig rig=new Rig()){
            Set<UUID> huge=new HashSet<>(rig.valid);for(int i=0;i<65;i++)huge.add(UUID.randomUUID());assertThrows(IllegalArgumentException.class,()->rig.service.inspect(city,rig.view(),10,huge));assertThrows(IllegalArgumentException.class,()->rig.service.start(UUID.randomUUID(),city,"irrigation_network",10,huge,"actor"));assertFalse(rig.service.cycle(city,rig.view(),11,huge).operating());assertThrows(IllegalArgumentException.class,()->rig.service.projects(city.cityId(),65,0));assertThrows(IllegalArgumentException.class,()->rig.service.history(city.cityId(),1,-1));
            rig.store.execute("UPDATE public_works_schema SET version=99");assertThrows(SQLException.class,()->new PublicWorksService(rig.store,rig.configuration));
        }
    }
    @Test void handledNestedStartFailureRestoresFeeAndSlotWhileKeepingUnrelatedOuterWrites()throws Exception{
        try(Rig rig=new Rig()){
            rig.store.execute("CREATE TRIGGER reject_nested_start BEFORE INSERT ON public_works_history BEGIN SELECT RAISE(ABORT,'nested start failed'); END");
            rig.store.tx(s->{s.audit("test","KEEP_OUTER_START");assertThrows(SQLException.class,rig::start);s.audit("test","KEEP_OUTER_END");return null;});
            assertEquals(100,rig.store.stock(city.cityId()).get(BREAD));assertTrue(rig.service.project(city.cityId(),city.nationId()).isEmpty());assertTrue(rig.service.history(city.cityId(),64,0).isEmpty());assertEquals(2,rig.store.query("SELECT COUNT(*) FROM audit WHERE action IN ('KEEP_OUTER_START','KEEP_OUTER_END')",r->r.getInt(1)).getFirst());
        }
    }
    @Test void handledNestedOperatingFailureRestoresFeeAndWatermarkWhileOuterCommitSucceeds()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();rig.finishConstruction();int history=rig.service.history(city.cityId(),64,0).size();rig.store.execute("CREATE TRIGGER reject_nested_operation BEFORE INSERT ON public_works_history WHEN NEW.kind='operating' BEGIN SELECT RAISE(ABORT,'nested operation failed'); END");
            rig.store.tx(s->{assertThrows(SQLException.class,()->rig.service.cycle(city,rig.view(),14,rig.valid));s.audit("test","KEEP_OUTER_OPERATION");return null;});
            assertEquals(90,rig.store.stock(city.cityId()).get(BREAD));assertEquals(-1,rig.saved(project.requestId()).lastOperationPeriod());assertEquals(history,rig.service.history(city.cityId(),64,0).size());assertEquals(1,rig.store.query("SELECT COUNT(*) FROM audit WHERE action='KEEP_OUTER_OPERATION'",r->r.getInt(1)).getFirst());
        }
    }
    @Test void handledNestedConstructionFailureRestoresMaterialsAttemptAndProgressBeforeOuterCommit()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();rig.store.execute("CREATE TRIGGER reject_nested_construction BEFORE INSERT ON public_works_history WHEN NEW.kind='construction' BEGIN SELECT RAISE(ABORT,'nested construction failed'); END");
            rig.store.tx(s->{PublicWorksService.Cycle cycle=rig.service.cycle(city,rig.view(),11,rig.valid);assertThrows(SQLException.class,()->cycle.completed(rig.valid));s.audit("test","KEEP_OUTER_CONSTRUCTION");return null;});
            assertEquals(100,rig.store.stock(city.cityId()).get(PLANKS));assertEquals(0,rig.saved(project.requestId()).progress());assertEquals(-1,rig.saved(project.requestId()).lastConstructionAttemptPeriod());assertEquals(1,rig.service.history(city.cityId(),64,0).size());assertEquals(1,rig.store.query("SELECT COUNT(*) FROM audit WHERE action='KEEP_OUTER_CONSTRUCTION'",r->r.getInt(1)).getFirst());
        }
    }
    @Test void handledFailureAfterTheWholeSimulationRestoresAllSettlementWritesThroughItsLocalSavepoint()throws Exception{
        try(Rig rig=new Rig()){
            Project project=rig.start();rig.finishConstruction();
            rig.store.tx(s->{s.audit("test","KEEP_BEFORE_SETTLEMENT");assertThrows(SQLException.class,()->rig.simulate(14,true,true));s.audit("test","KEEP_AFTER_SETTLEMENT");return null;});
            assertEquals(90,rig.store.stock(city.cityId()).get(BREAD));assertEquals(-1,rig.saved(project.requestId()).lastOperationPeriod());assertEquals(13,rig.view().lastPeriod());assertEquals(-1,rig.evolution.profile(city.cityId()).lastPeriod());assertFalse(rig.store.settled(city.cityId(),14));assertEquals(2,rig.store.query("SELECT COUNT(*) FROM audit WHERE action IN ('KEEP_BEFORE_SETTLEMENT','KEEP_AFTER_SETTLEMENT')",r->r.getInt(1)).getFirst());
            assertEquals(11,rig.simulate(14,false,false).produced().get(BREAD));assertEquals(99,rig.store.stock(city.cityId()).get(BREAD));
        }
    }
    @Test void dottedAndSlashedCustomItemsPersistAndDebitFromTheFrozenSnapshotAfterRestart()throws Exception{
        String flour="server.food:grains/wheat.flour",beam="server.parts:timber/oak.beam",roll="server.food:bread/roll";ProjectDefinition base=standard();ProjectDefinition custom=new ProjectDefinition(base.id(),base.name(),base.buildPeriods(),Map.of(flour,7L),Map.of(beam,3L),Map.of(roll,2L),base.facilities(),base.terrains(),base.plans(),base.civilizations(),base.knowledge(),base.culture(),base.multipliers());
        try(Rig rig=new Rig(config(custom))){for(String item:List.of(flour,beam,roll))rig.store.stock(city.cityId(),item,100);Project project=rig.start();assertEquals(93,rig.store.stock(city.cityId()).get(flour));rig.restart();assertEquals(custom,rig.saved(project.requestId()).definition());rig.finishConstruction();assertEquals(91,rig.store.stock(city.cityId()).get(beam));rig.simulate(14,false,false);assertEquals(98,rig.store.stock(city.cityId()).get(roll));assertEquals(93,rig.store.stock(city.cityId()).get(flour));}
    }
    @Test void atomicSettlementRethrowsTheOriginalErrorAfterRollingBackItsWrites()throws Exception{
        try(Rig rig=new Rig()){
            AssertionError failure=new AssertionError("fatal settlement failure");
            AssertionError propagated=assertThrows(AssertionError.class,()->rig.service.atomicSettlement(s->{s.stock(city.cityId(),BREAD,0);throw failure;}));
            assertSame(failure,propagated);assertEquals(100,rig.store.stock(city.cityId()).get(BREAD));
        }
    }
}

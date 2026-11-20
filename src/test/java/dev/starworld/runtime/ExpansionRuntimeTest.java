package dev.starworld.runtime;

import dev.starworld.config.ConfigLoader;
import dev.starworld.domain.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.*;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ExpansionRuntimeTest {
    @TempDir Path temp;Definitions definitions;
    @BeforeEach void prepare()throws Exception{
        for(String file:List.of("config.yml","definitions.yml","evolution.yml","exports.yml","logistics.yml"))try(var in=getClass().getResourceAsStream("/"+file)){Files.copy(Objects.requireNonNull(in),temp.resolve(file));}
        Files.writeString(temp.resolve("config.yml"),"config-version: 2\nsimulation:\n  event-chance: 0\n");
        try(var mock=mockStatic(Material.class)){Material block=mock(Material.class);when(block.isBlock()).thenReturn(true);mock.when(()->Material.matchMaterial(anyString())).thenReturn(block);definitions=ConfigLoader.load(temp.resolve("config.yml").toFile(),temp.resolve("definitions.yml").toFile(),temp.resolve("evolution.yml").toFile());}
    }
    ExpansionRuntime expansion(WorldStore store)throws Exception{JavaPlugin plugin=mock(JavaPlugin.class);org.bukkit.Server server=mock(org.bukkit.Server.class);when(plugin.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));return new ExpansionRuntime(plugin,mock(WorldRuntime.class),definitions,mock(CoreBridge.class),mock(CustomItems.class),store,temp.toFile());}
    CoreCity city(){return new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"城市","国家",1.1,"public API");}
    AuthoritySnapshot tradeAuthority(CoreCity a,CoreCity b,long period,Set<String> anchors,Set<UUID> nodes){return new AuthoritySnapshot(a.cityId(),period,Map.of(a.cityId(),a.nationId(),b.cityId(),b.nationId()),Set.of(a.cityId(),b.cityId()),anchors,nodes,Set.of(TradePair.of(a.cityId(),b.cityId())));}
    void completePhysicalTrade(WorldStore s,ExpansionRuntime x,CoreCity a,CoreCity b,long period)throws Exception{
        for(CoreCity c:List.of(a,b)){s.ensureCity(c);for(String item:List.of("minecraft:bread","minecraft:iron_ingot","minecraft:oak_planks"))s.stock(c.cityId(),item,100);}
        UUID world=UUID.randomUUID();Site first=new Site(world,"world",0,64,0),second=new Site(world,"world",32,64,0);Set<String> anchors=Set.of(first.anchorKey(),second.anchorKey());
        var logistics=x.logistics();AuthoritySnapshot registration=tradeAuthority(a,b,period-1,anchors,Set.of());
        Node left=logistics.registerNode(UUID.randomUUID(),a.cityId(),NodeType.ROAD,first,"plains",registration),right=logistics.registerNode(UUID.randomUUID(),b.cityId(),NodeType.ROAD,second,"plains",registration);
        Set<UUID> nodes=Set.of(left.id(),right.id());AuthoritySnapshot managers=tradeAuthority(a,b,period-1,anchors,nodes);
        Route route=logistics.createRoute(UUID.randomUUID(),a.cityId(),b.cityId(),List.of(left.id(),right.id()),Mode.LAND,managers);
        var order=logistics.createOffer(UUID.randomUUID(),route.id(),Map.of("minecraft:bread",10L),Map.of("minecraft:iron_ingot",2L),managers);logistics.accept(order.id(),managers);
        assertEquals(1,logistics.settle(tradeAuthority(a,b,period,anchors,nodes),10).completed());assertEquals(OrderStatus.COMPLETED,logistics.order(order.id()).orElseThrow().status());
    }
    long tradeActions(WorldStore s,UUID city)throws Exception{return s.query("SELECT COUNT(*) FROM evolution_trade_action WHERE city=?",r->r.getLong(1),city).getFirst();}
    @Test void completedTradeContributesOnceAcrossLaterCyclesAndRestart()throws Exception{
        Path db=temp.resolve("trade-once.db");CoreCity a=city(),b=city();
        try(WorldStore s=new WorldStore(db,definitions.stockLimit())){ExpansionRuntime x=expansion(s);completePhysicalTrade(s,x,a,b,10);Simulation sim=new Simulation(s,definitions);
            assertTrue(x.settle(a,11,Set.of(),Optional.empty(),sim).applied());assertEquals(1,x.evolution().profile(a.cityId()).tendencies().get("commerce"));assertEquals(1,tradeActions(s,a.cityId()));
            assertFalse(x.settle(a,11,Set.of(),Optional.empty(),sim).applied());assertTrue(x.settle(a,12,Set.of(),Optional.empty(),sim).applied());assertEquals(1,x.evolution().profile(a.cityId()).tendencies().get("commerce"));
            assertTrue(x.settle(b,12,Set.of(),Optional.empty(),sim).applied());assertEquals(1,x.evolution().profile(b.cityId()).tendencies().get("commerce"));assertEquals(1,tradeActions(s,b.cityId()),"Each legitimate endpoint consumes its own contribution once");
        }
        try(WorldStore s=new WorldStore(db,definitions.stockLimit())){ExpansionRuntime x=expansion(s);assertTrue(x.settle(a,13,Set.of(),Optional.empty(),new Simulation(s,definitions)).applied());assertEquals(1,x.evolution().profile(a.cityId()).tendencies().get("commerce"));assertEquals(1,tradeActions(s,a.cityId()));}
    }
    @Test void failedSettlementDoesNotConsumeTradeReceiptAndRestartCanRetryExactlyOnce()throws Exception{
        Path db=temp.resolve("trade-rollback.db");CoreCity a=city(),b=city();
        try(WorldStore s=new WorldStore(db,definitions.stockLimit())){ExpansionRuntime x=expansion(s);completePhysicalTrade(s,x,a,b,10);s.execute("CREATE TRIGGER reject_trade_settlement BEFORE INSERT ON settlement BEGIN SELECT RAISE(ABORT,'trade receipt crash boundary'); END");
            assertThrows(SQLException.class,()->x.settle(a,11,Set.of(),Optional.empty(),new Simulation(s,definitions)));assertEquals(0,tradeActions(s,a.cityId()));assertEquals(0,x.evolution().profile(a.cityId()).tendencies().get("commerce"));assertFalse(s.settled(a.cityId(),11));
        }
        try(WorldStore s=new WorldStore(db,definitions.stockLimit())){ExpansionRuntime x=expansion(s);assertEquals(0,tradeActions(s,a.cityId()));s.execute("DROP TRIGGER reject_trade_settlement");Simulation sim=new Simulation(s,definitions);assertTrue(x.settle(a,11,Set.of(),Optional.empty(),sim).applied());assertTrue(x.settle(a,12,Set.of(),Optional.empty(),sim).applied());assertEquals(1,x.evolution().profile(a.cityId()).tendencies().get("commerce"));assertEquals(1,tradeActions(s,a.cityId()));}
    }
    @Test void priorNationTradeCannotCreditConqueringNationAndRemainsAvailableToOriginalOwner()throws Exception{
        try(WorldStore s=new WorldStore(temp.resolve("trade-ownership.db"),definitions.stockLimit())){ExpansionRuntime x=expansion(s);CoreCity a=city(),b=city();completePhysicalTrade(s,x,a,b,10);CoreCity conquered=new CoreCity(a.cityId(),UUID.randomUUID(),a.cityName(),"新国",1,"current core snapshot");Simulation sim=new Simulation(s,definitions);
            assertTrue(x.settle(conquered,11,Set.of(),Optional.empty(),sim).applied());assertEquals(0,x.evolution().profile(a.cityId()).tendencies().get("commerce"));assertEquals(0,tradeActions(s,a.cityId()));assertEquals(0,x.national().view(conquered.nationId()).tendencies().get("commerce"));
            assertTrue(x.settle(a,12,Set.of(),Optional.empty(),sim).applied());assertEquals(1,x.evolution().profile(a.cityId()).tendencies().get("commerce"));assertEquals(1,tradeActions(s,a.cityId()));
        }
    }
    @Test void lateLifeContributionIsAppliedOnNextSettlementAndDuplicateTickCannotDrainIt()throws Exception{
        try(WorldStore s=new WorldStore(temp.resolve("late.db"),definitions.stockLimit())){CoreCity c=city();s.ensureCity(c);ExpansionRuntime x=expansion(s);Simulation sim=new Simulation(s,definitions);
            x.settle(c,10,Set.of(),Optional.empty(),sim);s.stock(c.cityId(),"minecraft:bread",30);UUID chef=UUID.randomUUID();x.life().chooseProfession(chef,"chef",10);x.life().feast(UUID.randomUUID(),c,chef,"community",10,Set.of("minecraft:bread"));
            long before=x.evolution().profile(c.cityId()).culture();assertFalse(x.settle(c,10,Set.of(),Optional.empty(),sim).applied());assertEquals(before,x.evolution().profile(c.cityId()).culture());
            assertTrue(x.settle(c,11,Set.of(),Optional.empty(),sim).applied());assertEquals(before+12,x.evolution().profile(c.cityId()).culture());x.settle(c,12,Set.of(),Optional.empty(),sim);assertEquals(before+12,x.evolution().profile(c.cityId()).culture());
        }
    }
    @Test void failingReceiptRollsBackCrossModuleLifeDrainAndMilitaryReadinessThenRestartRecovers()throws Exception {
        Path db=temp.resolve("atomic.db");CoreCity c=city();UUID soldier=UUID.randomUUID();Building b=new Building(UUID.randomUUID(),c.cityId(),c.nationId(),"barracks",new Site(UUID.randomUUID(),"world",0,64,0),1);
        try(WorldStore s=new WorldStore(db,definitions.stockLimit())){s.ensureCity(c);s.addBuilding(b,"mountains",definitions);ExpansionRuntime x=expansion(s);s.stock(c.cityId(),"minecraft:bread",40);s.stock(c.cityId(),"minecraft:arrow",40);x.life().chooseProfession(soldier,"chef",10);x.life().feast(UUID.randomUUID(),c,soldier,"community",10,Set.of("minecraft:bread"));s.execute("CREATE TRIGGER reject_final_receipt BEFORE INSERT ON settlement BEGIN SELECT RAISE(ABORT,'crash boundary'); END");
            assertThrows(Exception.class,()->x.settle(c,11,Set.of(b.id()),Optional.empty(),new Simulation(s,definitions)));assertTrue(x.evolution().readiness(c.cityId()).isEmpty());assertEquals(40,s.stock(c.cityId()).get("minecraft:arrow"));assertEquals(0,s.query("SELECT COUNT(*) FROM life_evolution_action",r->r.getInt(1)).getFirst());s.execute("DROP TRIGGER reject_final_receipt");
        }
        try(WorldStore s=new WorldStore(db,definitions.stockLimit())){ExpansionRuntime x=expansion(s);assertTrue(x.settle(c,11,Set.of(b.id()),Optional.empty(),new Simulation(s,definitions)).applied());assertEquals(12,x.evolution().profile(c.cityId()).culture());var readiness=x.evolution().readiness(c.cityId()).orElseThrow();assertEquals(4,readiness.provided());assertEquals(1.6,readiness.terrainDefense());assertEquals(1.6,readiness.readiness());assertEquals(36,s.stock(c.cityId()).get("minecraft:arrow"));}
    }
    @Test void specialtyCallbackRechecksMembershipBeforeMutatingPreviousCity()throws Exception{
        try(WorldStore s=new WorldStore(temp.resolve("revoked.db"),definitions.stockLimit())){
            CoreCity c=city();s.ensureCity(c);JavaPlugin plugin=mock(JavaPlugin.class);org.bukkit.Server server=mock(org.bukkit.Server.class);when(plugin.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));WorldRuntime runtime=mock(WorldRuntime.class);org.bukkit.entity.Player player=mock(org.bukkit.entity.Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(runtime.playerCity(player)).thenReturn(Optional.of(c),Optional.empty());when(runtime.manage(player,c.cityId())).thenReturn(true);when(runtime.open(c)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(s.view(c.cityId()).orElseThrow()));
            doAnswer(call->{java.util.concurrent.CompletableFuture<?> future=call.getArgument(1);java.util.function.Consumer<Object> action=call.getArgument(2);action.accept(future.join());return null;}).when(runtime).reply(any(),any(),any());
            ExpansionRuntime x=new ExpansionRuntime(plugin,runtime,definitions,mock(CoreBridge.class),mock(CustomItems.class),s,temp.toFile());
            assertThrows(IllegalArgumentException.class,()->x.command(player,new String[]{"specialty","granary"}));assertEquals("",x.evolution().profile(c.cityId()).specialty());verify(runtime,never()).verifiedBuildings(any());
        }
    }
    @Test void publicBorderCaptureRechecksActualBuildingsRatherThanTrustingCallerIds()throws Exception{
        try(WorldStore s=new WorldStore(temp.resolve("border-capture.db"),definitions.stockLimit())){
            CoreCity c=city();s.ensureCity(c);Building b=new Building(UUID.randomUUID(),c.cityId(),c.nationId(),"farm",new Site(UUID.randomUUID(),"world",0,64,0),1);s.addBuilding(b,"plains",definitions);var registered=s.view(c.cityId()).orElseThrow();
            JavaPlugin plugin=mock(JavaPlugin.class);org.bukkit.Server server=mock(org.bukkit.Server.class);when(plugin.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);when(server.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));WorldRuntime runtime=mock(WorldRuntime.class);CoreBridge core=mock(CoreBridge.class);when(runtime.currentPeriod()).thenReturn(10L);when(runtime.verifiedBuildings(registered)).thenReturn(Set.of());
            ExpansionRuntime x=new ExpansionRuntime(plugin,runtime,definitions,core,mock(CustomItems.class),s,temp.toFile());assertTrue(x.captureBorderEvidence(c,registered,Set.of(b.id())).witnesses().isEmpty());verifyNoInteractions(core);when(runtime.verifiedBuildings(registered)).thenReturn(Set.of(b.id()));when(core.foreignNeighbors(c,b.site())).thenReturn(List.of());x.captureBorderEvidence(c,registered,Set.of(b.id()));verify(core).foreignNeighbors(c,b.site());
        }
    }
}

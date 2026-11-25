package dev.starworld.market;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.domain.WorldState.Site;
import dev.starworld.integration.*;
import dev.starworld.logistics.*;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.market.MarketState.*;
import dev.starworld.runtime.WorldRuntime;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MarketControllerTest {
    final class CommandRig {
        final JavaPlugin plugin=mock(JavaPlugin.class);
        final WorldRuntime runtime=mock(WorldRuntime.class);
        final CoreBridge core=mock(CoreBridge.class);
        final CoreExtensionPort extensions=mock(CoreExtensionPort.class);
        final MarketService market=mock(MarketService.class);
        final LogisticsService logistics=mock(LogisticsService.class);
        final TreasuryTransferPort treasury=mock(TreasuryTransferPort.class);
        final Player player=mock(Player.class);
        final CoreCity buyer=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"buyer","buyer nation",1,"test"),
                seller=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"seller","seller nation",1,"test");
        final UUID actor=UUID.randomUUID(),listing=UUID.randomUUID(),route=UUID.randomUUID();
        final MarketController controller;
        CommandRig()throws Exception{
            Server server=mock(Server.class);when(plugin.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);
            when(player.getUniqueId()).thenReturn(actor);when(player.isOnline()).thenReturn(true);
            when(runtime.playerCity(player)).thenReturn(Optional.of(buyer));when(runtime.manage(player,buyer.cityId())).thenReturn(true);
            when(runtime.currentPeriod()).thenReturn(4L);when(core.canGovern(actor,buyer.cityId())).thenReturn(true);
            when(market.listing(listing)).thenReturn(Optional.of(new Listing(listing,seller.cityId(),seller.nationId(),"minecraft:bread",20,new BigDecimal("1.25"),ListingStatus.AVAILABLE,1,49)));
            when(logistics.route(route)).thenReturn(Optional.of(new Route(route,seller.cityId(),buyer.cityId(),List.of(),Mode.LAND,true,32,1,256)));
            for(CoreCity city:List.of(seller,buyer)){
                when(core.city(city.cityId())).thenReturn(Optional.of(city));UUID peer=city==seller?buyer.nationId():seller.nationId();
                when(extensions.snapshot(eq(city.nationId()),anySet())).thenReturn(Optional.of(new CoreExtensionPort.NationSnapshot(city.nationId(),city.nationName(),Instant.EPOCH,"clear",Map.of(),null,Set.of(),Map.of(),"",Map.of(),Map.of(),1,Set.of(),Map.of(peer,"NEUTRAL"),List.of(),0,Map.of("war","available","diplomacy","available"))));
            }
            when(market.buy(any(),eq(listing),eq(route),eq(buyer.cityId()),eq(buyer.nationId()),eq(6L),any())).thenAnswer(call->purchase(call.getArgument(0),6));
            when(market.buy(any(),eq(listing),eq(route),eq(buyer.cityId()),eq(buyer.nationId()),any())).thenAnswer(call->purchase(call.getArgument(0),20));
            immediate(runtime);controller=new MarketController(plugin,runtime,core,extensions,logistics,market,treasury);
        }
        Purchase purchase(UUID id,long units){return new Purchase(id,listing,buyer.cityId(),buyer.nationId(),seller.cityId(),seller.nationId(),route,UUID.randomUUID(),"market:"+id,new BigDecimal("1.25").multiply(BigDecimal.valueOf(units)),PurchaseStatus.PENDING,0,0,"",4,units);}
        void buy(String quantity){controller.execute(player,quantity==null?new String[]{"market","buy",listing.toString(),route.toString()}:new String[]{"market","buy",listing.toString(),route.toString(),quantity});}
    }
    @Test void explicitQuantityAndOmittedQuantityUseTheirOwnAuthorizedReservationMode()throws Exception{
        CommandRig r=new CommandRig();r.buy("6");
        verify(r.market).buy(any(),eq(r.listing),eq(r.route),eq(r.buyer.cityId()),eq(r.buyer.nationId()),eq(6L),argThat(a->a.period()==4&&a.managedCities().contains(r.buyer.cityId())&&a.permittedTrades().contains(TradePair.of(r.seller.cityId(),r.buyer.cityId()))));
        verify(r.player).sendMessage(contains("数量 6 · 将从本国库原子支付 7.50"));
        r.buy(null);verify(r.market).buy(any(),eq(r.listing),eq(r.route),eq(r.buyer.cityId()),eq(r.buyer.nationId()),any());
        verify(r.player).sendMessage(contains("数量 20 · 将从本国库原子支付 25.00"));verifyNoInteractions(r.treasury);
    }
    @Test void explicitQuantityStillRechecksFounderAfterCapturingAuthority()throws Exception{
        CommandRig r=new CommandRig();when(r.core.canGovern(r.actor,r.buyer.cityId())).thenReturn(true,true,false);r.buy("6");
        verify(r.player).sendMessage(contains("国家金币付款需要"));
        verify(r.market,never()).buy(any(),any(),any(),any(),any(),anyLong(),any());
        verify(r.market,never()).buy(any(),any(),any(),any(),any(),any());verifyNoInteractions(r.treasury);
    }
    @Test void malformedOrExcessiveQuantityDoesNotStartStorageWork()throws Exception{
        CommandRig r=new CommandRig();for(String quantity:List.of("0","-1","+1","01","1.5","1e2"," 6","1000001","999999999999999999999"))r.buy(quantity);
        verify(r.runtime,never()).async(any());verifyNoInteractions(r.market,r.treasury);
        assertEquals(1,MarketController.parseQuantity("1"));assertEquals(1_000_000,MarketController.parseQuantity("1000000"));
    }
    @SuppressWarnings("unchecked")
    void immediate(WorldRuntime runtime){when(runtime.async(any())).thenAnswer(c->{try{return CompletableFuture.completedFuture(((WorldRuntime.Work<?>)c.getArgument(0)).run());}catch(Exception error){return CompletableFuture.failedFuture(error);}});when(runtime.globalFuture(any())).thenAnswer(c->{try{return CompletableFuture.completedFuture(((Callable<?>)c.getArgument(0)).call());}catch(Exception error){return CompletableFuture.failedFuture(error);}});doAnswer(c->{CompletableFuture<?> result=c.getArgument(1);((Consumer<Object>)c.getArgument(2)).accept(result.join());return null;}).when(runtime).reply(any(),any(),any());}
    @Test void retiredGlobalDispatchTerminatesTickAndReleasesNextPassWithoutPayment()throws Exception{
        JavaPlugin plugin=mock(JavaPlugin.class);WorldRuntime runtime=mock(WorldRuntime.class);CoreBridge core=mock(CoreBridge.class);MarketService market=mock(MarketService.class);TreasuryTransferPort treasury=mock(TreasuryTransferPort.class);
        immediate(runtime);when(market.definitions()).thenReturn(MarketDefinitions.defaults());when(market.leaseDue(anyInt(),anyLong())).thenReturn(List.of());when(market.leasePaid(anyInt(),anyLong())).thenReturn(List.of());when(market.expiring(anyLong(),anyInt())).thenReturn(List.of());
        CompletableFuture<Object> scheduled=new CompletableFuture<>();when(runtime.globalFuture(any())).thenAnswer(call->scheduled);
        MarketController controller=new MarketController(plugin,runtime,core,mock(CoreExtensionPort.class),mock(LogisticsService.class),market,treasury);
        var result=controller.tickFuture();assertFalse(result.isDone());assertTrue(controller.tickFuture().isDone());verify(market,times(1)).reconcileDeliveries(anyInt());
        IllegalStateException stopped=new IllegalStateException("scheduler stopped");scheduled.completeExceptionally(stopped);
        assertSame(stopped,assertThrows(ExecutionException.class,()->result.get(1,TimeUnit.SECONDS)).getCause());
        assertThrows(ExecutionException.class,()->controller.tickFuture().get(1,TimeUnit.SECONDS));verify(market,times(2)).reconcileDeliveries(anyInt());verifyNoInteractions(core,treasury);
    }
    @Test void publicMarketPurchaseRejectsCityManagerWithoutNationFounderPermission(){JavaPlugin plugin=mock(JavaPlugin.class);WorldRuntime runtime=mock(WorldRuntime.class);CoreBridge core=mock(CoreBridge.class);MarketService market=mock(MarketService.class);Player p=mock(Player.class);CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"市","国",1,"snapshot");when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(runtime.playerCity(p)).thenReturn(Optional.of(city));when(runtime.manage(p,city.cityId())).thenReturn(true);MarketController controller=new MarketController(plugin,runtime,core,mock(CoreExtensionPort.class),mock(LogisticsService.class),market,mock(TreasuryTransferPort.class));controller.execute(p,new String[]{"market","buy",UUID.randomUUID().toString(),UUID.randomUUID().toString()});verify(p).sendMessage(contains("国家金币付款需要"));verify(runtime,never()).async(any());verifyNoInteractions(market);}
    @Test void asyncPurchaseCallbackRechecksCurrentCityBeforePreparingAnyPayment()throws Exception{JavaPlugin plugin=mock(JavaPlugin.class);WorldRuntime runtime=mock(WorldRuntime.class);CoreBridge core=mock(CoreBridge.class);MarketService market=mock(MarketService.class);LogisticsService logistics=mock(LogisticsService.class);Player p=mock(Player.class);CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"市","国",1,"snapshot");UUID actor=UUID.randomUUID(),listing=UUID.randomUUID(),route=UUID.randomUUID(),seller=UUID.randomUUID();when(p.getUniqueId()).thenReturn(actor);when(runtime.playerCity(p)).thenReturn(Optional.of(city),Optional.empty());when(runtime.manage(p,city.cityId())).thenReturn(true);when(core.canGovern(actor,city.cityId())).thenReturn(true);when(market.listing(listing)).thenReturn(Optional.of(new Listing(listing,seller,UUID.randomUUID(),"minecraft:bread",20,new BigDecimal("1.00"),ListingStatus.AVAILABLE,1,10)));when(logistics.route(route)).thenReturn(Optional.of(new Route(route,seller,city.cityId(),List.of(),Mode.LAND,true,32,1,256)));immediate(runtime);MarketController controller=new MarketController(plugin,runtime,core,mock(CoreExtensionPort.class),logistics,market,mock(TreasuryTransferPort.class));controller.execute(p,new String[]{"market","buy",listing.toString(),route.toString()});verify(p).sendMessage(contains("请先加入"));verify(market,never()).buy(any(),any(),any(),any(),any(),any());}
    @Test void asyncPurchaseCallbackRechecksRevokedFounderBeforePreparingAnyPayment()throws Exception{JavaPlugin plugin=mock(JavaPlugin.class);WorldRuntime runtime=mock(WorldRuntime.class);CoreBridge core=mock(CoreBridge.class);MarketService market=mock(MarketService.class);LogisticsService logistics=mock(LogisticsService.class);Player p=mock(Player.class);CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"市","国",1,"snapshot");UUID actor=UUID.randomUUID(),listing=UUID.randomUUID(),route=UUID.randomUUID(),seller=UUID.randomUUID();when(p.getUniqueId()).thenReturn(actor);when(runtime.playerCity(p)).thenReturn(Optional.of(city));when(runtime.manage(p,city.cityId())).thenReturn(true);when(core.canGovern(actor,city.cityId())).thenReturn(true,false);when(market.listing(listing)).thenReturn(Optional.of(new Listing(listing,seller,UUID.randomUUID(),"minecraft:bread",20,new BigDecimal("1.00"),ListingStatus.AVAILABLE,1,10)));when(logistics.route(route)).thenReturn(Optional.of(new Route(route,seller,city.cityId(),List.of(),Mode.LAND,true,32,1,256)));immediate(runtime);MarketController controller=new MarketController(plugin,runtime,core,mock(CoreExtensionPort.class),logistics,market,mock(TreasuryTransferPort.class));controller.execute(p,new String[]{"market","buy",listing.toString(),route.toString()});verify(p).sendMessage(contains("国家金币付款需要"));verify(market,never()).buy(any(),any(),any(),any(),any(),any());}
    @Test void disabledPhysicalNodeAfterPurchasePreventsNewTransferButStillQueriesOriginalReceipt()throws Exception{
        JavaPlugin plugin=mock(JavaPlugin.class);WorldRuntime runtime=mock(WorldRuntime.class);CoreBridge core=mock(CoreBridge.class);CoreExtensionPort extensions=mock(CoreExtensionPort.class);MarketService market=mock(MarketService.class);LogisticsService logistics=mock(LogisticsService.class);TreasuryTransferPort treasury=mock(TreasuryTransferPort.class);Server server=mock(Server.class);World world=mock(World.class);Block block=mock(Block.class);
        UUID worldId=UUID.randomUUID(),routeId=UUID.randomUUID(),nodeId=UUID.randomUUID();CoreCity seller=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"卖城","卖国",1,"test"),buyer=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"买城","买国",1,"test");Site site=new Site(worldId,"world",0,64,0);Node disabled=new Node(nodeId,seller.cityId(),seller.nationId(),NodeType.ROAD,site,"plains",1,false);Route route=new Route(routeId,seller.cityId(),buyer.cityId(),List.of(nodeId),Mode.LAND,true,32,1,256);
        Purchase purchase=new Purchase(UUID.randomUUID(),UUID.randomUUID(),buyer.cityId(),buyer.nationId(),seller.cityId(),seller.nationId(),routeId,UUID.randomUUID(),"starworld:market:disabled-dispatch",new BigDecimal("25.00"),PurchaseStatus.IN_FLIGHT,1,0,"",1,20);Dispatch dispatch=new Dispatch(purchase,"lease");
        when(plugin.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);when(server.getWorld(worldId)).thenReturn(world);when(world.getName()).thenReturn("world");when(world.isChunkLoaded(0,0)).thenReturn(true);when(world.getBlockAt(0,64,0)).thenReturn(block);when(block.getType()).thenReturn(Material.LODESTONE);when(core.ownsSite(seller.cityId(),site)).thenReturn(true);when(core.city(seller.cityId())).thenReturn(Optional.of(seller));when(core.city(buyer.cityId())).thenReturn(Optional.of(buyer));when(runtime.currentPeriod()).thenReturn(2L);when(logistics.definitions()).thenReturn(LogisticsDefinitions.defaults());when(logistics.route(routeId)).thenReturn(Optional.of(route));when(logistics.node(nodeId)).thenReturn(Optional.of(disabled));when(market.definitions()).thenReturn(MarketDefinitions.defaults());when(market.leaseDue(anyInt(),anyLong())).thenReturn(List.of(dispatch));when(market.leasePaid(anyInt(),anyLong())).thenReturn(List.of());when(market.expiring(anyLong(),anyInt())).thenReturn(List.of());when(treasury.receipt(purchase.transactionId())).thenReturn(Optional.empty());
        for(CoreCity city:List.of(seller,buyer)){UUID peer=city==seller?buyer.nationId():seller.nationId();when(extensions.snapshot(eq(city.nationId()),anySet())).thenReturn(Optional.of(new CoreExtensionPort.NationSnapshot(city.nationId(),city.nationName(),Instant.EPOCH,"clear",Map.of(),null,Set.of(),Map.of(),"",Map.of(),Map.of(),1,Set.of(),Map.of(peer,"NEUTRAL"),List.of(),0,Map.of("war","available","diplomacy","available"))));}
        immediate(runtime);
        new MarketController(plugin,runtime,core,extensions,logistics,market,treasury).tickFuture().join();
        verify(treasury,never()).transfer(any());verify(treasury).receipt(purchase.transactionId());verify(market).finish(eq(dispatch),argThat(d->d.status()==TreasuryTransferPort.Status.RETRY),anyLong());verify(world).getBlockAt(0,64,0);
    }

    @Test void tightenedTransportLimitChecksOldReceiptWithoutNewChargeAndDoesNotBlockAnotherPurchase()throws Exception{
        JavaPlugin plugin=mock(JavaPlugin.class);WorldRuntime runtime=mock(WorldRuntime.class);CoreBridge core=mock(CoreBridge.class);CoreExtensionPort extensions=mock(CoreExtensionPort.class);MarketService market=mock(MarketService.class);LogisticsService logistics=mock(LogisticsService.class);TreasuryTransferPort treasury=mock(TreasuryTransferPort.class);Server server=mock(Server.class);World world=mock(World.class);Block block=mock(Block.class);
        UUID worldId=UUID.randomUUID(),nodeId=UUID.randomUUID();CoreCity seller=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"卖城","卖国",1,"test"),buyer=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"买城","买国",1,"test");Site site=new Site(worldId,"world",0,64,0);Node node=new Node(nodeId,seller.cityId(),seller.nationId(),NodeType.ROAD,site,"plains",1,true);
        Route limited=new Route(UUID.randomUUID(),seller.cityId(),buyer.cityId(),List.of(nodeId),Mode.LAND,true,512,4,256),valid=new Route(UUID.randomUUID(),seller.cityId(),buyer.cityId(),List.of(nodeId),Mode.LAND,true,64,1,256);
        Purchase old=new Purchase(UUID.randomUUID(),UUID.randomUUID(),buyer.cityId(),buyer.nationId(),seller.cityId(),seller.nationId(),limited.id(),UUID.randomUUID(),"starworld:market:limited",new BigDecimal("25.00"),PurchaseStatus.IN_FLIGHT,1,0,"",1,20),next=new Purchase(UUID.randomUUID(),UUID.randomUUID(),buyer.cityId(),buyer.nationId(),seller.cityId(),seller.nationId(),valid.id(),UUID.randomUUID(),"starworld:market:available",new BigDecimal("25.00"),PurchaseStatus.IN_FLIGHT,1,0,"",1,20);Dispatch held=new Dispatch(old,"old-lease"),ready=new Dispatch(next,"ready-lease");
        when(plugin.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);when(server.getWorld(worldId)).thenReturn(world);when(world.getName()).thenReturn("world");when(world.isChunkLoaded(0,0)).thenReturn(true);when(world.getBlockAt(0,64,0)).thenReturn(block);when(block.getType()).thenReturn(Material.LODESTONE);when(core.ownsSite(seller.cityId(),site)).thenReturn(true);when(core.city(seller.cityId())).thenReturn(Optional.of(seller));when(core.city(buyer.cityId())).thenReturn(Optional.of(buyer));when(runtime.currentPeriod()).thenReturn(2L);when(logistics.definitions()).thenReturn(LogisticsDefinitions.defaults());when(logistics.route(limited.id())).thenReturn(Optional.of(limited));when(logistics.route(valid.id())).thenReturn(Optional.of(valid));when(logistics.routeLimitation(limited)).thenReturn(Optional.of("最大运输时长已收紧"));when(logistics.routeLimitation(valid)).thenReturn(Optional.empty());when(logistics.node(nodeId)).thenReturn(Optional.of(node));when(market.definitions()).thenReturn(MarketDefinitions.defaults());when(market.leaseDue(anyInt(),anyLong())).thenReturn(List.of(held,ready));when(market.leasePaid(anyInt(),anyLong())).thenReturn(List.of());when(market.expiring(anyLong(),anyInt())).thenReturn(List.of());
        var receipt=new TreasuryTransferPort.Receipt(old.transactionId(),old.request().payer(),old.request().receiver(),old.total(),"OTHER",Instant.EPOCH);when(treasury.receipt(old.transactionId())).thenReturn(Optional.of(receipt));when(treasury.transfer(next.request())).thenReturn(new TreasuryTransferPort.Delivery(next.transactionId(),TreasuryTransferPort.Status.RETRY,null,"temporary failure"));
        for(CoreCity city:List.of(seller,buyer)){UUID peer=city==seller?buyer.nationId():seller.nationId();when(extensions.snapshot(eq(city.nationId()),anySet())).thenReturn(Optional.of(new CoreExtensionPort.NationSnapshot(city.nationId(),city.nationName(),Instant.EPOCH,"clear",Map.of(),null,Set.of(),Map.of(),"",Map.of(),Map.of(),1,Set.of(),Map.of(peer,"NEUTRAL"),List.of(),0,Map.of("war","available","diplomacy","available"))));}
        immediate(runtime);new MarketController(plugin,runtime,core,extensions,logistics,market,treasury).tickFuture().join();
        verify(treasury,never()).transfer(old.request());verify(treasury).receipt(old.transactionId());verify(treasury).transfer(next.request());verify(market).finish(eq(held),argThat(d->d.status()==TreasuryTransferPort.Status.CONFIRMED&&d.receipt()==receipt),anyLong());verify(market).finish(eq(ready),argThat(d->d.status()==TreasuryTransferPort.Status.RETRY),anyLong());
    }
}

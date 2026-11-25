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
            when(player.getUniqueId()).thenReturn(actor);when(player.isOnline()).thenReturn(true);when(player.hasPermission("starworld.use")).thenReturn(true);
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
    final class QuoteRig {
        final CommandRig r=new CommandRig();
        final MarketQuoteService quotes=mock(MarketQuoteService.class);
        final MarketTaxPort taxes=mock(MarketTaxPort.class);
        final MarketController controller;
        MarketQuoteService.Quote quoted;
        QuoteRig(String amount)throws Exception{
            when(taxes.quote(any(),eq(r.seller.nationId()),eq(r.actor),any())).thenAnswer(c->new MarketTaxPort.Quote(c.getArgument(0),r.seller.nationId(),r.actor,c.getArgument(3),new BigDecimal(amount)));
            when(quotes.prepare(eq(r.listing),eq(r.route),eq(r.buyer.cityId()),eq(r.buyer.nationId()),anyLong(),any(),any(),anyLong())).thenAnswer(c->{
                MarketTaxPort.Quote t=c.getArgument(5);long now=c.getArgument(7);
                return quoted=new MarketQuoteService.Quote(t.id(),r.listing,r.route,r.buyer.cityId(),r.buyer.nationId(),r.seller.nationId(),r.actor,c.getArgument(4),t.goodsTotal(),t.taxAmount(),now,now+60_000,null);
            });
            when(quotes.quote(any())).thenAnswer(c->Optional.of(quoted));
            when(quotes.accept(any(),eq(r.buyer.cityId()),eq(r.buyer.nationId()),any(),anyLong())).thenAnswer(c->purchase());
            controller=new MarketController(r.plugin,r.runtime,r.core,r.extensions,r.logistics,r.market,r.treasury,null,TreasuryRefundPort.unavailable(),quotes,taxes);
        }
        Purchase purchase(){return new Purchase(quoted.id(),r.listing,r.buyer.cityId(),r.buyer.nationId(),r.seller.cityId(),r.seller.nationId(),r.route,UUID.randomUUID(),"market:"+quoted.id(),quoted.total(),PurchaseStatus.PENDING,0,0,"",4,quoted.units(),quoted.goodsTotal(),quoted.taxAmount(),quoted.id());}
        void buy(String op,String quantity){controller.execute(r.player,new String[]{"market",op,r.listing.toString(),r.route.toString(),quantity});}
        void accept(){controller.execute(r.player,new String[]{"market","buy-quote",quoted.id().toString()});}
    }
    @Test void positiveTariffRequiresExplicitConsentAndAcceptanceNeverRequotes()throws Exception{
        QuoteRig f=new QuoteRig("0.22");f.buy("buy","6");
        verify(f.r.player).sendMessage(contains("货款 7.50 · 出口国关税 0.22 · 过境费 0.00 · 应付 7.72"));
        verify(f.quotes,never()).accept(any(),any(),any(),any(),anyLong());verifyNoInteractions(f.r.treasury);
        when(f.taxes.quote(any(),any(),any(),any())).thenThrow(new IllegalArgumentException("rate changed or API unavailable"));
        f.accept();verify(f.quotes).accept(eq(f.quoted.id()),eq(f.r.buyer.cityId()),eq(f.r.buyer.nationId()),argThat(a->a.managedCities().contains(f.r.buyer.cityId())),anyLong());
        verify(f.taxes,times(1)).quote(any(),any(),any(),any());verify(f.r.player).sendMessage(contains("原子支付 7.72"));verifyNoInteractions(f.r.treasury);
    }
    @Test void zeroTariffBuyReservesImmediatelyButExplicitPreviewNeverReserves()throws Exception{
        QuoteRig f=new QuoteRig("0.00");f.buy("buy","6");verify(f.quotes).accept(eq(f.quoted.id()),any(),any(),any(),anyLong());verify(f.r.player).sendMessage(contains("原子支付 7.50"));
        QuoteRig preview=new QuoteRig("0.00");preview.buy("quote","6");verify(preview.quotes,never()).accept(any(),any(),any(),any(),anyLong());verify(preview.r.player).sendMessage(contains("报价未预留商品"));
    }
    @Test void tariffQuoteFailureDoesNotPrepareReserveOrPay()throws Exception{
        QuoteRig f=new QuoteRig("0.22");when(f.taxes.quote(any(),any(),any(),any())).thenThrow(new IllegalArgumentException("核心关税报价暂不可用"));f.buy("buy","6");
        verifyNoInteractions(f.quotes,f.r.treasury);verify(f.r.market,never()).buy(any(),any(),any(),any(),any(),anyLong(),any());verify(f.r.player).sendMessage(contains("核心关税报价暂不可用"));
    }
    @Test void quoteCaptureRechecksRevokedFounderAndUsePermissionBeforeStorageWrites()throws Exception{
        for(String revoked:List.of("founder","permission")){
            QuoteRig f=new QuoteRig("0.22");
            when(f.taxes.quote(any(),any(),any(),any())).thenAnswer(c->{
                if(revoked.equals("founder"))when(f.r.core.canGovern(f.r.actor,f.r.buyer.cityId())).thenReturn(false);
                else when(f.r.player.hasPermission("starworld.use")).thenReturn(false);
                return new MarketTaxPort.Quote(c.getArgument(0),f.r.seller.nationId(),f.r.actor,c.getArgument(3),new BigDecimal("0.22"));
            });
            f.buy("buy","6");verifyNoInteractions(f.quotes,f.r.treasury);verify(f.r.player).sendMessage(contains(revoked.equals("founder")?"国家金币付款需要":"市场使用权限已失效"));
        }
    }
    @Test void quoteAcceptanceRechecksCityNationAndFounderAfterLookupAndCapture()throws Exception{
        for(String revoked:List.of("city","nation","founder","permission","capture")){
            QuoteRig f=new QuoteRig("0.22");f.buy("quote","6");
            if(revoked.equals("founder"))when(f.r.core.canGovern(f.r.actor,f.r.buyer.cityId())).thenReturn(true,false);
            else if(revoked.equals("capture"))when(f.r.core.canGovern(f.r.actor,f.r.buyer.cityId())).thenReturn(true,true,false);
            else when(f.quotes.quote(any())).thenAnswer(c->{
                if(revoked.equals("permission"))when(f.r.player.hasPermission("starworld.use")).thenReturn(false);
                else{CoreCity changed=new CoreCity(revoked.equals("city")?UUID.randomUUID():f.r.buyer.cityId(),UUID.randomUUID(),"changed","changed",1,"test");when(f.r.runtime.playerCity(f.r.player)).thenReturn(Optional.of(changed));when(f.r.runtime.manage(f.r.player,changed.cityId())).thenReturn(true);when(f.r.core.canGovern(f.r.actor,changed.cityId())).thenReturn(true);}
                return Optional.of(f.quoted);
            });
            f.accept();verify(f.quotes,never()).accept(any(),any(),any(),any(),anyLong());verifyNoInteractions(f.r.treasury);
        }
    }
    @Test void paymentDispatchUsesFrozenGoodsPlusTaxEvenWhenAssessmentIsUnavailable()throws Exception{
        QuoteRig f=new QuoteRig("0.22");f.buy("quote","6");Purchase p=f.purchase();Dispatch d=new Dispatch(p,"lease");
        when(f.r.market.definitions()).thenReturn(MarketDefinitions.defaults());when(f.r.market.leaseDue(anyInt(),anyLong())).thenReturn(List.of(d));when(f.r.market.leasePaid(anyInt(),anyLong())).thenReturn(List.of());when(f.r.market.expiring(anyLong(),anyInt())).thenReturn(List.of());
        when(f.r.treasury.transfer(p.request())).thenReturn(new TreasuryTransferPort.Delivery(p.transactionId(),TreasuryTransferPort.Status.RETRY,null,"unavailable"));clearInvocations(f.taxes);
        f.controller.tickFuture().join();f.controller.tickFuture().join();verify(f.r.treasury,times(2)).transfer(argThat(request->request.amount().compareTo(new BigDecimal("7.72"))==0&&request.transactionId().equals(p.transactionId())));verifyNoInteractions(f.taxes);
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
    @Test void physicalRoadGapDefersNewMoneyButStillReconcilesReceiptsAndRepairAllowsPayment()throws Exception{
        CommandRig r=new CommandRig();Server server=r.plugin.getServer();World world=mock(World.class);UUID worldId=UUID.randomUUID();
        var left=new Node(UUID.randomUUID(),r.seller.cityId(),r.seller.nationId(),NodeType.ROAD,new Site(worldId,"world",0,64,0),"plains",1,true);
        var right=new Node(UUID.randomUUID(),r.buyer.cityId(),r.buyer.nationId(),NodeType.ROAD,new Site(worldId,"world",32,64,0),"plains",1,true);
        when(r.logistics.definitions()).thenReturn(LogisticsDefinitions.defaults());when(r.logistics.node(left.id())).thenReturn(Optional.of(left));when(r.logistics.node(right.id())).thenReturn(Optional.of(right));
        when(r.logistics.route(r.route)).thenReturn(Optional.of(new Route(r.route,r.seller.cityId(),r.buyer.cityId(),List.of(left.id(),right.id()),Mode.LAND,true,32,1,256,true)));
        when(server.getWorld(worldId)).thenReturn(world);when(world.getName()).thenReturn("world");when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(r.core.ownsSite(any(),any())).thenReturn(true);
        Block air=mock(Block.class),road=mock(Block.class),anchor=mock(Block.class);when(air.getType()).thenReturn(Material.AIR);when(road.getType()).thenReturn(Material.STONE);when(anchor.getType()).thenReturn(Material.LODESTONE);
        java.util.concurrent.atomic.AtomicBoolean gap=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{int x=call.getArgument(0),y=call.getArgument(1);return y!=64?air:(x==0||x==32)?anchor:x==16&&gap.get()?air:road;});
        Purchase purchase=r.purchase(UUID.randomUUID(),7);Dispatch dispatch=new Dispatch(purchase,"lease");when(r.market.definitions()).thenReturn(MarketDefinitions.defaults());when(r.market.leaseDue(anyInt(),anyLong())).thenReturn(List.of(dispatch));when(r.market.leasePaid(anyInt(),anyLong())).thenReturn(List.of());when(r.market.expiring(anyLong(),anyInt())).thenReturn(List.of());
        when(r.treasury.receipt(purchase.transactionId())).thenReturn(Optional.empty());r.controller.tickFuture().join();verify(r.treasury,never()).transfer(any());verify(r.treasury).receipt(purchase.transactionId());
        verify(r.market).finish(eq(dispatch),argThat(d->d.status()==TreasuryTransferPort.Status.RETRY),anyLong());
        gap.set(false);when(r.treasury.transfer(purchase.request())).thenReturn(new TreasuryTransferPort.Delivery(purchase.transactionId(),TreasuryTransferPort.Status.RETRY,null,"test"));r.controller.tickFuture().join();verify(r.treasury).transfer(purchase.request());
        verify(world,never()).getChunkAt(anyInt(),anyInt());
    }
    @SuppressWarnings("unchecked")
    void immediate(WorldRuntime runtime){when(runtime.async(any())).thenAnswer(c->{try{return CompletableFuture.completedFuture(((WorldRuntime.Work<?>)c.getArgument(0)).run());}catch(Exception error){return CompletableFuture.failedFuture(error);}});when(runtime.globalFuture(any())).thenAnswer(c->{try{return CompletableFuture.completedFuture(((Callable<?>)c.getArgument(0)).call());}catch(Exception error){return CompletableFuture.failedFuture(error);}});doAnswer(c->{CompletableFuture<?> result=c.getArgument(1);((Consumer<Object>)c.getArgument(2)).accept(result.join());return null;}).when(runtime).reply(any(),any(),any());}
    final class RefundRig {
        final CommandRig r=new CommandRig();
        final MarketRefundService refunds=mock(MarketRefundService.class);
        final TreasuryRefundPort port=mock(TreasuryRefundPort.class);
        final Purchase purchase=r.purchase(UUID.randomUUID(),7);
        final MarketRefundService.Refund refund=new MarketRefundService.Refund(UUID.randomUUID(),purchase.id(),"starworld:refund:test",MarketRefundService.Status.IN_FLIGHT,1,0,"",4);
        final MarketRefundService.Dispatch dispatch=new MarketRefundService.Dispatch(refund,purchase,"refund-lease");
        final MarketController controller;
        RefundRig()throws Exception{
            when(r.market.purchase(purchase.id())).thenReturn(Optional.of(purchase));when(refunds.refund(refund.id())).thenReturn(Optional.of(refund));
            when(r.market.definitions()).thenReturn(MarketDefinitions.defaults());when(r.market.leaseDue(anyInt(),anyLong())).thenReturn(List.of());when(r.market.leasePaid(anyInt(),anyLong())).thenReturn(List.of());when(r.market.expiring(anyLong(),anyInt())).thenReturn(List.of());
            when(refunds.leaseDue(anyInt(),anyLong())).thenReturn(List.of(dispatch));when(refunds.leaseReturns(anyInt(),anyLong())).thenReturn(List.of());
            controller=new MarketController(r.plugin,r.runtime,r.core,r.extensions,r.logistics,r.market,r.treasury,refunds,port);
        }
        TreasuryRefundPort.Receipt proof(){return new TreasuryRefundPort.Receipt(refund.transactionId(),purchase.transactionId(),purchase.buyerNation(),Map.of(purchase.sellerNation(),purchase.total()),Instant.EPOCH);}
    }
    @Test void refundCommandsRecheckFounderUsePermissionAndOnlineStateAfterStorageLookup()throws Exception{
        for(String op:List.of("refund","refund-accept","refund-cancel","refund-retry"))for(String revoked:List.of("founder","permission","online")){
            RefundRig f=new RefundRig();
            if(revoked.equals("founder"))when(f.r.core.canGovern(f.r.actor,f.r.buyer.cityId())).thenReturn(true,false);
            if(revoked.equals("permission"))when(f.r.player.hasPermission("starworld.use")).thenReturn(false);
            if(revoked.equals("online"))when(f.r.player.isOnline()).thenReturn(false);
            f.controller.execute(f.r.player,new String[]{"market",op,(op.equals("refund")?f.purchase.id():f.refund.id()).toString()});
            verify(f.refunds,never()).request(any(),any(),any());verify(f.refunds,never()).accept(any(),any(),any());verify(f.refunds,never()).cancel(any(),any(),any());verify(f.refunds,never()).retry(any(),any(),any());verifyNoInteractions(f.port);
            verify(f.r.player).sendMessage(contains(revoked.equals("founder")?"国家金币付款需要":"玩家已离线或市场使用权限已失效"));
        }
    }
    @Test void refundOwnershipChangeQueriesOnlyOriginalReceiptAndStillFinishesMatchingReconciliation()throws Exception{
        RefundRig f=new RefundRig();CoreCity changed=new CoreCity(f.r.seller.cityId(),UUID.randomUUID(),"changed","changed",1,"test");when(f.r.core.city(changed.cityId())).thenReturn(Optional.of(changed));
        when(f.port.receipt(f.refund.transactionId())).thenReturn(Optional.empty());f.controller.tickFuture().join();
        verify(f.port,never()).refund(any());verify(f.refunds).finish(eq(f.dispatch),argThat(d->d.status()==TreasuryTransferPort.Status.RETRY&&d.receipt()==null),anyLong());
        when(f.port.receipt(f.refund.transactionId())).thenReturn(Optional.of(f.proof()));f.controller.tickFuture().join();
        verify(f.port,never()).refund(any());verify(f.refunds).finish(eq(f.dispatch),argThat(d->d.status()==TreasuryTransferPort.Status.CONFIRMED&&d.receipt().matches(f.dispatch.request())),anyLong());
        verifyNoInteractions(f.r.treasury);
    }
    @Test void unchangedRefundOwnersDispatchExactlyTheApprovedOriginalPayment()throws Exception{
        RefundRig f=new RefundRig();var delivery=new TreasuryRefundPort.Delivery(f.refund.transactionId(),TreasuryTransferPort.Status.CONFIRMED,f.proof(),"confirmed");when(f.port.refund(f.dispatch.request())).thenReturn(delivery);
        f.controller.tickFuture().join();verify(f.port).refund(f.dispatch.request());verify(f.refunds).finish(eq(f.dispatch),same(delivery),anyLong());verifyNoInteractions(f.r.treasury);
    }
    @Test void unavailableRefundReceiptDoesNotLeaveTheBatchUnfinishedOrIssueNewReversal()throws Exception{
        RefundRig f=new RefundRig();when(f.r.core.city(f.r.seller.cityId())).thenReturn(Optional.empty());when(f.port.receipt(f.refund.transactionId())).thenThrow(new IllegalStateException("core API unavailable"));
        f.controller.tickFuture().get(1,TimeUnit.SECONDS);verify(f.port,never()).refund(any());
        verify(f.refunds).finish(eq(f.dispatch),argThat(d->d.status()==TreasuryTransferPort.Status.RETRY&&d.receipt()==null),anyLong());
    }
    @Test void closedOrRetiredGlobalRefundDispatchCannotIssueAReversal()throws Exception{
        for(boolean retired:List.of(false,true)){
            RefundRig f=new RefundRig();CompletableFuture<Object> scheduled=new CompletableFuture<>();java.util.concurrent.atomic.AtomicReference<Callable<?>> task=new java.util.concurrent.atomic.AtomicReference<>();
            when(f.r.runtime.globalFuture(any())).thenAnswer(call->{task.set(call.getArgument(0));return scheduled;});
            var result=f.controller.tickFuture();assertFalse(result.isDone());
            if(retired){scheduled.completeExceptionally(new IllegalStateException("scheduler retired"));assertThrows(ExecutionException.class,()->result.get(1,TimeUnit.SECONDS));}
            else{f.controller.close();scheduled.complete(task.get().call());result.get(1,TimeUnit.SECONDS);f.controller.tickFuture().get(1,TimeUnit.SECONDS);}
            verifyNoInteractions(f.port,f.r.treasury);verify(f.refunds,never()).finish(any(),any(),anyLong());
        }
    }
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
    @Test void asyncPurchaseCallbackRechecksCurrentCityBeforePreparingAnyPayment()throws Exception{JavaPlugin plugin=mock(JavaPlugin.class);WorldRuntime runtime=mock(WorldRuntime.class);CoreBridge core=mock(CoreBridge.class);MarketService market=mock(MarketService.class);LogisticsService logistics=mock(LogisticsService.class);Player p=mock(Player.class);CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"市","国",1,"snapshot");UUID actor=UUID.randomUUID(),listing=UUID.randomUUID(),route=UUID.randomUUID(),seller=UUID.randomUUID();when(p.getUniqueId()).thenReturn(actor);when(p.isOnline()).thenReturn(true);when(p.hasPermission("starworld.use")).thenReturn(true);when(runtime.playerCity(p)).thenReturn(Optional.of(city),Optional.empty());when(runtime.manage(p,city.cityId())).thenReturn(true);when(core.canGovern(actor,city.cityId())).thenReturn(true);when(market.listing(listing)).thenReturn(Optional.of(new Listing(listing,seller,UUID.randomUUID(),"minecraft:bread",20,new BigDecimal("1.00"),ListingStatus.AVAILABLE,1,10)));when(logistics.route(route)).thenReturn(Optional.of(new Route(route,seller,city.cityId(),List.of(),Mode.LAND,true,32,1,256)));immediate(runtime);MarketController controller=new MarketController(plugin,runtime,core,mock(CoreExtensionPort.class),logistics,market,mock(TreasuryTransferPort.class));controller.execute(p,new String[]{"market","buy",listing.toString(),route.toString()});verify(p).sendMessage(contains("请先加入"));verify(market,never()).buy(any(),any(),any(),any(),any(),any());}
    @Test void asyncPurchaseCallbackRechecksRevokedFounderBeforePreparingAnyPayment()throws Exception{JavaPlugin plugin=mock(JavaPlugin.class);WorldRuntime runtime=mock(WorldRuntime.class);CoreBridge core=mock(CoreBridge.class);MarketService market=mock(MarketService.class);LogisticsService logistics=mock(LogisticsService.class);Player p=mock(Player.class);CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"市","国",1,"snapshot");UUID actor=UUID.randomUUID(),listing=UUID.randomUUID(),route=UUID.randomUUID(),seller=UUID.randomUUID();when(p.getUniqueId()).thenReturn(actor);when(p.isOnline()).thenReturn(true);when(p.hasPermission("starworld.use")).thenReturn(true);when(runtime.playerCity(p)).thenReturn(Optional.of(city));when(runtime.manage(p,city.cityId())).thenReturn(true);when(core.canGovern(actor,city.cityId())).thenReturn(true,false);when(market.listing(listing)).thenReturn(Optional.of(new Listing(listing,seller,UUID.randomUUID(),"minecraft:bread",20,new BigDecimal("1.00"),ListingStatus.AVAILABLE,1,10)));when(logistics.route(route)).thenReturn(Optional.of(new Route(route,seller,city.cityId(),List.of(),Mode.LAND,true,32,1,256)));immediate(runtime);MarketController controller=new MarketController(plugin,runtime,core,mock(CoreExtensionPort.class),logistics,market,mock(TreasuryTransferPort.class));controller.execute(p,new String[]{"market","buy",listing.toString(),route.toString()});verify(p).sendMessage(contains("国家金币付款需要"));verify(market,never()).buy(any(),any(),any(),any(),any(),any());}
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

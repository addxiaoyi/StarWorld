package dev.starworld.integration;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.*;
import dev.starcore.starcore.module.treasury.NationSettlementService;
import dev.starcore.starcore.module.treasury.NationSettlementService.*;
import dev.starcore.starcore.module.treasury.NationTransferService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.bukkit.Server;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StarCoreTreasurySettlementsTest {
    final UUID buyer=UUID.randomUUID(),seller=UUID.randomUUID(),transit=UUID.randomUUID();
    final TreasuryTransferPort.Request request=new TreasuryTransferPort.Request("goods-and-toll",buyer,Map.of(seller,new BigDecimal("20.60"),transit,new BigDecimal("0.70")),"physical goods");
    Server server;NationService nations;NationSettlementService settlements;NationTransferService legacy;StarCoreApi api;JavaPlugin owner;StarCoreTreasuryTransfers port;
    @BeforeEach void setup(){
        owner=mock(JavaPlugin.class);server=mock(Server.class);PluginManager plugins=mock(PluginManager.class);ServicesManager services=mock(ServicesManager.class);api=mock(StarCoreApi.class);Plugin core=mock(Plugin.class);nations=mock(NationService.class);settlements=mock(NationSettlementService.class);legacy=mock(NationTransferService.class);
        when(owner.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);when(server.getPluginManager()).thenReturn(plugins);when(server.getServicesManager()).thenReturn(services);when(plugins.getPlugin("StarCore")).thenReturn(core);when(core.isEnabled()).thenReturn(true);when(services.load(StarCoreApi.class)).thenReturn(api);when(api.service(NationSettlementService.class)).thenReturn(Optional.of(settlements));when(api.service(NationTransferService.class)).thenReturn(Optional.of(legacy));when(api.service(NationService.class)).thenReturn(Optional.of(nations));when(nations.nationById(any())).thenReturn(Optional.of(mock(Nation.class)));port=new StarCoreTreasuryTransfers(owner);
    }
    Map<NationId,BigDecimal> credits(){return Map.of(NationId.of(seller),new BigDecimal("20.60"),NationId.of(transit),new BigDecimal("0.70"));}
    SettlementReceipt proof(){return new SettlementReceipt(request.transactionId(),NationId.of(buyer),credits(),request.description(),Instant.EPOCH);}
    @Test void oneAtomicCallAllocatesGoodsAndTollAndRequiresAllExactBeneficiaries(){
        when(settlements.settlementReceipt(request.transactionId())).thenReturn(Optional.empty(),Optional.of(proof()));
        when(settlements.settle(any())).thenAnswer(call->{SettlementRequest actual=call.getArgument(0);assertEquals(credits(),actual.credits());assertEquals(buyer,actual.payerId().value());assertEquals(request.description(),actual.description());return new SettlementResult(SettlementStatus.APPLIED,actual.transactionId(),Map.of());});
        assertEquals(TreasuryTransferPort.Status.CONFIRMED,port.transfer(request).status());assertEquals(TreasuryTransferPort.Status.CONFIRMED,port.transfer(request).status());assertTrue(port.receipt(request).orElseThrow().matches(request));verify(settlements,times(1)).settle(any());verifyNoInteractions(legacy);
    }
    @Test void sameTotalWithOtherAllocationOrDescriptionIsAConflict(){
        when(settlements.settlementReceipt(request.transactionId())).thenReturn(Optional.of(new SettlementReceipt(request.transactionId(),NationId.of(buyer),Map.of(NationId.of(seller),new BigDecimal("20.00"),NationId.of(transit),new BigDecimal("1.30")),request.description(),Instant.EPOCH)));
        assertEquals(TreasuryTransferPort.Status.CONFLICT,port.transfer(request).status());
        when(settlements.settlementReceipt(request.transactionId())).thenReturn(Optional.of(new SettlementReceipt(request.transactionId(),NationId.of(buyer),credits(),"another purpose",Instant.EPOCH)));
        assertEquals(TreasuryTransferPort.Status.CONFLICT,port.transfer(request).status());verify(settlements,never()).settle(any());verifyNoInteractions(legacy);
    }
    @Test void noReceiptOrMissingServiceCannotProvePaymentOrNonPayment(){
        when(settlements.settle(any())).thenReturn(new SettlementResult(SettlementStatus.APPLIED,request.transactionId(),Map.of()));assertEquals(TreasuryTransferPort.Status.RETRY,port.transfer(request).status());
        when(api.service(NationSettlementService.class)).thenReturn(Optional.empty());assertEquals(TreasuryTransferPort.Status.RETRY,port.transfer(request).status());assertThrows(IllegalStateException.class,()->port.receipt(request));verifyNoInteractions(legacy);
    }
    @Test void missingBeneficiaryInsufficiencyAndWrongThreadNeverSplitACharge(){
        when(nations.nationById(NationId.of(transit))).thenReturn(Optional.empty());assertEquals(TreasuryTransferPort.Status.REJECTED,port.transfer(request).status());verify(settlements,never()).settle(any());
        when(nations.nationById(NationId.of(transit))).thenReturn(Optional.of(mock(Nation.class)));when(settlements.settle(any())).thenReturn(new SettlementResult(SettlementStatus.INSUFFICIENT_FUNDS,request.transactionId(),Map.of()));assertEquals(TreasuryTransferPort.Status.REJECTED,port.transfer(request).status());
        clearInvocations(settlements);when(server.isPrimaryThread()).thenReturn(false);assertEquals(TreasuryTransferPort.Status.RETRY,port.transfer(request).status());assertThrows(IllegalStateException.class,()->port.receipt(request));verifyNoInteractions(settlements,legacy);
    }
    @Test void fullRefundChecksOriginalAllocationAndRefusesAPartialReverseReceipt(){
        when(settlements.settlementReceipt(request.transactionId())).thenReturn(Optional.of(proof()));var refund=new TreasuryRefundPort.Request("return-all",request);var refunds=new StarCoreTreasuryRefunds(owner,port);
        when(settlements.settlementRefundReceipt(refund.transactionId())).thenReturn(Optional.empty(),Optional.of(new SettlementRefundReceipt(refund.transactionId(),request.transactionId(),NationId.of(buyer),credits(),Instant.EPOCH)));
        when(settlements.refundSettlement(any())).thenReturn(new SettlementResult(SettlementStatus.APPLIED,refund.transactionId(),Map.of()));assertEquals(TreasuryTransferPort.Status.CONFIRMED,refunds.refund(refund).status());
        when(settlements.settlementRefundReceipt(refund.transactionId())).thenReturn(Optional.of(new SettlementRefundReceipt(refund.transactionId(),request.transactionId(),NationId.of(buyer),Map.of(NationId.of(seller),new BigDecimal("21.30")),Instant.EPOCH)));assertEquals(TreasuryTransferPort.Status.CONFLICT,refunds.refund(refund).status());verify(settlements,times(1)).refundSettlement(any());verifyNoInteractions(legacy);
    }
    @Test void allocationsAreBoundedImmutableAndExactToCents(){
        assertEquals(new BigDecimal("21.30"),request.amount());assertThrows(UnsupportedOperationException.class,()->request.credits().clear());assertThrows(IllegalStateException.class,request::receiver);
        for(Map<UUID,BigDecimal> invalid:List.of(Map.<UUID,BigDecimal>of(),Map.of(buyer,BigDecimal.ONE),Map.of(seller,new BigDecimal("0.001")),Map.of(seller,new BigDecimal("1E+1000000"))))assertThrows(IllegalArgumentException.class,()->new TreasuryTransferPort.Request("bad",buyer,invalid,""));
        Map<UUID,BigDecimal> tooMany=new HashMap<>();for(int i=0;i<17;i++)tooMany.put(UUID.randomUUID(),BigDecimal.ONE);assertThrows(IllegalArgumentException.class,()->new TreasuryTransferPort.Request("bad",buyer,tooMany,""));
    }
}

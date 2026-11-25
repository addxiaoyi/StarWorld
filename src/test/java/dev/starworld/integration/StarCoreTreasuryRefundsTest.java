package dev.starworld.integration;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.*;
import dev.starcore.starcore.module.treasury.NationSettlementService;
import dev.starcore.starcore.module.treasury.NationSettlementService.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import org.bukkit.Server;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StarCoreTreasuryRefundsTest {
    Server server;Plugin core;NationService nations;NationSettlementService settlements;TreasuryTransferPort transfers;StarCoreTreasuryRefunds port;
    final TreasuryTransferPort.Request original=new TreasuryTransferPort.Request("original",UUID.randomUUID(),UUID.randomUUID(),new BigDecimal("12.50"),"goods");
    final TreasuryRefundPort.Request request=new TreasuryRefundPort.Request("refund",original);
    @BeforeEach void setup(){
        JavaPlugin owner=mock(JavaPlugin.class);server=mock(Server.class);PluginManager plugins=mock(PluginManager.class);ServicesManager services=mock(ServicesManager.class);StarCoreApi api=mock(StarCoreApi.class);core=mock(Plugin.class);nations=mock(NationService.class);settlements=mock(NationSettlementService.class);transfers=mock(TreasuryTransferPort.class);
        when(owner.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);when(server.getPluginManager()).thenReturn(plugins);when(server.getServicesManager()).thenReturn(services);when(plugins.getPlugin("StarCore")).thenReturn(core);when(core.isEnabled()).thenReturn(true);when(services.load(StarCoreApi.class)).thenReturn(api);when(api.service(NationSettlementService.class)).thenReturn(Optional.of(settlements));when(api.service(NationService.class)).thenReturn(Optional.of(nations));when(nations.nationById(any())).thenReturn(Optional.of(mock(Nation.class)));
        when(transfers.receipt(original.transactionId())).thenReturn(Optional.of(new TreasuryTransferPort.Receipt(original.transactionId(),original.payer(),original.receiver(),original.amount(),"OTHER",Instant.EPOCH)));
        port=new StarCoreTreasuryRefunds(owner,transfers);
    }
    SettlementRefundReceipt proof(){return new SettlementRefundReceipt(request.transactionId(),original.transactionId(),NationId.of(original.payer()),Map.of(NationId.of(original.receiver()),original.amount()),Instant.EPOCH);}
    @Test void exactOriginalAndReverseReceiptsAreRequiredAndRepeatedRefundNeverCallsMutation(){
        when(settlements.settlementRefundReceipt(request.transactionId())).thenReturn(Optional.empty(),Optional.of(proof()));
        when(settlements.refundSettlement(any())).thenAnswer(call->{SettlementRefundRequest actual=call.getArgument(0);assertEquals(request.transactionId(),actual.transactionId());assertEquals(original.transactionId(),actual.originalTransactionId());return new SettlementResult(SettlementStatus.APPLIED,actual.transactionId(),Map.of());});
        assertEquals(TreasuryTransferPort.Status.CONFIRMED,port.refund(request).status());
        assertEquals(TreasuryTransferPort.Status.CONFIRMED,port.refund(request).status());verify(settlements,times(1)).refundSettlement(any());
        assertTrue(port.receipt(request.transactionId()).orElseThrow().matches(request));
    }
    @Test void missingOrConflictingOriginalPreventsRefundAndAnotherRefundIdentityIsQuarantined(){
        when(transfers.receipt(original.transactionId())).thenReturn(Optional.empty());assertEquals(TreasuryTransferPort.Status.CONFLICT,port.refund(request).status());
        when(transfers.receipt(original.transactionId())).thenReturn(Optional.of(new TreasuryTransferPort.Receipt(original.transactionId(),original.payer(),original.receiver(),new BigDecimal("99.00"),"OTHER",Instant.EPOCH)));
        assertEquals(TreasuryTransferPort.Status.CONFLICT,port.refund(request).status());verify(settlements,never()).refundSettlement(any());
        var wrong=new SettlementRefundReceipt("another",original.transactionId(),NationId.of(original.payer()),proof().credits(),Instant.EPOCH);
        when(settlements.settlementRefundReceipt(request.transactionId())).thenReturn(Optional.of(wrong));assertEquals(TreasuryTransferPort.Status.CONFLICT,port.refund(request).status());
    }
    @Test void coreClaimsOfSuccessWithoutReceiptAndInsufficientFundsKeepSameRefundPending(){
        for(SettlementStatus state:List.of(SettlementStatus.APPLIED,SettlementStatus.INSUFFICIENT_FUNDS,SettlementStatus.REJECTED)){
            when(settlements.refundSettlement(any())).thenReturn(new SettlementResult(state,request.transactionId(),Map.of()));
            assertEquals(TreasuryTransferPort.Status.RETRY,port.refund(request).status());
        }
        when(settlements.refundSettlement(any())).thenReturn(new SettlementResult(SettlementStatus.APPLIED,"wrong",Map.of()));assertEquals(TreasuryTransferPort.Status.CONFLICT,port.refund(request).status());
    }
    @Test void absentNationUnavailableCoreAndWorkerThreadCannotInitiateRefunds(){
        when(nations.nationById(any())).thenReturn(Optional.empty());assertEquals(TreasuryTransferPort.Status.RETRY,port.refund(request).status());verify(settlements,never()).refundSettlement(any());
        clearInvocations(settlements,transfers);when(server.isPrimaryThread()).thenReturn(false);assertEquals(TreasuryTransferPort.Status.RETRY,port.refund(request).status());assertThrows(IllegalStateException.class,()->port.receipt("refund"));verifyNoInteractions(settlements,transfers);
        when(server.isPrimaryThread()).thenReturn(true);when(core.isEnabled()).thenReturn(false);assertEquals(TreasuryTransferPort.Status.RETRY,port.refund(request).status());assertTrue(port.status().contains("等待"));
    }
}

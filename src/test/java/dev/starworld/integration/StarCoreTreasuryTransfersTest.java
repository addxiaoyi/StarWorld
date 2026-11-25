package dev.starworld.integration;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.*;
import dev.starcore.starcore.module.treasury.NationTransferService;
import dev.starcore.starcore.module.treasury.NationTransferService.*;
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

class StarCoreTreasuryTransfersTest {
    Server server;Plugin core;NationService nations;NationTransferService transfers;StarCoreTreasuryTransfers port;
    TreasuryTransferPort.Request request=new TreasuryTransferPort.Request("starworld:market:test:stable",UUID.randomUUID(),UUID.randomUUID(),new BigDecimal("125.50"),"实体批次");
    @BeforeEach void setup(){JavaPlugin owner=mock(JavaPlugin.class);server=mock(Server.class);PluginManager plugins=mock(PluginManager.class);ServicesManager services=mock(ServicesManager.class);StarCoreApi api=mock(StarCoreApi.class);core=mock(Plugin.class);nations=mock(NationService.class);transfers=mock(NationTransferService.class);when(owner.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);when(server.getPluginManager()).thenReturn(plugins);when(server.getServicesManager()).thenReturn(services);when(plugins.getPlugin("StarCore")).thenReturn(core);when(core.isEnabled()).thenReturn(true);when(services.load(StarCoreApi.class)).thenReturn(api);when(api.service(NationTransferService.class)).thenReturn(Optional.of(transfers));when(api.service(NationService.class)).thenReturn(Optional.of(nations));when(nations.nationById(any())).thenReturn(Optional.of(mock(Nation.class)));when(transfers.transferReceipt(anyString())).thenReturn(Optional.empty());port=new StarCoreTreasuryTransfers(owner);}
    TransferReceipt proof(){return new TransferReceipt(request.transactionId(),NationId.of(request.payer()),NationId.of(request.receiver()),request.amount(),TransferCategory.OTHER,request.description(),Instant.parse("2026-11-20T00:00:00Z"));}
    @Test void existingDurableProofAcknowledgesWithoutTransferringAgain(){when(transfers.transferReceipt(request.transactionId())).thenReturn(Optional.of(proof()));var delivery=port.transfer(request);assertEquals(TreasuryTransferPort.Status.CONFIRMED,delivery.status());assertTrue(delivery.receipt().matches(request));verify(transfers,never()).transfer(any());}
    @Test void exactPublicTransferCarriesBothNationsCentsAndCategoryAndRequiresReceipt(){when(transfers.transferReceipt(request.transactionId())).thenReturn(Optional.empty(),Optional.of(proof()));when(transfers.transfer(any())).thenAnswer(c->{TransferRequest actual=c.getArgument(0);assertEquals(request.transactionId(),actual.transactionId());assertEquals(request.payer(),actual.payerId().value());assertEquals(request.receiver(),actual.receiverId().value());assertEquals(new BigDecimal("125.50"),actual.amount());assertEquals(TransferCategory.OTHER,actual.category());return new TransferResult(TransferStatus.APPLIED,actual.transactionId(),BigDecimal.ZERO,actual.amount());});assertEquals(TreasuryTransferPort.Status.CONFIRMED,port.transfer(request).status());verify(transfers,times(1)).transfer(any());}
    @Test void appliedWithoutProofIsRetriedAndWrongReceiptIsQuarantined(){when(transfers.transfer(any())).thenReturn(new TransferResult(TransferStatus.APPLIED,request.transactionId(),BigDecimal.ZERO,request.amount()));assertEquals(TreasuryTransferPort.Status.RETRY,port.transfer(request).status());TransferReceipt collision=new TransferReceipt(request.transactionId(),NationId.of(request.receiver()),NationId.of(request.payer()),request.amount(),TransferCategory.OTHER,"wrong endpoints",Instant.now());when(transfers.transferReceipt(request.transactionId())).thenReturn(Optional.of(collision));assertEquals(TreasuryTransferPort.Status.CONFLICT,port.transfer(request).status());verify(transfers,times(1)).transfer(any());}
    @Test void insufficientFundsOrDeletedNationDoesNotFabricateCoins(){when(transfers.transfer(any())).thenReturn(new TransferResult(TransferStatus.INSUFFICIENT_FUNDS,request.transactionId(),BigDecimal.ZERO,BigDecimal.ZERO));assertEquals(TreasuryTransferPort.Status.REJECTED,port.transfer(request).status());when(nations.nationById(NationId.of(request.receiver()))).thenReturn(Optional.empty());assertEquals(TreasuryTransferPort.Status.REJECTED,port.transfer(request).status());verify(transfers,times(1)).transfer(any());}
    @Test void workerThreadUnavailableCoreAndUnqueryableReceiptFailClosed(){when(server.isPrimaryThread()).thenReturn(false);assertEquals(TreasuryTransferPort.Status.RETRY,port.transfer(request).status());assertThrows(IllegalStateException.class,()->port.receipt(request.transactionId()));verifyNoInteractions(transfers);when(server.isPrimaryThread()).thenReturn(true);when(core.isEnabled()).thenReturn(false);assertTrue(port.status().contains("停用"));assertEquals(TreasuryTransferPort.Status.RETRY,port.transfer(request).status());}
    @Test void coreResultWithAnotherTransactionIdCannotAcknowledge(){when(transfers.transfer(any())).thenReturn(new TransferResult(TransferStatus.APPLIED,"other",BigDecimal.ZERO,request.amount()));assertEquals(TreasuryTransferPort.Status.CONFLICT,port.transfer(request).status());}
}

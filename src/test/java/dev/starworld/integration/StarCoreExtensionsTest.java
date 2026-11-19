package dev.starworld.integration;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.*;
import dev.starcore.starcore.module.resource.*;
import dev.starcore.starcore.module.resource.NationResourceTransactionService.*;
import dev.starcore.starcore.module.weather.WeatherControlService;
import dev.starcore.starcore.module.weather.model.WeatherType;
import dev.starcore.starcore.foundation.epoch.EpochService;
import dev.starcore.starcore.module.war.WarService;
import dev.starcore.starcore.module.diplomacy.DiplomacyRelationService;
import dev.starworld.integration.CoreExtensionPort.*;
import java.time.Instant;
import java.util.*;
import org.bukkit.Server;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class StarCoreExtensionsTest {
    private final UUID nationUUID=UUID.randomUUID();
    private final NationId id=NationId.of(nationUUID);
    private Server server; private Plugin core; private NationService nations; private Nation nation;
    private NationResourceTransactionService transactions;private ResourceService resources;
    private final Map<Class<?>,Object> services=new HashMap<>();
    private StarCoreExtensions adapter;
    @BeforeEach void setup(){
        JavaPlugin owner=mock(JavaPlugin.class);server=mock(Server.class);PluginManager plugins=mock(PluginManager.class);ServicesManager manager=mock(ServicesManager.class);core=mock(Plugin.class);StarCoreApi api=mock(StarCoreApi.class);
        when(owner.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);when(server.getPluginManager()).thenReturn(plugins);when(server.getServicesManager()).thenReturn(manager);when(plugins.getPlugin("StarCore")).thenReturn(core);when(core.isEnabled()).thenReturn(true);when(manager.load(StarCoreApi.class)).thenReturn(api);
        when(api.service(any())).thenAnswer(call->Optional.ofNullable(services.get(call.getArgument(0))));
        nations=mock(NationService.class);nation=mock(Nation.class);transactions=mock(NationResourceTransactionService.class);resources=mock(ResourceService.class);
        services.put(NationService.class,nations);services.put(NationResourceTransactionService.class,transactions);services.put(ResourceService.class,resources);
        when(nations.nationById(id)).thenReturn(Optional.of(nation));when(nation.id()).thenReturn(id);when(nation.name()).thenReturn("真实国家快照");when(resources.availableResourceTypes()).thenReturn(List.of("strategic","agricultural"));when(resources.stockpile(id)).thenReturn(Map.of("strategic",3L));
        when(transactions.transactionReceipt(anyString())).thenReturn(Optional.empty());adapter=new StarCoreExtensions(owner);
    }
    private ResourceRequest request(){return new ResourceRequest("starworld:test:stable",nationUUID,Map.of("strategic",2L),"military output");}
    private TransactionReceipt receiptOf(ResourceRequest r){return new TransactionReceipt(r.transactionId(),id,r.resources(),Operation.GRANT,Category.OTHER,r.description(),Instant.parse("2026-11-19T00:00:00Z"));}
    @Test void matchingExistingReceiptAcknowledgesWithoutSecondGrant(){ResourceRequest r=request();when(transactions.transactionReceipt(r.transactionId())).thenReturn(Optional.of(receiptOf(r)));Delivery result=adapter.deliver(r);assertEquals(DeliveryStatus.CONFIRMED,result.status());assertTrue(result.receipt().matches(r));verify(transactions,never()).grant(any());}
    @Test void grantRequiresVerifiedReceiptAfterCoreReportsApplied(){ResourceRequest r=request();when(transactions.transactionReceipt(r.transactionId())).thenReturn(Optional.empty(),Optional.of(receiptOf(r)));when(transactions.grant(any())).thenAnswer(call->{TransactionRequest sent=call.getArgument(0);assertEquals(r.transactionId(),sent.transactionId());assertEquals(id,sent.nationId());assertEquals(r.resources(),sent.resources());assertEquals(Category.OTHER,sent.category());return new TransactionResult(Status.APPLIED,sent.transactionId(),Map.of());});assertEquals(DeliveryStatus.CONFIRMED,adapter.deliver(r).status());verify(transactions,times(1)).grant(any());}
    @Test void appliedWithoutReceiptRemainsRetryableAndNeverBlindlyAcknowledges(){ResourceRequest r=request();when(transactions.grant(any())).thenReturn(new TransactionResult(Status.APPLIED,r.transactionId(),Map.of()));Delivery result=adapter.deliver(r);assertEquals(DeliveryStatus.RETRY,result.status());assertNull(result.receipt());}
    @Test void collisionReceiptIsQuarantinedAndDoesNotGrantAgain(){ResourceRequest r=request();TransactionReceipt wrong=new TransactionReceipt(r.transactionId(),id,Map.of("strategic",9L),Operation.GRANT,Category.OTHER,"collision",Instant.now());when(transactions.transactionReceipt(r.transactionId())).thenReturn(Optional.of(wrong));assertEquals(DeliveryStatus.CONFLICT,adapter.deliver(r).status());verify(transactions,never()).grant(any());}
    @Test void offThreadOrUnavailableCoreCannotInvokeGrant(){when(server.isPrimaryThread()).thenReturn(false);assertEquals(DeliveryStatus.RETRY,adapter.deliver(request()).status());assertThrows(IllegalStateException.class,()->adapter.receipt(request().transactionId()));assertTrue(adapter.snapshot(nationUUID,Set.of()).isEmpty());verifyNoInteractions(transactions);}
    @Test void deletedNationOrUnknownTypeDoesNotCreateGhostResourceStockpile(){when(nations.nationById(id)).thenReturn(Optional.empty());assertEquals(DeliveryStatus.REJECTED,adapter.deliver(request()).status());verify(transactions,never()).grant(any());when(nations.nationById(id)).thenReturn(Optional.of(nation));assertEquals(DeliveryStatus.REJECTED,adapter.deliver(new ResourceRequest("unknown",nationUUID,Map.of("imaginary",1L),"")).status());verify(transactions,never()).grant(any());}
    @Test void snapshotCopiesCoreWeatherWarEpochAndFactoriesWithoutLeakingMutableCollections(){
        Map<String,Long> stock=new HashMap<>(Map.of("strategic",9L));when(resources.stockpile(id)).thenReturn(stock);
        WeatherControlService weather=mock(WeatherControlService.class);services.put(WeatherControlService.class,weather);when(weather.getNationWeather(id)).thenReturn(WeatherType.RAIN);when(weather.getResourceModifiers(WeatherType.RAIN)).thenReturn(Map.of("agricultural",1.2));
        EpochService epoch=mock(EpochService.class);services.put(EpochService.class,epoch);when(epoch.snapshot()).thenReturn(new EpochService.EpochSnapshot(true,3,Instant.EPOCH,Instant.EPOCH.plusSeconds(100)));
        UUID other=UUID.randomUUID();NationId otherId=NationId.of(other);WarService wars=mock(WarService.class);services.put(WarService.class,wars);when(wars.activeWarsOf(id)).thenReturn(List.of(new WarService.WarSnapshot(UUID.randomUUID(),id,otherId,false)));
        DiplomacyRelationService diplomacy=mock(DiplomacyRelationService.class);services.put(DiplomacyRelationService.class,diplomacy);when(diplomacy.relationBetween(id,otherId)).thenReturn(DiplomacyRelationService.Relation.HOSTILE);
        ProcessingService processing=mock(ProcessingService.class);services.put(ProcessingService.class,processing);when(processing.getAllRecipes()).thenReturn(List.of(new ProcessingService.Recipe("smelt",Map.of("mineral",2L),Map.of("industrial",1L),20)));when(processing.getFactories(id)).thenReturn(List.of(new Object()));
        NationSnapshot snapshot=adapter.snapshot(nationUUID,Set.of(other)).orElseThrow();stock.put("strategic",999L);
        assertEquals(9,snapshot.resourceStock().get("strategic"));assertEquals("rain",snapshot.weather());assertEquals(1.2,snapshot.weatherResourceMultipliers().get("agricultural"));assertEquals(3,snapshot.epoch().number());assertEquals(Set.of(other),snapshot.warOpponents());assertEquals("HOSTILE",snapshot.diplomacy().get(other));assertEquals("smelt",snapshot.processingRecipes().getFirst().id());assertEquals(1,snapshot.factoryCount());assertThrows(UnsupportedOperationException.class,()->snapshot.resourceStock().put("strategic",1L));assertTrue(snapshot.capabilities().get("technology").startsWith("unavailable"));
    }
    @Test void missingOptionalServicesAreExplicitAndDeletedNationReturnsEmpty(){NationSnapshot snapshot=adapter.snapshot(nationUUID,Set.of()).orElseThrow();assertEquals("unknown",snapshot.weather());assertEquals("unavailable: NullPointerException",snapshot.capabilities().get("weather"));assertNull(snapshot.epoch());assertTrue(snapshot.capabilities().get("prosperity-submit").contains("无幂等回执"));when(nations.nationById(id)).thenReturn(Optional.empty());assertTrue(adapter.snapshot(nationUUID,Set.of()).isEmpty());}
}

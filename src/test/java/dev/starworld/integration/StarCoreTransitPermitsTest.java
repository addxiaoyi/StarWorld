package dev.starworld.integration;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.module.diplomacy.TransitPermitService;
import dev.starcore.starcore.module.diplomacy.TransitPermitService.*;
import dev.starcore.starcore.module.nation.model.NationId;
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

class StarCoreTransitPermitsTest {
    final UUID grantor=UUID.randomUUID(),origin=UUID.randomUUID(),target=UUID.randomUUID(),actor=UUID.randomUUID(),request=UUID.randomUUID(),world=UUID.randomUUID(),city=UUID.randomUUID();
    final Terms terms=new Terms("starworld:route","a".repeat(64),NationId.of(grantor),NationId.of(origin),NationId.of(target),Set.of(new Parcel(world,"world",city,1,2)),new BigDecimal("0.10"),1_000_000);
    final Permit permit=new Permit(terms,1,false,actor,1);
    final Receipt approval=new Receipt(request,Action.APPROVE,0,permit);
    TransitPermitService service;Server server;StarCoreApi api;StarCoreTransitPermits port;
    @BeforeEach void setup(){
        JavaPlugin owner=mock(JavaPlugin.class);server=mock(Server.class);PluginManager plugins=mock(PluginManager.class);ServicesManager services=mock(ServicesManager.class);api=mock(StarCoreApi.class);Plugin core=mock(Plugin.class);service=mock(TransitPermitService.class);
        when(owner.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);when(server.getPluginManager()).thenReturn(plugins);when(server.getServicesManager()).thenReturn(services);when(plugins.getPlugin("StarCore")).thenReturn(core);when(core.isEnabled()).thenReturn(true);when(services.load(StarCoreApi.class)).thenReturn(api);when(api.service(TransitPermitService.class)).thenReturn(Optional.of(service));
        when(service.permit(terms.routeKey(),NationId.of(grantor))).thenReturn(Optional.of(permit));port=new StarCoreTransitPermits(owner);
    }
    @Test void immutableCaptureAndEvaluationRequireTheExactPermitReturnedByCore(){
        var captured=port.permit(terms.routeKey(),grantor).orElseThrow();assertEquals(grantor,captured.terms().grantingNation());assertEquals(new BigDecimal("0.10"),captured.terms().tollPerUnit());assertThrows(UnsupportedOperationException.class,()->captured.terms().parcels().clear());
        when(service.evaluate(terms,1)).thenReturn(new Decision(Optional.of(permit),true,"ok"));assertTrue(port.allowed(captured));
        for(Decision invalid:List.of(new Decision(Optional.of(permit),false,"war"),new Decision(Optional.empty(),true,"wrong"),new Decision(Optional.of(new Permit(terms,2,false,actor,2)),true,"wrong revision"),new Decision(Optional.of(new Permit(terms,1,true,actor,1)),true,"revoked"))){when(service.evaluate(terms,1)).thenReturn(invalid);assertFalse(port.allowed(captured));}
    }
    @Test void approvalRequiresMatchingDurableReceiptAndPassesAllTerms(){
        var expected=port.permit(terms.routeKey(),grantor).orElseThrow().terms();when(service.approve(any())).thenAnswer(call->{Approval actual=call.getArgument(0);assertEquals(terms,actual.terms());assertEquals(actor,actual.actor());assertEquals(0,actual.expectedRevision());return approval;});
        assertThrows(IllegalArgumentException.class,()->port.approve(request,actor,0,expected));when(service.receipt(request)).thenReturn(Optional.of(approval));assertEquals(expected,port.approve(request,actor,0,expected).permit().terms());
        when(service.receipt(request)).thenReturn(Optional.of(new Receipt(UUID.randomUUID(),Action.APPROVE,0,permit)));assertThrows(IllegalArgumentException.class,()->port.approve(request,actor,0,expected));
        doThrow(new IllegalArgumentException("current founder required")).when(service).approve(any());assertTrue(assertThrows(IllegalArgumentException.class,()->port.approve(request,actor,0,expected)).getMessage().contains("founder"));
    }
    @Test void revokeHasItsOwnRequestAndRevisionAndNeedsTheDurableReversal(){
        var result=new Receipt(request,Action.REVOKE,1,new Permit(terms,2,true,actor,2));when(service.revoke(any())).thenAnswer(call->{Revocation actual=call.getArgument(0);assertEquals(request,actual.requestId());assertEquals(actor,actual.actor());assertEquals(terms.routeKey(),actual.routeKey());assertEquals(1,actual.expectedRevision());assertEquals(grantor,actual.grantingNation().value());return result;});
        when(service.receipt(request)).thenReturn(Optional.of(result));assertTrue(port.revoke(request,actor,1,terms.routeKey(),grantor).permit().revoked());
        assertEquals(TransitPermitPort.Action.REVOKE,port.receipt(request).orElseThrow().action());when(service.receipt(request)).thenReturn(Optional.empty());assertThrows(IllegalArgumentException.class,()->port.revoke(request,actor,1,terms.routeKey(),grantor));
    }
    @Test void wrongThreadUnavailableCoreAndMalformedPermissionFailClosed(){
        when(server.isPrimaryThread()).thenReturn(false);assertThrows(IllegalArgumentException.class,()->port.permit(terms.routeKey(),grantor));verifyNoInteractions(service);
        when(server.isPrimaryThread()).thenReturn(true);when(api.service(TransitPermitService.class)).thenReturn(Optional.empty());assertThrows(IllegalArgumentException.class,()->port.permit(terms.routeKey(),grantor));assertTrue(port.status().contains("等待"));
        when(api.service(TransitPermitService.class)).thenReturn(Optional.of(service));var bad=new Terms(terms.routeKey(),terms.fingerprint(),terms.grantingNation(),terms.originNation(),terms.targetNation(),terms.parcels(),new BigDecimal("0.001"),terms.expiresAt());when(service.permit(anyString(),any())).thenReturn(Optional.of(new Permit(bad,1,false,actor,1)));assertThrows(IllegalArgumentException.class,()->port.permit(terms.routeKey(),grantor));
    }
}

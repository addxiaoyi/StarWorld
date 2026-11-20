package dev.starworld.integration;

import dev.starworld.domain.WorldState.*;
import dev.starworld.evolution.EvolutionState.*;
import java.time.Instant;
import java.util.*;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BorderConflictCaptureTest {
    final UUID world=UUID.randomUUID(),peer=UUID.randomUUID();
    final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"city","nation",1,"");
    final JavaPlugin plugin=mock(JavaPlugin.class);final Server server=mock(Server.class);final CoreBridge core=mock(CoreBridge.class);final CoreExtensionPort extensions=mock(CoreExtensionPort.class);
    BorderConflictCapture capture(){when(plugin.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);return new BorderConflictCapture(plugin,core,extensions);}
    Building building(int chunk){return new Building(UUID.randomUUID(),city.cityId(),city.nationId(),"farm",new Site(world,"world",chunk*16,64,0),1);}
    CityView view(List<Building> buildings){return new CityView(city.cityId(),city.nationId(),"city","agrarian",-1,Map.of(),buildings,null,"");}
    CoreExtensionPort.NationSnapshot snapshot(Set<UUID> wars,Map<UUID,String> relations,Map<String,String> capabilities){return new CoreExtensionPort.NationSnapshot(city.nationId(),"nation",Instant.EPOCH,"clear",Map.of(),null,Set.of(),Map.of(),"",Map.of(),Map.of(),1,wars,relations,List.of(),0,capabilities);}
    @Test void onlyVerifiedSameNationRegionsReadTheirOwnFourNeighboursAndDeduplicateSites(){
        BorderConflictCapture service=capture();Building first=building(0),sameRegion=new Building(UUID.randomUUID(),city.cityId(),city.nationId(),"workshop",new Site(world,"world",1,64,0),1),invalid=building(3),oldNation=new Building(UUID.randomUUID(),city.cityId(),UUID.randomUUID(),"farm",building(5).site(),1);
        when(core.foreignNeighbors(city,first.site())).thenReturn(List.of(new CoreBridge.BorderNeighbor(first.site(),1,0,peer)));when(extensions.snapshot(eq(city.nationId()),anySet())).thenReturn(Optional.of(snapshot(Set.of(peer),Map.of(),Map.of("war","available"))));
        var result=service.capture(city,view(List.of(first,sameRegion,invalid,oldNation)),Set.of(first.id(),sameRegion.id(),oldNation.id()),10);
        assertEquals(1,result.witnesses().size());assertEquals("WAR",result.witnesses().getFirst().relation());assertEquals(10,result.witnesses().getFirst().period());verify(core,times(1)).foreignNeighbors(eq(city),any());verify(extensions).snapshot(city.nationId(),Set.of(peer));assertThrows(UnsupportedOperationException.class,()->result.witnesses().clear());
    }
    @Test void remoteWarPeaceUnknownCapabilitiesAndMissingCoreCannotManufactureBorderEvidence(){
        BorderConflictCapture service=capture();Building b=building(0);when(core.foreignNeighbors(city,b.site())).thenReturn(List.of(new CoreBridge.BorderNeighbor(b.site(),1,0,peer)));
        for(var state:List.of(snapshot(Set.of(UUID.randomUUID()),Map.of(peer,"NEUTRAL"),Map.of("war","available","diplomacy","available")),snapshot(Set.of(peer),Map.of(peer,"HOSTILE"),Map.of()),snapshot(Set.of(),Map.of(peer,"ALLIED"),Map.of("war","available","diplomacy","available")))){when(extensions.snapshot(eq(city.nationId()),anySet())).thenReturn(Optional.of(state));assertTrue(service.capture(city,view(List.of(b)),Set.of(b.id()),10).witnesses().isEmpty());}
        when(extensions.snapshot(eq(city.nationId()),anySet())).thenReturn(Optional.empty());assertTrue(service.capture(city,view(List.of(b)),Set.of(b.id()),10).witnesses().isEmpty());when(extensions.snapshot(eq(city.nationId()),anySet())).thenThrow(new IllegalStateException("missing core"));assertTrue(service.capture(city,view(List.of(b)),Set.of(b.id()),10).witnesses().isEmpty());
    }
    @Test void explicitHostilePeerIsAcceptedButNoNeighboursAndOffThreadCallsAreNot(){
        BorderConflictCapture service=capture();Building b=building(0);when(core.foreignNeighbors(city,b.site())).thenReturn(List.of(new CoreBridge.BorderNeighbor(b.site(),1,0,peer)));when(extensions.snapshot(eq(city.nationId()),anySet())).thenReturn(Optional.of(snapshot(Set.of(),Map.of(peer,"HOSTILE"),Map.of("diplomacy","available"))));assertEquals("HOSTILE",service.capture(city,view(List.of(b)),Set.of(b.id()),10).witnesses().getFirst().relation());
        when(core.foreignNeighbors(city,b.site())).thenReturn(List.of());assertTrue(service.capture(city,view(List.of(b)),Set.of(b.id()),10).witnesses().isEmpty());when(server.isPrimaryThread()).thenReturn(false);clearInvocations(core,extensions);assertThrows(IllegalStateException.class,()->service.capture(city,view(List.of(b)),Set.of(b.id()),10));verifyNoInteractions(core,extensions);
    }
    @Test void moreThanHundredForeignPeersUsesStableBoundedSubsetWithoutFailingCity(){
        BorderConflictCapture service=capture();List<Building> buildings=new ArrayList<>();Set<UUID> ids=new HashSet<>(),allPeers=new HashSet<>();
        for(int i=0;i<64;i++){Building b=building(i*3);buildings.add(b);ids.add(b.id());int x=b.site().x()>>4;List<CoreBridge.BorderNeighbor> found=new ArrayList<>();for(int[] d:new int[][]{{-1,0},{0,-1},{0,1},{1,0}}){UUID n=UUID.randomUUID();allPeers.add(n);found.add(new CoreBridge.BorderNeighbor(b.site(),x+d[0],d[1],n));}when(core.foreignNeighbors(city,b.site())).thenReturn(found);}
        when(extensions.snapshot(eq(city.nationId()),anySet())).thenAnswer(call->{Set<UUID> selected=call.getArgument(1);assertEquals(100,selected.size());return Optional.of(snapshot(selected,Map.of(),Map.of("war","available")));});
        var first=service.capture(city,view(buildings),ids,10);var second=service.capture(city,view(buildings),ids,10);assertEquals(100,first.witnesses().size());assertEquals(first,second);assertEquals(256,allPeers.size());verify(core,times(128)).foreignNeighbors(eq(city),any());
    }
    @Test void cardinalEvidenceRejectsCoordinateOverflowAndUnknownRelations(){
        assertThrows(IllegalArgumentException.class,()->new BorderWitness(city.cityId(),city.nationId(),world,"world",Integer.MAX_VALUE,0,Integer.MIN_VALUE,0,peer,"WAR",1));assertThrows(IllegalArgumentException.class,()->new BorderWitness(city.cityId(),city.nationId(),world,"world",0,0,1,0,peer,"UNKNOWN",1));
    }
}

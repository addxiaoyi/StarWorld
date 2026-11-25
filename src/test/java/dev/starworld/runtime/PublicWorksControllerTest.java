package dev.starworld.runtime;

import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.CoreBridge;
import dev.starworld.works.*;
import dev.starworld.works.PublicWorksState.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicWorksControllerTest {
    private static final class Fixture {
        final JavaPlugin plugin=mock(JavaPlugin.class);final Server server=mock(Server.class);final WorldRuntime runtime=mock(WorldRuntime.class);final CoreBridge core=mock(CoreBridge.class);final PublicWorksService service=mock(PublicWorksService.class);final Player player=mock(Player.class);final UUID actor=UUID.randomUUID(),anchor=UUID.randomUUID();
        final CoreCity city=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"城","国",1,"fixture");final CityView view=new CityView(city.cityId(),city.nationId(),"城","agrarian",9,Map.of(),List.of(),null,"");final PublicWorksController controller;
        Fixture(){when(plugin.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);when(player.getUniqueId()).thenReturn(actor);when(player.isOnline()).thenReturn(true);when(player.hasPermission("starworld.use")).thenReturn(true);when(runtime.playerCity(player)).thenReturn(Optional.of(city));when(core.city(city.cityId())).thenReturn(Optional.of(city));when(runtime.city(city.cityId())).thenReturn(CompletableFuture.completedFuture(Optional.of(view)));when(runtime.currentPeriod()).thenReturn(10L);when(runtime.manage(player,city.cityId())).thenReturn(true);when(runtime.verifiedBuildings(view)).thenReturn(Set.of(anchor));controller=new PublicWorksController(plugin,runtime,core,service);}
        @SuppressWarnings("unchecked") void immediate(){when(runtime.async(any())).thenAnswer(c->{try{return CompletableFuture.completedFuture(((WorldRuntime.Work<?>)c.getArgument(0)).run());}catch(Exception e){return CompletableFuture.failedFuture(e);}});doAnswer(c->{((Consumer<Object>)c.getArgument(2)).accept(((CompletableFuture<?>)c.getArgument(1)).join());return null;}).when(runtime).reply(any(),any(),any());}
    }
    @Test void captureChecksMainThreadLiveNationManagerAndActualAnchors(){Fixture f=new Fixture();assertEquals(Set.of(f.anchor),f.controller.capture(f.player,f.city,f.view).verified());when(f.server.isPrimaryThread()).thenReturn(false);assertThrows(IllegalStateException.class,()->f.controller.capture(f.player,f.city,f.view));when(f.server.isPrimaryThread()).thenReturn(true);when(f.core.city(f.city.cityId())).thenReturn(Optional.of(new CoreCity(f.city.cityId(),UUID.randomUUID(),"城","新国",1,"changed")));assertThrows(IllegalArgumentException.class,()->f.controller.capture(f.player,f.city,f.view));verify(f.runtime,times(1)).verifiedBuildings(f.view);verify(f.runtime,never()).open(any());}
    @Test void startPreservesRequestUuidAndUsesOnlyReadOnlyCityLookup()throws Exception{Fixture f=new Fixture();f.immediate();UUID id=UUID.randomUUID();Project project=mock(Project.class);ProjectDefinition definition=mock(ProjectDefinition.class);when(project.definition()).thenReturn(definition);when(project.requestId()).thenReturn(id);when(f.service.start(id,f.city,"irrigation_network",10,Set.of(f.anchor),f.actor.toString())).thenReturn(project);List<Project> results=new ArrayList<>();f.controller.startFromPreview(f.player,f.city,"irrigation_network",id,results::add);verify(f.service).start(id,f.city,"irrigation_network",10,Set.of(f.anchor),f.actor.toString());assertEquals(List.of(project),results);verify(f.runtime,never()).open(any());}
    @Test void revokedManagerAfterAsyncReadCannotPayOrStart()throws Exception{Fixture f=new Fixture();f.immediate();when(f.runtime.manage(f.player,f.city.cityId())).thenReturn(true,false);assertThrows(IllegalArgumentException.class,()->f.controller.startFromPreview(f.player,f.city,"irrigation_network",UUID.randomUUID(),p->fail()));verify(f.service,never()).start(any(),any(),anyString(),anyLong(),anySet(),anyString());verify(f.runtime,never()).verifiedBuildings(any());verify(f.runtime,never()).open(any());}
    @Test void changedMembershipAfterAsyncReadCannotStartAnOldCityProject()throws Exception{Fixture f=new Fixture();f.immediate();when(f.runtime.playerCity(f.player)).thenReturn(Optional.of(new CoreCity(f.city.cityId(),UUID.randomUUID(),"城","新国",1,"changed")));assertThrows(IllegalArgumentException.class,()->f.controller.startFromPreview(f.player,f.city,"irrigation_network",UUID.randomUUID(),p->fail()));verify(f.service,never()).start(any(),any(),anyString(),anyLong(),anySet(),anyString());}
    @Test void missingPersistedCityDoesNotReactivateAuthority()throws Exception{Fixture f=new Fixture();f.immediate();when(f.runtime.city(f.city.cityId())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));assertThrows(IllegalArgumentException.class,()->f.controller.startFromPreview(f.player,f.city,"irrigation_network",UUID.randomUUID(),p->fail()));verifyNoInteractions(f.service);verify(f.runtime,never()).open(any());}
    @Test void validOneRowConfigurationUsesRealStatusAndHistoryPages()throws Exception{Fixture f=new Fixture();f.immediate();var cfg=mock(PublicWorksDefinitions.class);var limits=mock(Settings.class);when(limits.maxPage()).thenReturn(1);when(cfg.data()).thenReturn(limits);when(f.service.configuration()).thenReturn(cfg);when(f.service.projects(f.city.cityId(),1,1)).thenReturn(List.of());when(f.service.history(f.city.cityId(),1,2)).thenReturn(List.of());f.controller.execute(f.player,new String[]{"works","status","2"});f.controller.execute(f.player,new String[]{"works","history","3"});verify(f.service).projects(f.city.cityId(),1,1);verify(f.service).history(f.city.cityId(),1,2);}
}

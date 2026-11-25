package dev.starworld.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.foundation.territory.model.ChunkCoordinate;
import dev.starcore.starcore.foundation.territory.model.TerritoryClaim;
import dev.starcore.starcore.module.city.CityService;
import dev.starcore.starcore.module.city.model.City;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.Nation;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.policy.PolicyService;
import dev.starcore.starcore.module.policy.model.PolicyEffectScope;
import dev.starcore.starcore.module.prosperity.ProsperityService;
import dev.starcore.starcore.module.resource.ResourceService;
import dev.starcore.starcore.module.technology.TechnologyService;
import dev.starworld.domain.WorldState.Site;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StarCoreBridgeTest {
    private final UUID cityId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();
    private final NationId nationId = NationId.of(UUID.randomUUID());
    private final UUID worldId = UUID.randomUUID();
    private JavaPlugin owner;
    private Server server;
    private Plugin core;
    private ServicesManager manager;
    private StarCoreApi api;
    private CityService cities;
    private NationService nations;
    private City city;
    private Nation nation;
    private Map<Class<?>, Object> services;
    private StarCoreBridge bridge;

    @BeforeEach
    void setup() {
        owner = mock(JavaPlugin.class);
        server = mock(Server.class);
        PluginManager plugins = mock(PluginManager.class);
        manager = mock(ServicesManager.class);
        core = mock(Plugin.class);
        api = mock(StarCoreApi.class);
        cities = mock(CityService.class);
        nations = mock(NationService.class);
        city = mock(City.class);
        nation = mock(Nation.class);
        services = new HashMap<>();
        services.put(CityService.class, cities);
        when(owner.getServer()).thenReturn(server);
        when(server.isPrimaryThread()).thenReturn(true);
        when(server.getPluginManager()).thenReturn(plugins);
        when(server.getServicesManager()).thenReturn(manager);
        when(plugins.getPlugin("StarCore")).thenReturn(core);
        when(core.isEnabled()).thenReturn(true);
        when(manager.load(StarCoreApi.class)).thenReturn(api);
        when(api.version()).thenReturn("1.0.0");
        when(api.nationService()).thenReturn(Optional.of(nations));
        when(nations.nationOf(playerId)).thenReturn(Optional.of(nation));
        when(api.service(any())).thenAnswer(invocation -> Optional.ofNullable(services.get(invocation.getArgument(0))));
        when(cities.getCity(cityId)).thenReturn(Optional.of(city));
        when(cities.getPlayerCity(playerId)).thenReturn(Optional.of(city));
        when(city.id()).thenReturn(cityId);
        when(city.nationId()).thenReturn(nationId);
        when(city.name()).thenReturn("稻香城");
        when(city.isResident(playerId)).thenReturn(true);
        when(nations.nationById(nationId)).thenReturn(Optional.of(nation));
        when(nation.id()).thenReturn(nationId);
        when(nation.name()).thenReturn("星国");
        when(nation.hasMember(playerId)).thenReturn(true);
        World world = mock(World.class);
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.getName()).thenReturn("world");
        bridge = new StarCoreBridge(owner);
    }

    @Test
    void readsAuditedPublicSignaturesAndAppliesActualModifiers() {
        TechnologyService technology = mock(TechnologyService.class);
        PolicyService policy = mock(PolicyService.class);
        ProsperityService prosperity = mock(ProsperityService.class);
        ResourceService resources = mock(ResourceService.class);
        services.put(TechnologyService.class, technology);
        services.put(PolicyService.class, policy);
        services.put(ProsperityService.class, prosperity);
        services.put(ResourceService.class, resources);
        when(technology.getTotalModifier(nationId, "production_speed")).thenReturn(.3);
        when(policy.activePolicyModifier(nationId, "production_bonus", PolicyEffectScope.GLOBAL)).thenReturn(.12);
        when(policy.activePolicyModifier(nationId, "production_bonus", PolicyEffectScope.PRODUCTION)).thenReturn(.05);
        when(prosperity.getResourceBonus(nationId)).thenReturn(1.2);
        when(resources.stockpile(nationId)).thenReturn(Map.of("strategic", 42L));

        var state = bridge.playerCity(playerId).orElseThrow();
        assertEquals(cityId, state.cityId());
        assertEquals(nationId.value(), state.nationId());
        assertEquals("稻香城", state.cityName());
        assertEquals("星国", state.nationName());
        assertEquals(1.3 * 1.17 * 1.2, state.productionMultiplier(), 1e-10);
        assertTrue(state.detail().contains("strategic=42"));
        assertTrue(bridge.status().contains("1.0.0"));
    }

    @Test
    void optionalModulesDegradeExplicitlyToNeutralProduction() {
        var snapshot = bridge.city(cityId).orElseThrow();
        assertEquals(1, snapshot.productionMultiplier());
        assertTrue(snapshot.detail().contains("科技生产速度读取不可用"));
        assertTrue(snapshot.detail().contains("政策读取不可用"));
        assertTrue(snapshot.detail().contains("繁荣资源倍率读取不可用"));
        assertTrue(snapshot.detail().contains("国家资源模块缺失"));
    }

    @Test
    void invalidOptionalNumbersDoNotPoisonProduction() {
        TechnologyService technology = mock(TechnologyService.class);
        services.put(TechnologyService.class, technology);
        when(technology.getTotalModifier(nationId, "production_speed")).thenReturn(Double.NaN);
        assertEquals(1, bridge.city(cityId).orElseThrow().productionMultiplier());
    }

    @Test
    void managementRequiresLiveNationalMembershipAndMayorOrFounder() {
        assertFalse(bridge.canManage(playerId, cityId));
        when(city.canManageCity(playerId)).thenReturn(true);
        assertTrue(bridge.canManage(playerId, cityId));
        when(city.canManageCity(playerId)).thenReturn(false);
        when(nation.isFounder(playerId)).thenReturn(true);
        assertTrue(bridge.canManage(playerId, cityId));
        when(nation.hasMember(playerId)).thenReturn(false);
        when(nations.nationOf(playerId)).thenReturn(Optional.empty());
        assertFalse(bridge.canManage(playerId, cityId));
        assertTrue(bridge.playerCity(playerId).isEmpty());
    }

    @Test
    void cityFounderUsesNationMembershipIndexWhenNotListedAsMember() {
        when(nation.hasMember(playerId)).thenReturn(false);
        when(nation.isFounder(playerId)).thenReturn(true);

        assertTrue(bridge.playerCity(playerId).isPresent());
        assertTrue(bridge.canManage(playerId, cityId));
        assertTrue(bridge.canGovern(playerId, cityId));
    }

    @Test
    void playerCityRejectsStaleResidenceIndex() {
        when(city.isResident(playerId)).thenReturn(false);
        assertTrue(bridge.playerCity(playerId).isEmpty());
    }

    @Test
    void nationalCivilizationDecisionsRequireFounderRatherThanMayor() {
        when(city.canManageCity(playerId)).thenReturn(true);
        assertTrue(bridge.canManage(playerId, cityId));
        assertFalse(bridge.canGovern(playerId, cityId));
        when(nation.isFounder(playerId)).thenReturn(true);
        assertTrue(bridge.canGovern(playerId, cityId));
        when(nation.hasMember(playerId)).thenReturn(false);
        when(nations.nationOf(playerId)).thenReturn(Optional.empty());
        assertFalse(bridge.canGovern(playerId, cityId));
    }

    @Test
    void playerCityRejectsMembershipInDifferentNation() {
        NationId otherNationId = NationId.of(UUID.randomUUID());
        Nation otherNation = mock(Nation.class);
        when(otherNation.id()).thenReturn(otherNationId);
        when(nations.nationOf(playerId)).thenReturn(Optional.of(otherNation));

        assertTrue(bridge.playerCity(playerId).isEmpty());
        assertFalse(bridge.canManage(playerId, cityId));
        assertFalse(bridge.canGovern(playerId, cityId));
    }

    @Test
    void bothCityClaimAndNationalClaimAreRequiredIncludingNegativeCoordinates() {
        Site site = new Site(worldId, "world", -1, 64, -17);
        when(city.hasClaim("world", -1, -2)).thenReturn(true);
        when(nations.claimAt("world", -1, -2)).thenReturn(Optional.of(new TerritoryClaim(
                nationId.toString(), new ChunkCoordinate("world", -1, -2))));
        assertTrue(bridge.ownsSite(cityId, site));
        when(nations.claimAt("world", -1, -2)).thenReturn(Optional.of(new TerritoryClaim(
                UUID.randomUUID().toString(), new ChunkCoordinate("world", -1, -2))));
        assertFalse(bridge.ownsSite(cityId, site));
        when(nations.claimAt("world", -1, -2)).thenReturn(Optional.empty());
        assertFalse(bridge.ownsSite(cityId, site));
        when(city.hasClaim("world", -1, -2)).thenReturn(false);
        assertFalse(bridge.ownsSite(cityId, site));
    }

    @Test
    void worldIdentityMismatchFailsClosed() {
        assertFalse(bridge.ownsSite(cityId, new Site(worldId, "forged", 0, 64, 0)));
        assertFalse(bridge.ownsSite(cityId, new Site(UUID.randomUUID(), "world", 0, 64, 0)));
    }

    @Test
    void deletedCitiesAndNationsAreNotCached() {
        assertTrue(bridge.city(cityId).isPresent());
        when(cities.getCity(cityId)).thenReturn(Optional.empty());
        assertTrue(bridge.city(cityId).isEmpty());
        assertFalse(bridge.canManage(playerId, cityId));
        when(cities.getCity(cityId)).thenReturn(Optional.of(city));
        when(nations.nationById(nationId)).thenReturn(Optional.empty());
        assertTrue(bridge.city(cityId).isEmpty());
    }

    @Test
    void disabledCoreAndMissingApiOrCityModuleFailClosed() {
        when(core.isEnabled()).thenReturn(false);
        assertTrue(bridge.city(cityId).isEmpty());
        assertFalse(bridge.canManage(playerId, cityId));
        assertTrue(bridge.status().contains("未启用"));
        when(core.isEnabled()).thenReturn(true);
        when(manager.load(StarCoreApi.class)).thenReturn(null);
        assertTrue(bridge.city(cityId).isEmpty());
        when(manager.load(StarCoreApi.class)).thenReturn(api);
        services.remove(CityService.class);
        assertTrue(bridge.city(cityId).isEmpty());
    }

    @Test
    void callerOffPaperMainThreadFailsClosed() {
        when(server.isPrimaryThread()).thenReturn(false);
        assertTrue(bridge.city(cityId).isEmpty());
        assertFalse(bridge.canManage(playerId, cityId));
        assertTrue(bridge.status().contains("主线程"));
    }

    @Test
    void foreignBorderClaimsReadExactlyFourCardinalNeighboursAndNeverReadWorldBlocks() {
        Site site=new Site(worldId,"world",Integer.MAX_VALUE,64,Integer.MIN_VALUE);int x=site.x()>>4,z=site.z()>>4;
        when(city.hasClaim("world",x,z)).thenReturn(true);
        when(nations.claimAt("world",x,z)).thenReturn(Optional.of(new TerritoryClaim(nationId.toString(),new ChunkCoordinate("world",x,z))));
        NationId peerId=NationId.of(UUID.randomUUID());Nation peer=mock(Nation.class);when(peer.id()).thenReturn(peerId);when(nations.nationById(peerId)).thenReturn(Optional.of(peer));
        when(nations.claimAt("world",x+1,z)).thenReturn(Optional.of(new TerritoryClaim(peerId.toString(),new ChunkCoordinate("world",x+1,z))));
        when(nations.claimAt("world",x,z+1)).thenReturn(Optional.of(new TerritoryClaim(nationId.toString(),new ChunkCoordinate("world",x,z+1))));
        var values=bridge.foreignNeighbors(new dev.starworld.domain.WorldState.CoreCity(cityId,nationId.value(),"city","nation",1,""),site);
        assertEquals(1,values.size());assertEquals(peerId.value(),values.getFirst().nation());assertEquals(134217728,values.getFirst().chunkX());assertEquals(-134217728,values.getFirst().chunkZ());
        for(int[] delta:new int[][]{{-1,0},{0,-1},{0,1},{1,0}})verify(nations).claimAt("world",Math.addExact(x,delta[0]),Math.addExact(z,delta[1]));
        verify(nations,times(5)).claimAt(eq("world"),anyInt(),anyInt());World world=server.getWorld(worldId);verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());verify(world,never()).getChunkAt(anyInt(),anyInt());
        assertThrows(IllegalArgumentException.class,()->new CoreBridge.BorderNeighbor(site,Integer.MIN_VALUE,Integer.MAX_VALUE,peerId.value()));
    }

    @Test
    void foreignBorderClaimsRejectMissingCoreNationAndOffThreadCalls() {
        var current=new dev.starworld.domain.WorldState.CoreCity(cityId,nationId.value(),"city","nation",1,"");Site site=new Site(worldId,"world",0,64,0);
        when(city.hasClaim("world",0,0)).thenReturn(true);when(nations.claimAt("world",0,0)).thenReturn(Optional.of(new TerritoryClaim(nationId.toString(),new ChunkCoordinate("world",0,0))));
        when(nations.claimAt("world",1,0)).thenReturn(Optional.of(new TerritoryClaim(UUID.randomUUID().toString(),new ChunkCoordinate("world",1,0))));
        assertTrue(bridge.foreignNeighbors(current,site).isEmpty());when(server.isPrimaryThread()).thenReturn(false);clearInvocations(nations);assertTrue(bridge.foreignNeighbors(current,site).isEmpty());verifyNoInteractions(nations);
        when(server.isPrimaryThread()).thenReturn(true);when(core.isEnabled()).thenReturn(false);assertTrue(bridge.foreignNeighbors(current,site).isEmpty());
    }

    @Test
    void nullInputsAreRejected() {
        assertTrue(bridge.city(null).isEmpty());
        assertTrue(bridge.playerCity(null).isEmpty());
        assertFalse(bridge.canManage(null, cityId));
        assertFalse(bridge.canGovern(null, cityId));
        assertFalse(bridge.ownsSite(cityId, null));
    }
}

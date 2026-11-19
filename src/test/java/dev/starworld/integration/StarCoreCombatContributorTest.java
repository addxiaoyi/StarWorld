package dev.starworld.integration;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.core.extension.*;
import dev.starcore.starcore.core.extension.army.ArmyCombatExtensionPoint;
import dev.starcore.starcore.core.extension.army.ArmyCombatExtensionPoint.*;
import dev.starcore.starcore.module.army.ArmyService;
import dev.starworld.domain.WorldState.*;
import dev.starworld.evolution.EvolutionState.Readiness;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StarCoreCombatContributorTest {
    JavaPlugin owner; Server server; Plugin corePlugin; CoreBridge core; ArmyService armies;
    World world; AtomicLong period; StarCoreCombatContributor contributor;
    UUID attacker = UUID.randomUUID(), defender = UUID.randomUUID(), nationA = UUID.randomUUID(), nationB = UUID.randomUUID(), cityA = UUID.randomUUID(), cityB = UUID.randomUUID();
    Site siteA, siteB;
    @BeforeEach void setup() {
        ExtensionRegistry.getInstance().clear(); owner = mock(JavaPlugin.class); server = mock(Server.class); corePlugin = mock(Plugin.class); core = mock(CoreBridge.class);
        PluginManager plugins = mock(PluginManager.class); ServicesManager services = mock(ServicesManager.class); StarCoreApi api = mock(StarCoreApi.class);
        when(owner.getServer()).thenReturn(server); when(server.isPrimaryThread()).thenReturn(true); when(server.getPluginManager()).thenReturn(plugins); when(plugins.getPlugin("StarCore")).thenReturn(corePlugin); when(corePlugin.isEnabled()).thenReturn(true);
        when(server.getServicesManager()).thenReturn(services); when(services.load(StarCoreApi.class)).thenReturn(api); armies = spy(new ArmyService());
        when(api.service(ArmyService.class)).thenReturn(Optional.of(armies));
        world = mock(World.class); UUID worldId = UUID.randomUUID(); when(world.getUID()).thenReturn(worldId); when(world.getName()).thenReturn("world"); when(server.getWorld(worldId)).thenReturn(world); when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        siteA = new Site(worldId, "world", 0, 64, 0); siteB = new Site(worldId, "world", 32, 64, 0);
        armies.units.put(attacker, new ArmyService.Unit(nationA, new Location(world, 0, 64, 0))); armies.units.put(defender, new ArmyService.Unit(nationB, new Location(world, 32, 64, 0)));
        when(core.city(cityA)).thenReturn(Optional.of(new CoreCity(cityA, nationA, "粮城", "农国", 1, "test"))); when(core.city(cityB)).thenReturn(Optional.of(new CoreCity(cityB, nationB, "铁城", "工国", 1, "test")));
        when(core.ownsSite(cityA, siteA)).thenReturn(true); when(core.ownsSite(cityB, siteB)).thenReturn(true);
        period = new AtomicLong(10); contributor = new StarCoreCombatContributor(owner, core, period::get);
    }
    @AfterEach void cleanup() { when(server.isPrimaryThread()).thenReturn(true); contributor.unregister(); ExtensionRegistry.getInstance().clear(); }
    private Readiness ready(UUID city, UUID nation, long p, double terrain, double readiness) { return new Readiness(city, nation, p, 10, 10, terrain, readiness); }
    private CombatContext context() { return new CombatContext(attacker.toString(), defender.toString(), "INFANTRY", "INFANTRY", 100, 100, 100); }
    private ArmyCombatExtensionPoint point() { return (ArmyCombatExtensionPoint) ExtensionRegistry.getInstance().getExtensionPoint("army_combat"); }

    @Test void reflectedRegistryCreatesMissingPointAndAppliesCommittedSuppliesAndTerrain() {
        assertTrue(contributor.register()); assertTrue(contributor.register()); assertEquals(1, ExtensionRegistry.getInstance().getContributors("army_combat").size());
        contributor.update(ready(cityA, nationA, 10, 1, 1), List.of(siteA)); contributor.update(ready(cityB, nationB, 10, 2, 1), List.of(siteB));
        CombatResult result = point().execute(context()); assertEquals(104, result.damage(), .0001); assertEquals("starworld:city_readiness", result.source()); assertEquals(2, result.appliedModifiers().size());
        contributor.remove(cityB); assertEquals(130, point().execute(context()).damage(), .0001);
        contributor.unregister(); assertNull(ExtensionRegistry.getInstance().getExtensionPoint("army_combat"));
    }
    @Test void oldFutureUnfundedAndOffThreadSnapshotsAreNeutralWithoutUnsafeCoreReads() {
        assertTrue(contributor.register()); contributor.update(ready(cityA, nationA, 10, 1, 1), List.of(siteA));
        period.set(11); assertEquals(130, point().execute(context()).damage(), .0001);
        period.set(12); assertEquals(100, point().execute(context()).damage());
        period.set(9); assertEquals(100, point().execute(context()).damage());
        period.set(10); contributor.update(new Readiness(cityA, nationA, 10, 0, 10, 1, 1), List.of(siteA)); assertEquals(100, point().execute(context()).damage());
        contributor.update(ready(cityA, nationA, 10, 1, 1), List.of(siteA)); clearInvocations(core, armies);
        when(server.isPrimaryThread()).thenReturn(false); assertEquals(100, point().execute(context()).damage()); verifyNoInteractions(core); verify(armies, never()).getArmy(any());
    }
    @Test void currentClaimsNationIdentityAndLoadedWorldAreCheckedAtEveryCombat() {
        assertTrue(contributor.register()); contributor.update(ready(cityA, nationA, 10, 1, 1), List.of(siteA));
        assertEquals(130, point().execute(context()).damage(), .0001);
        when(core.ownsSite(cityA, siteA)).thenReturn(false); assertEquals(100, point().execute(context()).damage());
        when(core.ownsSite(cityA, siteA)).thenReturn(true); when(core.city(cityA)).thenReturn(Optional.of(new CoreCity(cityA, UUID.randomUUID(), "城", "新国", 1, "capture"))); assertEquals(100, point().execute(context()).damage());
        when(core.city(cityA)).thenReturn(Optional.of(new CoreCity(cityA, nationA, "城", "国", 1, "restored"))); when(world.isChunkLoaded(0, 0)).thenReturn(false); assertEquals(100, point().execute(context()).damage());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }
    @Test void maximumAggregateCanOverrideDefenseAndUnregisterPreservesOtherPlugins() {
        new ArmyCombatExtensionPoint(); assertTrue(contributor.register()); contributor.update(ready(cityB, nationB, 10, 2, 1), List.of(siteB));
        assertEquals(80, point().execute(context()).damage(), .0001);
        ExtensionContributor<CombatResult, CombatContext> other = new ExtensionContributor<>() { public String getId() { return "other"; } public String getName() { return "other"; } public CombatResult contribute(CombatContext c) { return new CombatResult(150, "other", List.of()); } };
        ExtensionRegistry.getInstance().registerContributor("army_combat", other); assertEquals(150, point().execute(context()).damage());
        contributor.unregister(); assertNotNull(point()); assertEquals(1, ExtensionRegistry.getInstance().getContributors("army_combat").size()); assertEquals(150, point().execute(context()).damage());
    }
    @Test void configurationDisablesRegistrationAndRejectsInvalidSettings() throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString("combat:\n  enabled: false\n  max-attack: 0.1\n  max-defense: 0.05\n");
        var settings = StarCoreCombatContributor.Settings.load(yaml); contributor = new StarCoreCombatContributor(owner, core, period::get, settings); assertFalse(contributor.register()); assertNull(point());
        assertThrows(IllegalArgumentException.class, () -> new StarCoreCombatContributor.Settings(true, Double.NaN, .2));
        assertThrows(IllegalArgumentException.class, () -> new StarCoreCombatContributor.Settings(true, .3, .9));
        assertThrows(IllegalArgumentException.class, () -> contributor.update(ready(cityA, nationA, 10, 0, 1), List.of(siteA)));
    }
    @Test void removalAndPruningDoNotRetainStaleOrOlderCacheUpdates() {
        assertTrue(contributor.register()); contributor.update(ready(cityA, nationA, 10, 1, 1), List.of(siteA));
        contributor.update(ready(cityA, nationA, 9, 1, 0), List.of(siteA)); assertEquals(130, point().execute(context()).damage(), .0001);
        contributor.prune(12); assertEquals(100, point().execute(context()).damage());
        contributor.update(ready(cityA, nationA, 10, 1, 1), List.of(siteA)); contributor.remove(cityA); assertEquals(100, point().execute(context()).damage());
    }
}

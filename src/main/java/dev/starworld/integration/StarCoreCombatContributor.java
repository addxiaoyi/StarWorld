package dev.starworld.integration;

import dev.starworld.domain.WorldState.Site;
import dev.starworld.evolution.EvolutionState.Readiness;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Public army_combat contributor. No authoritative war/army state or database is mutated. */
public final class StarCoreCombatContributor implements AutoCloseable {
    private static final String BASE = "dev.starcore.starcore.";
    private static final String POINT = "army_combat", ID = "starworld:city_readiness";
    private final JavaPlugin plugin;
    private final CoreBridge core;
    private final LongSupplier currentPeriod;
    private final Settings settings;
    private final Map<UUID, Cached> cities = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, Readiness>> regions = new ConcurrentHashMap<>();
    private volatile boolean registered;
    private volatile String status = "作战扩展尚未注册";
    private Object registry, point, contributor;
    private Class<?> pointType, contributorType, apiType, armyType;
    private Constructor<?> resultConstructor;
    private boolean createdPoint;

    public record Settings(boolean enabled, double maxAttackBonus, double maxDefenseReduction) {
        public Settings {
            if (!Double.isFinite(maxAttackBonus) || maxAttackBonus < 0 || maxAttackBonus > 1
                    || !Double.isFinite(maxDefenseReduction) || maxDefenseReduction < 0 || maxDefenseReduction > .8)
                throw new IllegalArgumentException("Invalid combat modifier limits");
        }
        public static Settings defaults() { return new Settings(true, .30, .20); }
        public static Settings load(ConfigurationSection config) {
            Object enabled = config.get("combat.enabled", true), attack = config.get("combat.max-attack", .30), defense = config.get("combat.max-defense", .20);
            if (!(enabled instanceof Boolean flag) || !(attack instanceof Number a) || !(defense instanceof Number d)) throw new IllegalArgumentException("Invalid combat configuration types");
            return new Settings(flag, a.doubleValue(), d.doubleValue());
        }
    }
    private record Cached(Readiness readiness, Set<String> regions) {}
    private record ArmySnapshot(UUID nation, Site site) {}

    public StarCoreCombatContributor(JavaPlugin plugin, CoreBridge core, LongSupplier currentPeriod) { this(plugin, core, currentPeriod, Settings.defaults()); }
    public StarCoreCombatContributor(JavaPlugin plugin, CoreBridge core, LongSupplier currentPeriod, Settings settings) {
        this.plugin = Objects.requireNonNull(plugin); this.core = Objects.requireNonNull(core); this.currentPeriod = Objects.requireNonNull(currentPeriod); this.settings = Objects.requireNonNull(settings);
    }
    public String status() { return status; }
    public Settings settings() { return settings; }

    /** Register and unregister on the Paper main thread. Creates a missing public extension point only once. */
    public synchronized boolean register() {
        mainThread(); if (registered) return true;
        if (!settings.enabled()) { status = "作战扩展由配置停用"; return false; }
        try {
            Plugin starcore = plugin.getServer().getPluginManager().getPlugin("StarCore");
            if (starcore == null || !starcore.isEnabled()) throw new IllegalStateException("StarCore 未启用");
            ClassLoader loader = starcore.getClass().getClassLoader();
            Class<?> registryType = Class.forName(BASE + "core.extension.ExtensionRegistry", true, loader);
            pointType = Class.forName(BASE + "core.extension.ExtensionPoint", true, loader);
            contributorType = Class.forName(BASE + "core.extension.ExtensionContributor", true, loader);
            Class<?> combat = Class.forName(BASE + "core.extension.army.ArmyCombatExtensionPoint", true, loader);
            apiType = Class.forName(BASE + "api.StarCoreApi", true, loader);
            armyType = Class.forName(BASE + "module.army.ArmyService", true, loader);
            resultConstructor = Class.forName(BASE + "core.extension.army.ArmyCombatExtensionPoint$CombatResult", true, loader).getConstructor(double.class, String.class, List.class);
            Object api = plugin.getServer().getServicesManager().load(apiType);
            if (api == null || optional(apiType.getMethod("service", Class.class).invoke(api, armyType)) == null) throw new IllegalStateException("StarCore 军队公开服务不可用");
            registry = registryType.getMethod("getInstance").invoke(null);
            point = registryType.getMethod("getExtensionPoint", String.class).invoke(registry, POINT);
            if (point == null) { point = combat.getConstructor().newInstance(); createdPoint = true; }
            contributor = Proxy.newProxyInstance(loader, new Class<?>[]{contributorType}, this::invoke);
            registryType.getMethod("registerContributor", String.class, contributorType).invoke(registry, POINT, contributor);
            registered = true; status = "StarCore army_combat 已接入：军需增伤/工业区地形防御；Paper 主线程"; return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            cleanup(); status = "作战扩展降级：" + root(failure).getClass().getSimpleName() + ": " + root(failure).getMessage(); return false;
        }
    }

    /** Pure immutable cache update; root passes only postcommit sites validated on the server thread. */
    public synchronized void update(Readiness value, Collection<Site> sites) {
        Objects.requireNonNull(value); Objects.requireNonNull(sites);
        if (value.city() == null || value.nation() == null || value.period() < 0 || value.provided() < 0 || value.required() < 0
                || !Double.isFinite(value.terrainDefense()) || value.terrainDefense() <= 0 || value.terrainDefense() > 10
                || !Double.isFinite(value.readiness()) || value.readiness() < 0 || value.readiness() > 2 || sites.size() > 256)
            throw new IllegalArgumentException("Invalid city military readiness cache");
        Cached previous = cities.get(value.city()); if (previous != null && previous.readiness().period() > value.period()) return;
        if (previous == null && cities.size() >= 10000) { prune(value.period()); if (cities.size() >= 10000) throw new IllegalArgumentException("Military readiness cache limit reached"); }
        remove(value.city()); Set<String> keys = new HashSet<>(); for (Site site : sites) keys.add(site.regionKey());
        Cached cached = new Cached(value, Set.copyOf(keys)); cities.put(value.city(), cached);
        for (String key : keys) regions.compute(key, (ignored, current) -> { Map<UUID, Readiness> copy = new HashMap<>(current == null ? Map.of() : current); copy.put(value.city(), value); return Map.copyOf(copy); });
    }
    public synchronized void remove(UUID city) {
        Cached previous = cities.remove(city); if (previous == null) return;
        for (String key : previous.regions()) regions.computeIfPresent(key, (ignored, current) -> { Map<UUID, Readiness> copy = new HashMap<>(current); copy.remove(city); return copy.isEmpty() ? null : Map.copyOf(copy); });
    }
    public synchronized void prune(long period) { for (var entry : cities.entrySet()) if (entry.getValue().readiness().period() < period - 1) remove(entry.getKey()); }

    private Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        return switch (method.getName()) {
            case "getId" -> ID;
            case "getName" -> "StarWorld 城市军需与地形";
            case "getDescription" -> "Committed city supplies and live territorial control";
            case "getPriority" -> 100;
            case "getVersion" -> "1.0";
            case "getAuthor" -> "StarWorld";
            case "toString" -> ID;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            case "isApplicable" -> registered && plugin.getServer().isPrimaryThread();
            case "contribute" -> contribution(args == null || args.length == 0 ? null : args[0]);
            default -> null;
        };
    }
    private Object contribution(Object context) {
        // Never access the bridge, army service, locations or worlds from an unexpected core thread.
        if (!registered || context == null || !plugin.getServer().isPrimaryThread()) return null;
        try {
            Plugin starcore = plugin.getServer().getPluginManager().getPlugin("StarCore");
            if (starcore == null || !starcore.isEnabled()) return null;
            Class<?> type = context.getClass(); double base = ((Number) type.getMethod("baseDamage").invoke(context)).doubleValue();
            if (!Double.isFinite(base) || base < 0) return null;
            UUID attacker = UUID.fromString((String) type.getMethod("attackerId").invoke(context));
            UUID defender = UUID.fromString((String) type.getMethod("defenderId").invoke(context));
            long period = currentPeriod.getAsLong(); if (period < 0) return null;
            Optional<ArmySnapshot> a = army(attacker), d = army(defender);
            Readiness attacking = a.map(army -> readiness(army, period)).orElse(null), defending = d.map(army -> readiness(army, period)).orElse(null);
            if (attacking == null && defending == null) return null;
            double factor = factor(attacking, defending, settings);
            if (Math.abs(factor - 1) < 1e-12) return null;
            double damage = base * factor; if (!Double.isFinite(damage)) return null;
            List<String> applied = new ArrayList<>();
            if (attacking != null) applied.add("city_supply:" + attacking.city() + ":" + attacking.period());
            if (defending != null) applied.add("city_terrain:" + defending.city() + ":" + defending.period());
            return resultConstructor.newInstance(damage, ID, List.copyOf(applied));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { return null; }
    }
    private Optional<ArmySnapshot> army(UUID id) throws ReflectiveOperationException {
        Object api = plugin.getServer().getServicesManager().load(apiType); if (api == null) return Optional.empty();
        Object service = optional(apiType.getMethod("service", Class.class).invoke(api, armyType)); if (service == null) return Optional.empty();
        Object army = optional(armyType.getMethod("getArmy", UUID.class).invoke(service, id)); if (army == null) return Optional.empty();
        UUID nation = (UUID) army.getClass().getMethod("nationId").invoke(army);
        Location location = (Location) army.getClass().getMethod("location").invoke(army);
        if (location == null || location.getWorld() == null) return Optional.empty(); World world = location.getWorld();
        if (plugin.getServer().getWorld(world.getUID()) != world || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return Optional.empty();
        return Optional.of(new ArmySnapshot(nation, new Site(world.getUID(), world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ())));
    }
    private Readiness readiness(ArmySnapshot army, long period) {
        Map<UUID, Readiness> candidates = regions.getOrDefault(army.site().regionKey(), Map.of());
        for (Readiness value : candidates.values().stream().sorted(Comparator.comparing(Readiness::city)).toList()) {
            if (!value.nation().equals(army.nation()) || value.period() > period || value.period() < period - 1 || value.required() == 0 || value.provided() == 0) continue;
            if (core.city(value.city()).map(city -> city.nationId().equals(value.nation())).orElse(false) && core.ownsSite(value.city(), army.site())) return value;
        }
        return null;
    }
    /** Defaults: at most +30% from actual supplied readiness, and at most -20% defensive terrain. */
    public static double factor(Readiness attacker, Readiness defender, Settings settings) {
        double attack = attacker == null ? 0 : Math.max(0, Math.min(1, attacker.readiness()));
        double defense = defender == null ? 0 : Math.max(0, Math.min(1, defender.terrainDefense() - 1)) * Math.max(0, Math.min(1, defender.readiness()));
        return (1 + settings.maxAttackBonus() * attack) * (1 - settings.maxDefenseReduction() * defense);
    }

    public synchronized void unregister() { mainThread(); registered = false; cleanup(); cities.clear(); regions.clear(); status = "作战扩展已卸载"; }
    private void cleanup() {
        try {
            if (registry != null && point != null && contributor != null) registry.getClass().getMethod("unregisterContributor", pointType, contributorType).invoke(registry, point, contributor);
            if (createdPoint && registry != null && point != null && registry.getClass().getMethod("getExtensionPoint", String.class).invoke(registry, POINT) == point) {
                Object current = registry.getClass().getMethod("getContributors", String.class).invoke(registry, POINT);
                if (current instanceof Collection<?> values && values.isEmpty()) registry.getClass().getMethod("unregisterExtensionPoint", String.class).invoke(registry, POINT);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { /* Do not erase another plugin's contributors on failure. */ }
        registry = null; point = null; contributor = null; resultConstructor = null; createdPoint = false; registered = false;
    }
    private void mainThread() { if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("作战扩展注册操作需要 Paper 主线程"); }
    private static Object optional(Object value) { if (!(value instanceof Optional<?> result)) throw new IllegalStateException("Expected public Optional API"); return result.orElse(null); }
    private static Throwable root(Throwable value) { while (value.getCause() != null) value = value.getCause(); return value; }
    @Override public void close() { unregister(); }
}

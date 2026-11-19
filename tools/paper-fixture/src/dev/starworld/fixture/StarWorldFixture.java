package dev.starworld.fixture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;

/** Isolated test helper. Uses real public services and a real connected player, never mock StarCore. */
public final class StarWorldFixture extends JavaPlugin implements CommandExecutor {
    private static final String CORE = "dev.starcore.starcore.";
    private final Gson json = new GsonBuilder().serializeNulls().create();
    private final Set<UUID> registering = new HashSet<>();
    private final ExpansionFixture expansion = new ExpansionFixture(this);

    @Override public void onEnable() {
        Objects.requireNonNull(getCommand("swfixture")).setExecutor(this);
        getLogger().warning("TEST FIXTURE ONLY: creates real test countries/claims and replaces blocks near (8,80,8).");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("Console only; never install this fixture on a production server.");
            return true;
        }
        try {
            if (args.length>0 && args[0].startsWith("exp-")) return expansion.execute(args);
            if (args.length != 2) throw new IllegalArgumentException("swfixture <setup|register|verify|unclaim|reclaim|seed|inventory> <connected-player>");
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) throw new IllegalArgumentException("A real connected player is required: " + args[1]);
            Object api = service("StarCore", CORE + "api.StarCoreApi");
            Class<?> apiType = type("StarCore", CORE + "api.StarCoreApi");
            Object nations = unwrap(apiType.getMethod("nationService").invoke(api));
            Class<?> cityServiceType = type("StarCore", CORE + "module.city.CityService");
            Object cities = unwrap(apiType.getMethod("service", Class.class).invoke(api, cityServiceType));
            if (nations == null || cities == null) throw new IllegalStateException("Real StarCore nation/city modules must be enabled");
            if (args[0].equals("seed")) {
                ItemStack ordinary = new ItemStack(Material.WHEAT, 16);
                ItemStack named = new ItemStack(Material.WHEAT, 7);
                ItemMeta meta = named.getItemMeta();
                meta.displayName(Component.text("StarWorld QA reserved wheat"));
                named.setItemMeta(meta);
                if (!player.getInventory().addItem(ordinary, named).isEmpty()) throw new IllegalStateException("Inventory full during test seed");
                player.saveData();
                inventory(player, "seed");
                return true;
            }
            if (args[0].equals("inventory")) { inventory(player, "inventory"); return true; }
            Class<?> nationServiceType = type("StarCore", CORE + "module.nation.NationService");
            Object nation = unwrap(nationServiceType.getMethod("nationOf", UUID.class).invoke(nations, player.getUniqueId()));
            if (args[0].equals("setup")) {
                if (nation == null) nation = nationServiceType.getMethod("createNation", UUID.class, String.class, String.class)
                        .invoke(nations, player.getUniqueId(), player.getName(), "SWTest" + player.getName());
                Object nationId = call(nation, "id");
                Object city = unwrap(cityServiceType.getMethod("getPlayerCity", UUID.class).invoke(cities, player.getUniqueId()));
                if (city == null) {
                    Object result = cityServiceType.getMethod("createCity", nationId.getClass(), String.class, UUID.class, Location.class)
                            .invoke(cities, nationId, "SWCity" + player.getName(), player.getUniqueId(), new Location(player.getWorld(), 8, 80, 8));
                    if (!Boolean.TRUE.equals(call(result, "success"))) throw new IllegalStateException(String.valueOf(call(result, "message")));
                    city = call(result, "city");
                }
                UUID cityId = (UUID) call(city, "id");
                for (int x = 0; x < 3; x++) {
                    claim(nations, nationServiceType, player, x);
                    cityServiceType.getMethod("addClaim", UUID.class, String.class, int.class, int.class)
                            .invoke(cities, cityId, player.getWorld().getName(), x, 0);
                    if (!Boolean.TRUE.equals(city.getClass().getMethod("hasClaim", String.class, int.class, int.class)
                            .invoke(city, player.getWorld().getName(), x, 0))) throw new IllegalStateException("City claim was not persisted: " + x);
                    platform(player.getWorld(), 8 + x * 16, 8);
                }
                getLogger().info("SW_FIXTURE_SETUP city=" + cityId + " nation=" + nationId + " player=" + player.getUniqueId());
            } else if (args[0].equals("register")) {
                if (!registering.add(player.getUniqueId())) throw new IllegalStateException("Registration fixture is already running");
                registerStep(player, 0);
            } else if (args[0].equals("unclaim") || args[0].equals("reclaim")) {
                if (args[0].equals("unclaim")) nationServiceType.getMethod("unclaimCurrentChunk", UUID.class, String.class, int.class, int.class)
                        .invoke(nations, player.getUniqueId(), player.getWorld().getName(), 2, 0);
                else claim(nations, nationServiceType, player, 2);
                Object remaining = unwrap(nationServiceType.getMethod("claimAt", String.class, int.class, int.class)
                        .invoke(nations, player.getWorld().getName(), 2, 0));
                if (args[0].equals("unclaim") && remaining != null) throw new IllegalStateException("National claim was not removed");
                getLogger().info("SW_FIXTURE_" + args[0].toUpperCase(Locale.ROOT));
            } else if (args[0].equals("verify")) {
                Object city = unwrap(cityServiceType.getMethod("getPlayerCity", UUID.class).invoke(cities, player.getUniqueId()));
                UUID cityId = (UUID) call(Objects.requireNonNull(city, "No real core city"), "id");
                Object world = service("StarWorld", "dev.starworld.api.StarWorldApi");
                Class<?> worldType = type("StarWorld", "dev.starworld.api.StarWorldApi");
                @SuppressWarnings("unchecked") CompletableFuture<Optional<?>> future =
                        (CompletableFuture<Optional<?>>) worldType.getMethod("city", UUID.class).invoke(world, cityId);
                future.whenComplete((value, failure) -> Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        if (failure != null) throw new IllegalStateException(failure);
                        Object snapshot = value.orElseThrow(() -> new IllegalStateException("City has not been registered by StarWorld"));
                        getLogger().info("SW_FIXTURE_VERIFY city=" + cityId + " lastPeriod=" + call(snapshot, "lastPeriod")
                                + " stock=" + call(snapshot, "stock") + " buildings=" + call(snapshot, "buildings")
                                + " event=" + call(snapshot, "event") + " lastReport=" + call(snapshot, "lastReport"));
                        Map<String, Object> report = new LinkedHashMap<>();
                        report.put("action", "verify"); report.put("player", player.getName()); report.put("cityId", cityId.toString());
                        report.put("lastPeriod", call(snapshot, "lastPeriod")); report.put("stock", call(snapshot, "stock"));
                        report.put("buildingCount", ((Collection<?>) call(snapshot, "buildings")).size());
                        Object event = call(snapshot, "event"); report.put("event", event == null ? null : event.toString());
                        report.put("lastReport", call(snapshot, "lastReport"));
                        getLogger().info("SW_FIXTURE_JSON " + json.toJson(report));
                    } catch (Exception error) { getLogger().log(java.util.logging.Level.SEVERE, "SW_FIXTURE_FAILED", error); }
                }));
            } else throw new IllegalArgumentException("Unknown fixture action");
        } catch (Exception error) {
            getLogger().log(java.util.logging.Level.SEVERE, "SW_FIXTURE_FAILED", error);
        }
        return true;
    }

    private void registerStep(Player player, int index) {
        String[] kinds = {"farm", "sawmill", "workshop"};
        Material[] anchors = {Material.COMPOSTER, Material.STONECUTTER, Material.BLAST_FURNACE};
        if (index == kinds.length) {
            registering.remove(player.getUniqueId());
            getLogger().info("SW_FIXTURE_REGISTER_DISPATCHED player=" + player.getName());
            return;
        }
        try {
            if (!player.isOnline()) throw new IllegalStateException("Test player left during registration");
            int x = 8 + index * 16;
            player.getWorld().getBlockAt(x, 80, 8).setType(anchors[index]);
            Location start = new Location(player.getWorld(), x + 0.5, 80, 5.5);
            Vector target = new Vector(x + 0.5, 80.5, 8.5);
            start.setDirection(target.subtract(start.toVector().add(new Vector(0, player.getEyeHeight(), 0))));
            if (!player.teleport(start) || player.getLocation().distanceSquared(start) > 0.1)
                throw new IllegalStateException("Test registration teleport was cancelled for " + kinds[index]);
            getLogger().info("SW_FIXTURE_REGISTER_STEP player=" + player.getName() + " type=" + kinds[index] + " stage=positioned");
            // Let teleport acknowledgements and Paper command dispatch settle before changing targets.
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    if (!player.isOnline()) throw new IllegalStateException("Test player left before registration command");
                    org.bukkit.block.Block targetBlock = player.getTargetBlockExact(6);
                    if (targetBlock == null || targetBlock.getType() != anchors[index])
                        throw new IllegalStateException("Test raycast does not hit " + kinds[index] + " anchor");
                    if (!player.performCommand("sw register " + kinds[index])) throw new IllegalStateException("Command not handled: " + kinds[index]);
                    getLogger().info("SW_FIXTURE_REGISTER_STEP player=" + player.getName() + " type=" + kinds[index] + " stage=dispatched");
                    Bukkit.getScheduler().runTaskLater(this, () -> registerStep(player, index + 1), 3L);
                } catch (Exception error) {
                    registering.remove(player.getUniqueId());
                    getLogger().log(java.util.logging.Level.SEVERE, "SW_FIXTURE_FAILED", error);
                }
            }, 3L);
        } catch (Exception error) {
            registering.remove(player.getUniqueId());
            getLogger().log(java.util.logging.Level.SEVERE, "SW_FIXTURE_FAILED", error);
        }
    }

    private void claim(Object nations, Class<?> contract, Player player, int x) throws Exception {
        Object nation = Objects.requireNonNull(unwrap(contract.getMethod("nationOf", UUID.class).invoke(nations, player.getUniqueId())), "Player has no real nation");
        String nationId = call(nation, "id").toString();
        Object existing = unwrap(contract.getMethod("claimAt", String.class, int.class, int.class)
                .invoke(nations, player.getWorld().getName(), x, 0));
        if (existing == null) contract.getMethod("claimCurrentChunk", UUID.class, String.class, int.class, int.class)
                .invoke(nations, player.getUniqueId(), player.getWorld().getName(), x, 0);
        Object verified = unwrap(contract.getMethod("claimAt", String.class, int.class, int.class)
                .invoke(nations, player.getWorld().getName(), x, 0));
        if (verified == null || !nationId.equals(call(verified, "ownerId")))
            throw new IllegalStateException("Chunk " + x + ",0 belongs to another country or was not claimed");
    }
    private static void platform(World world, int x, int z) {
        // Deliberately bounded test-world edits, on the Paper server thread.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -4; dz <= 2; dz++) world.getBlockAt(x + dx, 79, z + dz).setType(Material.STONE);
        world.getChunkAt(x >> 4, z >> 4).setForceLoaded(true);
    }
    private void inventory(Player player, String action) {
        List<Map<String, Object>> content = new ArrayList<>();
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot]; if (item == null || item.isEmpty()) continue;
            Map<String, Object> stack = new LinkedHashMap<>();
            stack.put("slot", slot); stack.put("type", item.getType().getKey().toString()); stack.put("amount", item.getAmount());
            stack.put("named", item.hasItemMeta() && item.getItemMeta().hasDisplayName());
            content.add(stack);
        }
        getLogger().info("SW_FIXTURE_JSON " + json.toJson(Map.of("action", action, "player", player.getName(), "items", content)));
    }
    private static Object call(Object target, String method) throws Exception { return target.getClass().getMethod(method).invoke(target); }
    private static Object unwrap(Object value) { return ((Optional<?>) value).orElse(null); }
    private static Class<?> type(String plugin, String type) throws ClassNotFoundException {
        Plugin p = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin(plugin), plugin);
        return Class.forName(type, true, p.getClass().getClassLoader());
    }
    private static Object service(String plugin, String type) throws Exception {
        return Objects.requireNonNull(Bukkit.getServicesManager().load(type(plugin, type)), type + " not registered");
    }
}

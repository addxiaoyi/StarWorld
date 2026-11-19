package dev.starworld.integration;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Optional CraftEngine item boundary. Call item methods on the owning player's
 * scheduler; returned stacks are independent. Never accesses SenLuo state.
 * Vanilla inputs deliberately accept only pristine items, preserving named,
 * enchanted, damaged, PDC-bearing and other special player possessions.
 */
public final class CustomItems {
    private static final Pattern ID = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");
    private final JavaPlugin owner;
    private final PluginManager plugins;
    private final Access access;
    private final Map<String, String> diagnostics = new ConcurrentHashMap<>();

    public CustomItems(JavaPlugin owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.plugins = owner.getServer().getPluginManager();
        Access resolved = null;
        Plugin ce = plugins.getPlugin("CraftEngine");
        if (ce != null && ce.isEnabled()) {
            try {
                resolved = new ReflectiveAccess(ce.getClass().getClassLoader());
                diagnostics.put("CraftEngine", "可用：公共物品 API；自定义内容按实际加载定义检查");
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                diagnostics.put("CraftEngine", "不可用：物品 API 不兼容；自定义物品已停用");
                owner.getLogger().warning("CraftEngine item API unavailable: " + failure.getClass().getSimpleName());
            }
        } else {
            diagnostics.put("CraftEngine", "未启用：自定义物品配方不可用，原版生产链可用");
        }
        this.access = resolved;
        describe("KaleidoscopeCookeryPlugin", "可用：CE 食材/菜肴 ID；不将取菜前事件当作生产回执", "未启用：厨房物品联动停用");
        describe("KaleidoscopeTavernPlugin", "可用：CE 饮品 ID；构造事件无玩家/位置，不自动奖励城市", "未启用：酒馆物品联动停用");
        describe("KaleidoscopeDecoPlugin", "已启用：无公开城市装饰贡献 API；自动家具加成不可用", "未启用：装饰联动不可用，原版建筑可用");
        describe("KaleidoscopeMusicPlugin", "已启用：无公开演奏完成 API；自动节庆演奏加成不可用", "未启用：音乐联动不可用，世界节庆仍可用");
    }

    // Isolates external API failure paths for contract tests without a server.
    CustomItems(JavaPlugin owner, Access access) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.plugins = owner.getServer().getPluginManager();
        this.access = access;
    }

    private void describe(String name, String present, String absent) {
        diagnostics.put(name, plugins.isPluginEnabled(name) ? present : absent);
    }

    /** Returns null for unavailable custom content; invalid vanilla IDs fail fast. */
    public ItemStack create(String id, int amount) {
        checkId(id);
        if (amount <= 0) throw new IllegalArgumentException("Item amount must be positive");
        if (vanilla(id)) return new ItemStack(material(id), amount);
        if (!available()) return null;
        try {
            ItemStack built = access.create(id);
            if (built == null || built.isEmpty()) {
                unavailable(id, "CE 未加载该物品");
                return null;
            }
            ItemStack result = built.clone();
            result.setAmount(amount);
            diagnostics.remove("item:" + id);
            return result;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            unavailable(id, "CE 创建失败：" + failure.getClass().getSimpleName());
            return null;
        }
    }

    /** Custom IDs require exact CE identity; vanilla IDs require pristine stacks. */
    public boolean matches(ItemStack stack, String id) {
        checkId(id);
        if (stack == null || stack.isEmpty()) return false;
        if (vanilla(id)) {
            if (stack.getType() != material(id) || stack.hasItemMeta()) return false;
            // If CE is present but incompatible, fail closed instead of consuming
            // an unidentified custom item whose base material happens to match.
            if (plugins.isPluginEnabled("CraftEngine")) {
                if (!available()) return false;
                try { return access.id(stack) == null; }
                catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                    unavailable(id, "CE 身份检查失败");
                    return false;
                }
            }
            return true;
        }
        if (!available()) return false;
        try { return id.equals(access.id(stack)); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            unavailable(id, "CE 身份检查失败");
            return false;
        }
    }

    /** Immutable capability snapshot. Installation does not imply a gameplay API. */
    public Map<String, String> status() {
        Map<String, String> result = new LinkedHashMap<>();
        diagnostics.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> result.put(e.getKey(), e.getValue()));
        return Map.copyOf(result);
    }

    /** Validates syntax/vanilla definitions; missing custom content is a degradation. */
    public void validate(Collection<String> ids) {
        for (String id : Objects.requireNonNull(ids, "ids")) {
            checkId(id);
            if (vanilla(id)) { material(id); continue; }
            if (!available()) { unavailable(id, "CraftEngine 未启用或 API 不兼容"); continue; }
            try {
                if (!access.exists(id)) unavailable(id, "CE 未加载该物品");
                else diagnostics.remove("item:" + id);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                unavailable(id, "CE 查询失败：" + failure.getClass().getSimpleName());
            }
        }
    }

    private boolean available() { return access != null && plugins.isPluginEnabled("CraftEngine"); }
    private void unavailable(String id, String reason) { diagnostics.put("item:" + id, "不可用：" + reason); }
    private static boolean vanilla(String id) { return id.startsWith("minecraft:"); }
    private static void checkId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid namespaced item ID: " + id);
    }
    private static Material material(String id) {
        Material type = Material.matchMaterial(id);
        if (type == null || type.isAir() || !type.isItem()) throw new IllegalArgumentException("Unknown vanilla item: " + id);
        return type;
    }

    interface Access {
        String id(ItemStack stack) throws ReflectiveOperationException;
        ItemStack create(String id) throws ReflectiveOperationException;
        boolean exists(String id) throws ReflectiveOperationException;
    }

    /** Public API calls verified against SenLuo's CE 26.9.1 baseline. */
    static final class ReflectiveAccess implements Access {
        private final Method customId;
        private final Method keyOf;
        private final Method keyAsString;
        private final Method managerInstance;
        private final Method getBuildable;
        private final Method buildItem;
        private final Method bukkitStack;

        ReflectiveAccess(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> items = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems", true, loader);
            Class<?> key = Class.forName("net.momirealms.craftengine.core.util.Key", true, loader);
            Class<?> manager = Class.forName("net.momirealms.craftengine.bukkit.item.BukkitItemManager", true, loader);
            Class<?> stackUtils = Class.forName("net.momirealms.craftengine.bukkit.util.ItemStackUtils", true, loader);
            Class<?> buildable = Class.forName("net.momirealms.craftengine.core.item.BuildableItem", true, loader);
            Class<?> item = Class.forName("net.momirealms.craftengine.core.item.Item", true, loader);
            Class<?> player = Class.forName("net.momirealms.craftengine.core.entity.player.Player", true, loader);
            customId = items.getMethod("getCustomItemId", ItemStack.class);
            keyOf = key.getMethod("of", String.class);
            keyAsString = key.getMethod("asString");
            managerInstance = manager.getMethod("instance");
            getBuildable = manager.getMethod("getBuildableItem", key);
            buildItem = buildable.getMethod("buildItem", player);
            // The Object overload expects a native Minecraft stack, not a CE Item wrapper.
            bukkitStack = stackUtils.getMethod("getBukkitStack", item);
        }

        @Override public String id(ItemStack stack) throws ReflectiveOperationException {
            Object key = customId.invoke(null, stack);
            return key == null ? null : (String) keyAsString.invoke(key);
        }

        private Optional<?> definition(String id) throws ReflectiveOperationException {
            return (Optional<?>) getBuildable.invoke(managerInstance.invoke(null), keyOf.invoke(null, id));
        }

        @Override public boolean exists(String id) throws ReflectiveOperationException { return definition(id).isPresent(); }

        @Override public ItemStack create(String id) throws ReflectiveOperationException {
            Object definition = definition(id).orElse(null);
            if (definition == null) return null;
            Object item = buildItem.invoke(definition, new Object[]{null});
            if (item == null) return null;
            return (ItemStack) bukkitStack.invoke(null, item);
        }
    }
}

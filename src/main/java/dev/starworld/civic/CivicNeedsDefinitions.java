package dev.starworld.civic;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Versioned, bounded costs and rewards. Definitions are snapshotted when a need is requested. */
public record CivicNeedsDefinitions(int schemaVersion, int maximumPage, int maximumOffset, int historyLimit, Map<String, Need> needs) {
    private static final Map<String,String> KINDS = Map.of("community_meals", "feast", "public_spaces", "decoration", "festival", "concert");
    public CivicNeedsDefinitions {
        if (schemaVersion != 1 || maximumPage < 1 || maximumPage > 128 || maximumOffset < 0 || maximumOffset > 100000 || historyLimit < 10 || historyLimit > 2000)
            throw new IllegalArgumentException("Invalid civic needs version or page limits");
        needs = Map.copyOf(needs);
        if (!needs.keySet().equals(KINDS.keySet())) throw new IllegalArgumentException("Exactly the three supported civic needs must be configured");
        needs.forEach((id, need) -> { if (!id.equals(need.id())) throw new IllegalArgumentException("Civic need key/id mismatch"); });
    }
    public CivicNeedsDefinitions(int schemaVersion,int maximumPage,int maximumOffset,Map<String,Need> needs) { this(schemaVersion,maximumPage,maximumOffset,200,needs); }
    public record Need(String id, String name, int lifetimePeriods, int cooldownPeriods, Map<String,Long> costs,
                       String actionKind, int actionCount, boolean distinctSources, double stabilityReward, double prosperityReward) {
        public Need {
            if (!KINDS.containsKey(id) || !KINDS.get(id).equals(actionKind) || name == null || name.isBlank() || name.length() > 80
                    || lifetimePeriods < 12 || lifetimePeriods > 24 || cooldownPeriods < 12 || cooldownPeriods > 240
                    || actionCount < 1 || actionCount > 4 || distinctSources != id.equals("public_spaces")
                    || distinctSources && actionCount < 2) throw new IllegalArgumentException("Invalid civic need definition");
            costs = Map.copyOf(costs);
            if (costs.isEmpty() || costs.size() > 16) throw new IllegalArgumentException("Civic needs must consume bounded real goods");
            costs.forEach((item, count) -> { item(item); if (count < 1 || count > 1000000) throw new IllegalArgumentException("Invalid civic material amount"); });
            if (!Double.isFinite(stabilityReward) || stabilityReward < 0 || stabilityReward > 2
                    || !Double.isFinite(prosperityReward) || prosperityReward < 0 || prosperityReward > 3
                    || stabilityReward + prosperityReward <= 0) throw new IllegalArgumentException("Civic rewards are local stability <=2 and prosperity <=3");
        }
    }
    public static CivicNeedsDefinitions defaults() {
        return new CivicNeedsDefinitions(1, 32, 10000, Map.of(
                "community_meals", new Need("community_meals", "社区聚餐", 12, 24, Map.of("minecraft:wheat",16L,"minecraft:bread",4L), "feast",1,false,2,1),
                "public_spaces", new Need("public_spaces", "公共空间",24,48,Map.of("minecraft:oak_planks",16L),"decoration",2,true,1,3),
                "festival", new Need("festival", "城市节庆",18,36,Map.of("minecraft:paper",8L,"minecraft:bread",8L),"concert",1,false,2,2)));
    }
    public Set<String> itemIds() { Set<String> ids = new HashSet<>(); needs.values().forEach(n -> ids.addAll(n.costs().keySet())); return Set.copyOf(ids); }
    /** Explicit optional-dependency degradation; vanilla community feasts and note-block concerts remain usable. */
    public Map<String,String> availability(Set<String> loadedItems, boolean furnitureEvidenceAvailable) {
        Set<String> available = Set.copyOf(loadedItems); Map<String,String> result = new TreeMap<>();
        for (Need need : needs.values()) result.put(need.id(), !available.containsAll(need.costs().keySet()) ? "停用：所需物品未加载"
                : need.distinctSources() && !furnitureEvidenceAvailable ? "等待真实家具维护凭据：CraftEngine 家具 API 不可用，不能以登记代替维护"
                : "可用：需真实仓库物资与请求后已提交的生活贡献");
        return Map.copyOf(result);
    }
    public static CivicNeedsDefinitions load(File file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.options().pathSeparator('\u001f'); yaml.load(file);
        keys(yaml, Set.of("civic-needs-version","limits","needs"));
        ConfigurationSection limits = section(yaml,"limits"), definitions = section(yaml,"needs");
        keys(limits,Set.of("maximum-page","maximum-offset","history-limit")); Map<String,Need> needs = new LinkedHashMap<>();
        for (String id : definitions.getKeys(false)) {
            ConfigurationSection n = section(definitions,id);
            keys(n,Set.of("name","lifetime-periods","cooldown-periods","costs","action-kind","action-count","distinct-sources","stability-reward","prosperity-reward"));
            Map<String,Long> costs = new TreeMap<>(); section(n,"costs").getValues(false).forEach((item,value) -> costs.put(item,whole(value,"cost "+item)));
            Object distinct = n.get("distinct-sources"); if (!(distinct instanceof Boolean)) throw new IllegalArgumentException("distinct-sources must be boolean");
            needs.put(id,new Need(id,text(n,"name"),integer(n,"lifetime-periods"),integer(n,"cooldown-periods"),costs,text(n,"action-kind"),integer(n,"action-count"),(Boolean)distinct,number(n,"stability-reward"),number(n,"prosperity-reward")));
        }
        return new CivicNeedsDefinitions(integer(yaml,"civic-needs-version"),integer(limits,"maximum-page"),integer(limits,"maximum-offset"),integer(limits,"history-limit"),needs);
    }
    /** Additive installation only. Existing administrator files are never overwritten. */
    public static void writeDefaults(File file) throws Exception {
        if (file.exists()) return;
        File parent = file.getAbsoluteFile().getParentFile(); if (parent != null) Files.createDirectories(parent.toPath());
        try (var stream = CivicNeedsDefinitions.class.getResourceAsStream("/civic-needs.yml")) {
            if (stream == null) throw new IllegalStateException("Missing civic-needs.yml resource"); Files.copy(stream,file.toPath());
        }
    }
    private static void keys(ConfigurationSection section,Set<String> expected) { if (!section.getKeys(false).equals(expected)) throw new IllegalArgumentException("Unknown or missing civic keys in "+section.getCurrentPath()); }
    private static ConfigurationSection section(ConfigurationSection root,String key) { ConfigurationSection found=root.getConfigurationSection(key); if(found==null)throw new IllegalArgumentException("Missing civic section "+key);return found; }
    private static String text(ConfigurationSection root,String key) { Object value=root.get(key);if(!(value instanceof String text)||text.isBlank())throw new IllegalArgumentException("Invalid civic text "+key);return text; }
    private static long whole(Object value,String key) { if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.longValue())throw new IllegalArgumentException("Expected whole civic number "+key);return n.longValue(); }
    private static int integer(ConfigurationSection root,String key) { long value=whole(root.get(key),key);if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new IllegalArgumentException("Civic integer out of range "+key);return (int)value; }
    private static double number(ConfigurationSection root,String key) { if(!(root.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Expected finite civic number "+key);return n.doubleValue(); }
    private static void item(String value) { if(value==null||value.length()>160||!value.matches("[a-z0-9._-]+:[a-z0-9/._-]+"))throw new IllegalArgumentException("Invalid civic item id"); }
}

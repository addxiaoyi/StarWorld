package dev.starworld.life;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Independent, immutable life.yml definitions. Item identity is validated by the CE boundary at runtime. */
public record LifeDefinitions(int professionCooldownPeriods, int playerCulturePerPeriod, int cityCulturePerPeriod,
                              long cultureLimit, double maximumProductionBonus, Map<String, Profession> professions,
                              Map<String, Feast> feasts, DecorationRules decorations, ConcertRules concerts) {
    public LifeDefinitions {
        if (professionCooldownPeriods < 1 || professionCooldownPeriods > 1000 || playerCulturePerPeriod < 1
                || cityCulturePerPeriod < playerCulturePerPeriod || cityCulturePerPeriod > 100000
                || cultureLimit < cityCulturePerPeriod || cultureLimit > 1_000_000_000L) throw new IllegalArgumentException("Invalid life limits");
        bonus(maximumProductionBonus); professions = Map.copyOf(professions); feasts = Map.copyOf(feasts);
        Objects.requireNonNull(decorations); Objects.requireNonNull(concerts);
        if (professions.isEmpty() || feasts.isEmpty()) throw new IllegalArgumentException("Empty life definitions");
        for (var entry : professions.entrySet()) if (!entry.getKey().equals(entry.getValue().id())) throw new IllegalArgumentException("Profession id mismatch");
        for (var entry : feasts.entrySet()) {
            Feast f = entry.getValue();
            if (!entry.getKey().equals(f.id()) || !professions.containsKey(f.profession())) throw new IllegalArgumentException("Unknown feast profession");
        }
        if (!professions.containsKey(decorations.profession()) || !professions.containsKey(concerts.profession())) throw new IllegalArgumentException("Unknown life profession");
    }
    public record Profession(String id, String name) {
        public Profession { identifier(id); Objects.requireNonNull(name); if (name.isBlank()) throw new IllegalArgumentException("Empty profession name"); }
    }
    public record Feast(String id, String name, String profession, Map<String, Long> inputs, int culture,
                        double productionBonus, int durationPeriods) {
        public Feast { identifier(id); Objects.requireNonNull(name); identifier(profession); inputs = costs(inputs);
            if (culture < 1 || culture > 10000 || durationPeriods < 1 || durationPeriods > 1000) throw new IllegalArgumentException("Invalid feast reward"); bonus(productionBonus); }
    }
    public record DecorationRules(String profession, Set<String> furnitureIds, Map<String, Long> registrationCost,
                                  Map<String, Long> upkeep, int maximumPerCity, int culturePerPeriod, double productionBonus) {
        public DecorationRules { identifier(profession); furnitureIds = Set.copyOf(furnitureIds); furnitureIds.forEach(LifeDefinitions::item);
            registrationCost = costs(registrationCost); upkeep = costs(upkeep);
            if (furnitureIds.isEmpty() || maximumPerCity < 1 || maximumPerCity > 32 || culturePerPeriod < 1 || culturePerPeriod > 100) throw new IllegalArgumentException("Invalid decoration limits"); bonus(productionBonus); }
    }
    public record ConcertRules(String profession, Set<String> instruments, Map<String, Long> inputs,
                               long minimumDurationMillis, long maximumDurationMillis, long noteGapMillis,
                               long maximumSilenceMillis, int minimumNotes, int maximumNotes, int distinctPitches,
                               int audience, int culture, double productionBonus, int durationPeriods, int cooldownPeriods) {
        public ConcertRules { identifier(profession); instruments = Set.copyOf(instruments); instruments.forEach(LifeDefinitions::item); inputs = costs(inputs);
            if (instruments.isEmpty() || minimumDurationMillis < 5000 || maximumDurationMillis < minimumDurationMillis || maximumDurationMillis > 3600000
                    || noteGapMillis < 200 || noteGapMillis > 10000 || maximumSilenceMillis < noteGapMillis || maximumSilenceMillis > maximumDurationMillis
                    || minimumNotes < 3 || maximumNotes < minimumNotes || maximumNotes > 256 || distinctPitches < 2 || distinctPitches > 25
                    || distinctPitches > minimumNotes || audience < 1 || audience > 16 || culture < 1 || culture > 10000
                    || durationPeriods < 1 || durationPeriods > 1000 || cooldownPeriods < 1 || cooldownPeriods > 1000
                    || minimumNotes * noteGapMillis > maximumDurationMillis || maximumNotes * maximumSilenceMillis < minimumDurationMillis) throw new IllegalArgumentException("Invalid concert rules"); bonus(productionBonus); }
    }
    public static LifeDefinitions defaults() {
        return new LifeDefinitions(6, 80, 240, 1_000_000_000, 0.30,
                Map.of("chef", new Profession("chef", "厨师"), "brewer", new Profession("brewer", "酿酒师"),
                        "artisan", new Profession("artisan", "装饰工匠"), "musician", new Profession("musician", "乐师")),
                Map.of("community", new Feast("community", "城市聚餐", "chef", Map.of("minecraft:bread", 8L), 12, 0.04, 3),
                        "cookery", new Feast("cookery", "森罗菜肴宴会", "chef", Map.of("kaleidoscopecookery:baozi", 4L, "kaleidoscopecookery:oil", 1L), 25, 0.08, 4),
                        "tavern", new Feast("tavern", "酒馆宴会", "brewer", Map.of("kaleidoscope_tavern:wine", 1L, "minecraft:bread", 4L), 20, 0.07, 4)),
                new DecorationRules("artisan", Set.of("kaleidoscope_deco:chair/oak", "kaleidoscope_deco:cook_stool/oak"),
                        Map.of("minecraft:oak_planks", 4L), Map.of("minecraft:bread", 1L), 8, 1, 0.005),
                new ConcertRules("musician", Set.of("minecraft:note_block", "musicplus:handheld_instrument"), Map.of("minecraft:bread", 8L),
                        60000, 300000, 750, 10000, 12, 120, 5, 1, 20, 0.06, 4, 4));
    }
    public Set<String> itemIds() {
        Set<String> result = new HashSet<>(); feasts.values().forEach(f -> result.addAll(f.inputs().keySet()));
        result.addAll(decorations.registrationCost().keySet()); result.addAll(decorations.upkeep().keySet());
        result.addAll(concerts.inputs().keySet()); result.addAll(concerts.instruments()); return Set.copyOf(result);
    }
    public static LifeDefinitions load(File file) throws Exception {
        YamlConfiguration y = new YamlConfiguration(); y.load(file);
        if (y.getInt("life-version") != 1) throw new IllegalArgumentException("Unsupported life-version");
        Map<String, Profession> professions = new LinkedHashMap<>(); ConfigurationSection ps = section(y, "professions");
        for (String id : ps.getKeys(false)) professions.put(id, new Profession(id, ps.getString(id)));
        Map<String, Feast> feasts = new LinkedHashMap<>(); ConfigurationSection fs = section(y, "feasts");
        for (String id : fs.getKeys(false)) { ConfigurationSection f = section(fs, id);
            feasts.put(id, new Feast(id, text(f, "name"), text(f, "profession"), amounts(f, "inputs"), f.getInt("culture"), f.getDouble("production-bonus"), f.getInt("duration-periods"))); }
        ConfigurationSection d = section(y, "decorations"), c = section(y, "concerts");
        return new LifeDefinitions(y.getInt("profession-cooldown-periods"), y.getInt("player-culture-per-period"), y.getInt("city-culture-per-period"),
                y.getLong("culture-limit"), y.getDouble("maximum-production-bonus"), professions, feasts,
                new DecorationRules(text(d, "profession"), new HashSet<>(d.getStringList("furniture-ids")), amounts(d, "registration-cost"), amounts(d, "upkeep"),
                        d.getInt("maximum-per-city"), d.getInt("culture-per-period"), d.getDouble("production-bonus")),
                new ConcertRules(text(c, "profession"), new HashSet<>(c.getStringList("instruments")), amounts(c, "inputs"), c.getLong("minimum-duration-millis"),
                        c.getLong("maximum-duration-millis"), c.getLong("note-gap-millis"), c.getLong("maximum-silence-millis"), c.getInt("minimum-notes"), c.getInt("maximum-notes"),
                        c.getInt("distinct-pitches"), c.getInt("audience"), c.getInt("culture"), c.getDouble("production-bonus"), c.getInt("duration-periods"), c.getInt("cooldown-periods")));
    }
    /** Creates a complete standalone life.yml only when absent. Never rewrites administrator definitions. */
    public static void writeDefaults(File file) throws Exception {
        if (file.exists()) return;
        File parent = file.getAbsoluteFile().getParentFile(); if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create life configuration directory");
        LifeDefinitions d = defaults(); YamlConfiguration y = new YamlConfiguration(); y.set("life-version", 1);
        y.set("profession-cooldown-periods", d.professionCooldownPeriods()); y.set("player-culture-per-period", d.playerCulturePerPeriod());
        y.set("city-culture-per-period", d.cityCulturePerPeriod()); y.set("culture-limit", d.cultureLimit()); y.set("maximum-production-bonus", d.maximumProductionBonus());
        d.professions().values().forEach(p -> y.set("professions." + p.id(), p.name()));
        for (Feast f : d.feasts().values()) { String p = "feasts." + f.id() + "."; y.set(p + "name", f.name()); y.set(p + "profession", f.profession());
            y.createSection(p + "inputs", f.inputs()); y.set(p + "culture", f.culture()); y.set(p + "production-bonus", f.productionBonus()); y.set(p + "duration-periods", f.durationPeriods()); }
        DecorationRules r = d.decorations(); y.set("decorations.profession", r.profession()); y.set("decorations.furniture-ids", r.furnitureIds().stream().sorted().toList());
        y.createSection("decorations.registration-cost", r.registrationCost()); y.createSection("decorations.upkeep", r.upkeep());
        y.set("decorations.maximum-per-city", r.maximumPerCity()); y.set("decorations.culture-per-period", r.culturePerPeriod()); y.set("decorations.production-bonus", r.productionBonus());
        ConcertRules c = d.concerts(); String p = "concerts."; y.set(p + "profession", c.profession()); y.set(p + "instruments", c.instruments().stream().sorted().toList()); y.createSection(p + "inputs", c.inputs());
        y.set(p + "minimum-duration-millis", c.minimumDurationMillis()); y.set(p + "maximum-duration-millis", c.maximumDurationMillis()); y.set(p + "note-gap-millis", c.noteGapMillis());
        y.set(p + "maximum-silence-millis", c.maximumSilenceMillis()); y.set(p + "minimum-notes", c.minimumNotes()); y.set(p + "maximum-notes", c.maximumNotes());
        y.set(p + "distinct-pitches", c.distinctPitches()); y.set(p + "audience", c.audience()); y.set(p + "culture", c.culture()); y.set(p + "production-bonus", c.productionBonus());
        y.set(p + "duration-periods", c.durationPeriods()); y.set(p + "cooldown-periods", c.cooldownPeriods()); y.save(file);
    }
    private static Map<String, Long> amounts(ConfigurationSection root, String key) {
        ConfigurationSection s = section(root, key); Map<String, Long> result = new HashMap<>();
        for (String id : s.getKeys(true)) { if(s.isConfigurationSection(id)){if(s.getConfigurationSection(id).getKeys(false).isEmpty())throw new IllegalArgumentException("Empty life amount mapping " + id);continue;}Object value = s.get(id); if (!(value instanceof Number n) || n.doubleValue() != n.longValue()) throw new IllegalArgumentException("Invalid life amount " + id); result.put(id, n.longValue()); }
        return result;
    }
    private static Map<String, Long> costs(Map<String, Long> values) {
        Map<String, Long> copy = Map.copyOf(values); if (copy.isEmpty()) throw new IllegalArgumentException("Life actions must consume real goods");
        copy.forEach((id, n) -> { item(id); if (n < 1 || n > 1000000) throw new IllegalArgumentException("Invalid life amount"); }); return copy;
    }
    private static ConfigurationSection section(ConfigurationSection s, String key) { ConfigurationSection r = s.getConfigurationSection(key); if (r == null) throw new IllegalArgumentException("Missing life section " + key); return r; }
    private static String text(ConfigurationSection s, String key) { String r = s.getString(key); if (r == null || r.isBlank()) throw new IllegalArgumentException("Missing life value " + key); return r; }
    private static void identifier(String value) { if (value == null || !value.matches("[a-z][a-z0-9_-]{0,47}")) throw new IllegalArgumentException("Invalid life id"); }
    private static void item(String value) { if (value == null || !value.matches("[a-z0-9._-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid life item id " + value); }
    private static void bonus(double value) { if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Invalid life production bonus"); }
}

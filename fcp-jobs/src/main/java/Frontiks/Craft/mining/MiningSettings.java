package Frontiks.Craft.mining;

import Frontiks.Craft.quests.QuestDef;
import Frontiks.Craft.work.HrabotaConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Чтение и валидация mining/quests-секций из hrabota.yml.
 * Все настройки имеют документированные дефолты; ошибки копятся в {@link #configErrors()}
 * и выводятся командой /miningnode validate.
 */
public final class MiningSettings {

    // mining
    private int cooldownSeconds = 45;          // дефолт совпадает со старой механикой (45с)
    private int nodeRewardCoins = 10;          // пример баланса, настраивается
    private List<String> defaultStages = List.of("TUFF", "STONE", "ANDESITE");
    private String defaultDepleted = "COBBLESTONE";
    private List<LootEntry> defaultLoot = builtinDefaultLoot();
    private List<String> editableMaterials = defaultPalette();

    // markers
    private boolean markersEnabled = true;
    private boolean showDepleted = false;
    private int markerRadius = 24;
    private int markerRefreshTicks = 40;
    private int pointsPerEdge = 4;

    // quests
    private boolean questsEnabled = true;
    private String timezone = "Europe/Moscow"; // безопасный документированный дефолт, не зависит от TZ процесса
    private int dailyCount = 2;
    private int weeklyCount = 2;
    private List<QuestDef> dailyPool = builtinDailyPool();
    private List<QuestDef> weeklyPool = builtinWeeklyPool();

    private final List<String> configErrors = new ArrayList<>();

    public MiningSettings() {
        reload();
    }

    public void reload() {
        configErrors.clear();
        FileConfiguration cfg = HrabotaConfig.get();
        if (cfg == null) {
            configErrors.add("hrabota.yml не загружен");
            return;
        }

        cooldownSeconds = Math.max(0, cfg.getInt("mining.cooldown-seconds", 45));
        nodeRewardCoins = Math.max(0, cfg.getInt("mining.node-reward-coins", 10));

        List<String> stages = upperList(cfg.getStringList("mining.stages"));
        if (stages.isEmpty()) stages = upperList(cfg.getStringList("mining.node.stages"));
        defaultStages = stages.isEmpty() ? List.of("TUFF", "STONE", "ANDESITE") : stages;

        defaultDepleted = cfg.getString("mining.depleted-material",
                cfg.getString("mining.node.depleted-material", "COBBLESTONE")).trim().toUpperCase();
        if (defaultDepleted.isEmpty()) defaultDepleted = "COBBLESTONE";

        defaultLoot = parseLoot(cfg.getMapList("mining.loot"), "mining.loot");
        if (defaultLoot.isEmpty()) defaultLoot = parseLoot(cfg.getMapList("mining.node.loot"), "mining.node.loot");
        if (defaultLoot.isEmpty()) defaultLoot = builtinDefaultLoot();

        List<String> palette = upperList(cfg.getStringList("mining.editable-materials"));
        if (!palette.isEmpty()) editableMaterials = palette;

        markersEnabled = cfg.getBoolean("mining.markers.enabled", true);
        showDepleted = cfg.getBoolean("mining.markers.show-depleted", false);
        markerRadius = Math.max(4, Math.min(128, cfg.getInt("mining.markers.radius", 24)));
        markerRefreshTicks = Math.max(10, Math.min(600, cfg.getInt("mining.markers.refresh-ticks", 40)));
        pointsPerEdge = Math.max(1, Math.min(16, cfg.getInt("mining.markers.points-per-edge", 4)));

        questsEnabled = cfg.getBoolean("quests.enabled", true);
        String tz = cfg.getString("quests.timezone", "Europe/Moscow");
        try {
            ZoneId.of(tz);
            timezone = tz.trim();
        } catch (Exception e) {
            configErrors.add("quests.timezone='" + tz + "' невалиден — используется Europe/Moscow");
            timezone = "Europe/Moscow";
        }
        dailyCount = Math.max(1, Math.min(7, cfg.getInt("quests.daily.count", 2)));
        weeklyCount = Math.max(1, Math.min(7, cfg.getInt("quests.weekly.count", 2)));
        if (cfg.contains("quests.daily.pool")) {
            dailyPool = parseQuestPool(cfg.getMapList("quests.daily.pool"), "quests.daily.pool");
        } else {
            dailyPool = builtinDailyPool();
        }
        if (cfg.contains("quests.weekly.pool")) {
            weeklyPool = parseQuestPool(cfg.getMapList("quests.weekly.pool"), "quests.weekly.pool");
        } else {
            weeklyPool = builtinWeeklyPool();
        }
    }

    public ZoneId zoneId() {
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("Europe/Moscow");
        }
    }

    public List<String> configErrors() { return List.copyOf(configErrors); }

    public int cooldownSeconds() { return cooldownSeconds; }
    public int nodeRewardCoins() { return nodeRewardCoins; }
    public List<String> defaultStages() { return defaultStages; }
    public String defaultDepleted() { return defaultDepleted; }
    /** Копия лута по умолчанию (узлы получают собственную редактируемую копию). */
    public List<LootEntry> defaultLoot() {
        List<LootEntry> copy = new ArrayList<>(defaultLoot.size());
        for (LootEntry e : defaultLoot) copy.add(new LootEntry(e.material, e.min, e.max, e.chance));
        return copy;
    }

    public void saveDefaultLoot(List<LootEntry> loot) {
        this.defaultLoot = new ArrayList<>(loot);
        FileConfiguration cfg = HrabotaConfig.get();
        List<Map<String, Object>> maps = new ArrayList<>();
        for (LootEntry e : loot) maps.add(e.toMap());
        cfg.set("mining.loot", maps);
        HrabotaConfig.save();
    }

    public void saveDefaultCooldownSeconds(int seconds) {
        this.cooldownSeconds = Math.max(0, seconds);
        FileConfiguration cfg = HrabotaConfig.get();
        cfg.set("mining.cooldown-seconds", this.cooldownSeconds);
        HrabotaConfig.save();
    }

    public void saveDefaultStages(List<String> stages, String depleted) {
        this.defaultStages = new ArrayList<>(stages);
        this.defaultDepleted = depleted;
        FileConfiguration cfg = HrabotaConfig.get();
        cfg.set("mining.stages", this.defaultStages);
        cfg.set("mining.depleted-material", this.defaultDepleted);
        HrabotaConfig.save();
    }
    public List<String> editableMaterials() { return editableMaterials; }

    public boolean markersEnabled() { return markersEnabled; }
    public boolean showDepletedMarkers() { return showDepleted; }
    public int markerRadius() { return markerRadius; }
    public int markerRefreshTicks() { return markerRefreshTicks; }
    public int pointsPerEdge() { return pointsPerEdge; }

    public boolean questsEnabled() { return questsEnabled; }
    public String timezone() { return timezone; }
    public int dailyCount() { return dailyCount; }
    public int weeklyCount() { return weeklyCount; }
    public List<QuestDef> dailyPool() { return dailyPool; }
    public List<QuestDef> weeklyPool() { return weeklyPool; }

    public void saveQuestPool(Frontiks.Craft.quests.QuestBoard.Period period, List<QuestDef> pool) {
        List<QuestDef> copy = new ArrayList<>(pool);
        FileConfiguration cfg = HrabotaConfig.get();
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (QuestDef d : copy) serialized.add(d.toMap());
        if (period == Frontiks.Craft.quests.QuestBoard.Period.DAILY) {
            this.dailyPool = copy;
            cfg.set("quests.daily.pool", serialized);
        } else {
            this.weeklyPool = copy;
            cfg.set("quests.weekly.pool", serialized);
        }
        HrabotaConfig.save();
    }

    public void saveQuestCount(Frontiks.Craft.quests.QuestBoard.Period period, int count) {
        int clamped = Math.max(1, Math.min(7, count));
        FileConfiguration cfg = HrabotaConfig.get();
        if (period == Frontiks.Craft.quests.QuestBoard.Period.DAILY) {
            this.dailyCount = clamped;
            cfg.set("quests.daily.count", clamped);
        } else {
            this.weeklyCount = clamped;
            cfg.set("quests.weekly.count", clamped);
        }
        HrabotaConfig.save();
    }

    /** id -> QuestDef для быстрого поиска во время начисления. */
    public Map<String, QuestDef> dailyDefs() { return toMap(dailyPool); }
    public Map<String, QuestDef> weeklyDefs() { return toMap(weeklyPool); }

    private static Map<String, QuestDef> toMap(List<QuestDef> pool) {
        Map<String, QuestDef> map = new LinkedHashMap<>();
        for (QuestDef d : pool) map.put(d.id, d);
        return map;
    }

    private List<LootEntry> parseLoot(List<Map<?, ?>> rawList, String where) {
        List<LootEntry> out = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            try {
                LootEntry e = LootEntry.fromMap(rawList.get(i));
                if (e.material.isBlank()) throw new IllegalArgumentException("пустой material");
                out.add(e);
            } catch (Exception ex) {
                configErrors.add(where + " #" + (i + 1) + ": " + ex.getMessage());
            }
        }
        return out;
    }

    private List<QuestDef> parseQuestPool(List<Map<?, ?>> rawList, String where) {
        List<QuestDef> out = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            try {
                out.add(QuestDef.fromRaw(rawList.get(i), where + " #" + (i + 1)));
            } catch (IllegalArgumentException ex) {
                configErrors.add(ex.getMessage());
            }
        }
        return out;
    }

    private static List<String> upperList(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (String s : in) {
            if (s == null) continue;
            String v = s.trim().toUpperCase();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static List<LootEntry> builtinDefaultLoot() {
        return List.of(
                new LootEntry("COBBLESTONE", 2, 4, 1.0),
                new LootEntry("COAL", 1, 1, 0.25)
        );
    }

    private static List<QuestDef> builtinDailyPool() {
        return List.of(
                new QuestDef("daily_cobble_32", QuestDef.Type.LOOT_AMOUNT, "COBBLESTONE", 32,
                        "Добыть 32 булыжника", "Соберите булыжник с добываемых узлов шахты", 150),
                new QuestDef("daily_coal_8", QuestDef.Type.LOOT_AMOUNT, "COAL", 8,
                        "Найти 8 угля", "Добудьте уголь при разработке узлов", 200),
                new QuestDef("daily_nodes_10", QuestDef.Type.NODES_COMPLETED, null, 10,
                        "Разработать 10 узлов", "Полностью пройдите все стадии 10 узлов", 180)
        );
    }

    private static List<QuestDef> builtinWeeklyPool() {
        return List.of(
                new QuestDef("weekly_cobble_256", QuestDef.Type.LOOT_AMOUNT, "COBBLESTONE", 256,
                        "Добыть 256 булыжника за неделю", "Крупная поставка булыжника с узлов шахты", 1000),
                new QuestDef("weekly_coal_48", QuestDef.Type.LOOT_AMOUNT, "COAL", 48,
                        "Найти 48 угля за неделю", "Соберите уголь при полном разрушении узлов", 1200),
                new QuestDef("weekly_nodes_75", QuestDef.Type.NODES_COMPLETED, null, 75,
                        "Разработать 75 узлов за неделю", "Полностью пройдите все стадии 75 узлов", 1500)
        );
    }

    private static List<String> defaultPalette() {
        return List.of("TUFF", "STONE", "ANDESITE", "DIORITE", "GRANITE", "DEEPSLATE",
                "COBBLED_DEEPSLATE", "COBBLESTONE", "NETHERRACK", "BLACKSTONE", "GRAVEL", "DIRT");
    }

}

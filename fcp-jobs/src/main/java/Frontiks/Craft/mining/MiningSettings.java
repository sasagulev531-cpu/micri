package Frontiks.Craft.mining;

import Frontiks.Craft.actions.ActionKindRegistry;
import Frontiks.Craft.api.FcpYamlStorage;
import Frontiks.Craft.api.LegacyFiles;
import Frontiks.Craft.jobs.JobRegistry;
import Frontiks.Craft.quests.QuestDef;
import Frontiks.Craft.quests.QuestStep;
import Frontiks.Craft.quests.v2.QuestV2Models;
import Frontiks.Craft.quests.v2.QuestsConfigParser;
import Frontiks.Craft.work.HrabotaConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Чтение и валидация:
 * - настроек узлов шахты из hrabota.yml;
 * - конфига заданий v2 (и совместимого v1) из plugins/FCP-Jobs/quests.yml.
 */
public final class MiningSettings {

    private final JavaPlugin plugin;
    private final ActionKindRegistry actionRegistry;
    private final JobRegistry jobRegistry;

    // mining
    private int cooldownSeconds = 45;
    private int nodeRewardCoins = 10;
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

    // quests v2
    private QuestV2Models.QuestsConfig questsConfigV2 = QuestV2Models.QuestsConfig.empty();

    // legacy v1 compatibility fields
    private boolean questsEnabled = true;
    private String timezone = "Europe/Moscow";
    private int dailyCount = 3;
    private int weeklyCount = 3;
    private List<QuestDef> dailyPool = builtinDailyPool();
    private List<QuestDef> weeklyPool = builtinWeeklyPool();

    private final List<String> configErrors = new ArrayList<>();

    public MiningSettings() {
        this(null, ActionKindRegistry.withBuiltins(), JobRegistry.withDefaultMiner());
    }

    public MiningSettings(JavaPlugin plugin) {
        this(plugin, ActionKindRegistry.withBuiltins(), JobRegistry.withDefaultMiner());
    }

    public MiningSettings(JavaPlugin plugin, ActionKindRegistry actionRegistry, JobRegistry jobRegistry) {
        this.plugin = plugin;
        this.actionRegistry = actionRegistry != null ? actionRegistry : ActionKindRegistry.withBuiltins();
        this.jobRegistry = jobRegistry != null ? jobRegistry : JobRegistry.withDefaultMiner();
        reload();
    }

    public ActionKindRegistry actionRegistry() { return actionRegistry; }
    public JobRegistry jobRegistry() { return jobRegistry; }
    public QuestV2Models.QuestsConfig questsConfigV2() { return questsConfigV2; }

    public void reload() {
        configErrors.clear();
        FileConfiguration cfg = HrabotaConfig.get();
        if (cfg != null) {
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
        }

        reloadQuestsConfig();
    }

    /**
     * Загружает plugins/FCP-Jobs/quests.yml через LegacyFiles.migrate + FcpYamlStorage.load.
     * Если quests.yml отсутствует — копирует эталонный quests.example.yml (при наличии не переписывает, R10).
     */
    private void reloadQuestsConfig() {
        QuestsConfigParser parser = new QuestsConfigParser(actionRegistry, jobRegistry);

        if (plugin != null) {
            try {
                File qFile = LegacyFiles.migrate(plugin, "quests.yml");
                // Также сохраняем справочный quests.example.yml рядом, если его ещё нет
                File exampleFile = new File(plugin.getDataFolder(), "quests.example.yml");
                if (!exampleFile.exists()) {
                    try (InputStream in = plugin.getResource("quests.example.yml")) {
                        if (in != null) {
                            Files.copy(in, exampleFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception ignored) {
                    }
                }

                if (!qFile.exists()) {
                    try (InputStream in = plugin.getResource("quests.example.yml")) {
                        if (in != null) {
                            Files.copy(in, qFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            plugin.saveResource("quests.yml", false);
                        }
                    }
                }

                // Загружаем через FcpYamlStorage (соблюдение конвенции FCP-Core §1) + читаем текст для точных путей ошибок
                FcpYamlStorage.load(plugin, qFile);
                String rawYaml = Files.readString(qFile.toPath(), StandardCharsets.UTF_8);
                QuestV2Models.ParseResult res = parser.parseYamlString(rawYaml, "quests.yml");
                applyParsedQuests(res);
                return;
            } catch (Exception e) {
                configErrors.add("Не удалось прочитать plugins/FCP-Jobs/quests.yml: " + e.getMessage());
            }
        }

        // Fallback для тестов без plugin-инстанса: читаем встроенный ресурс quests.example.yml
        try (InputStream in = MiningSettings.class.getClassLoader().getResourceAsStream("quests.example.yml")) {
            if (in != null) {
                String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                applyParsedQuests(parser.parseYamlString(yaml, "quests.example.yml"));
                return;
            }
        } catch (Exception ignored) {
        }

        questsConfigV2 = QuestV2Models.QuestsConfig.empty();
    }

    private void applyParsedQuests(QuestV2Models.ParseResult res) {
        this.questsConfigV2 = res.config();
        this.configErrors.addAll(res.errors());
        this.questsEnabled = questsConfigV2.enabled();
        this.timezone = questsConfigV2.timezone().getId();
        var dailyTpl = questsConfigV2.periods().get("daily");
        var weeklyTpl = questsConfigV2.periods().get("weekly");
        this.dailyCount = dailyTpl != null ? dailyTpl.count() : 3;
        this.weeklyCount = weeklyTpl != null ? weeklyTpl.count() : 3;
    }

    public ZoneId zoneId() {
        return questsConfigV2 != null ? questsConfigV2.timezone() : ZoneId.of("Europe/Moscow");
    }

    public List<String> configErrors() { return List.copyOf(configErrors); }

    public int cooldownSeconds() { return cooldownSeconds; }
    public int nodeRewardCoins() { return nodeRewardCoins; }
    public List<String> defaultStages() { return defaultStages; }
    public String defaultDepleted() { return defaultDepleted; }

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
                new QuestDef("daily_cobble_64", QuestDef.Type.LOOT_AMOUNT, "COBBLESTONE", 64,
                        "&fГорная порода", "Добудьте булыжник на смене в шахте", 200),
                new QuestDef("daily_coal_24", QuestDef.Type.LOOT_AMOUNT, "COAL", 24,
                        "&fТопливо для печей", "Добудьте уголь из узлов шахты", 300),
                new QuestDef("daily_nodes_15", QuestDef.Type.NODES_COMPLETED, null, 15,
                        "&fУдарник смены", "Полностью разработайте узлы шахты", 400)
        );
    }

    private static List<QuestDef> builtinWeeklyPool() {
        return List.of(
                new QuestDef(
                        "weekly_iron_chain",
                        "&6&lМастер железа",
                        List.of("&7Добудьте руду в шахте, переплавьте и скуйте блоки!"),
                        "IRON_INGOT",
                        true,
                        List.of(
                                new QuestStep(QuestDef.Type.LOOT_AMOUNT, "RAW_IRON", 64, true,
                                        "Добыть 64 рудного железа на смене в шахте"),
                                new QuestStep(QuestDef.Type.SMELT_ITEM, "IRON_INGOT", 64, false,
                                        "Переплавить 64 железных слитка в печи"),
                                new QuestStep(QuestDef.Type.CRAFT_ITEM, "IRON_BLOCK", 7, false,
                                        "Скрафтить 7 железных блоков")
                        ),
                        3000,
                        List.of("DIAMOND 2"),
                        List.of()
                )
        );
    }

    private static List<String> defaultPalette() {
        return List.of("TUFF", "STONE", "ANDESITE", "DIORITE", "GRANITE", "DEEPSLATE",
                "COBBLED_DEEPSLATE", "COBBLESTONE", "NETHERRACK", "BLACKSTONE", "GRAVEL", "DIRT");
    }
}

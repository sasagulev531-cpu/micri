package Frontiks.Craft.quests;

import Frontiks.Craft.api.EconomyService;
import Frontiks.Craft.api.FcpServices;
import Frontiks.Craft.api.FcpYamlStorage;
import Frontiks.Craft.api.LegacyFiles;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.work.HrabotaConfig;
import Frontiks.Craft.work.WorkManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Управление активными заданиями и цепочками этапов игрока (mining-quests.yml).
 */
public final class QuestManager {

    public record ResolvedQuest(QuestBoard.Period period, QuestDef def, QuestAssignment state) {}

    private static final class Bucket {
        String period = "";
        List<QuestAssignment> quests = new ArrayList<>();
    }

    private final JavaPlugin plugin;
    private final MiningSettings settings;
    private final ReentrantLock lock = new ReentrantLock();
    private File file;
    private FileConfiguration config;
    private final Map<UUID, Map<QuestBoard.Period, Bucket>> data = new HashMap<>();

    public QuestManager(JavaPlugin plugin, MiningSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void init() {
        lock.lock();
        try {
            file = LegacyFiles.migrate(plugin, "mining-quests.yml");
            if (!file.exists()) {
                config = new YamlConfiguration();
                config.set("version", 2);
                FcpYamlStorage.save(plugin, file, config);
            } else {
                config = FcpYamlStorage.load(plugin, file);
                if (!config.contains("version")) config.set("version", 2);
            }
            loadAll();
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось инициализировать mining-quests.yml: " + e);
            if (config == null) config = new YamlConfiguration();
        } finally {
            lock.unlock();
        }
    }

    private void loadAll() {
        data.clear();
        if (config == null || !config.isConfigurationSection("players")) return;
        for (String uuidRaw : config.getConfigurationSection("players").getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidRaw);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Map<QuestBoard.Period, Bucket> perPeriod = new HashMap<>();
            perPeriod.put(QuestBoard.Period.DAILY, readBucket(uuidRaw, "daily"));
            perPeriod.put(QuestBoard.Period.WEEKLY, readBucket(uuidRaw, "weekly"));
            data.put(uuid, perPeriod);
        }
    }

    private Bucket readBucket(String uuidRaw, String section) {
        Bucket b = new Bucket();
        ConfigurationSection sec = config.getConfigurationSection("players." + uuidRaw + "." + section);
        if (sec == null) return b;
        b.period = sec.getString("period", "");
        for (Map<?, ?> raw : sec.getMapList("quests")) {
            b.quests.add(QuestAssignment.fromMap(raw));
        }
        return b;
    }

    public void shutdown() {
        saveAll();
    }

    private void saveAll() {
        lock.lock();
        try {
            if (config == null || file == null) return;
            config.set("players", null);
            for (Map.Entry<UUID, Map<QuestBoard.Period, Bucket>> e : data.entrySet()) {
                String path = "players." + e.getKey();
                writeBucket(path + ".daily", e.getValue().get(QuestBoard.Period.DAILY));
                writeBucket(path + ".weekly", e.getValue().get(QuestBoard.Period.WEEKLY));
            }
            FcpYamlStorage.save(plugin, file, config);
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось сохранить mining-quests.yml: " + e);
        } finally {
            lock.unlock();
        }
    }

    private void writeBucket(String path, Bucket bucket) {
        if (bucket == null) return;
        config.set(path, null);
        config.set(path + ".period", bucket.period);
        List<Map<String, Object>> list = new ArrayList<>();
        for (QuestAssignment a : bucket.quests) list.add(a.toMap());
        config.set(path + ".quests", list);
    }

    public List<ResolvedQuest> resolvedQuests(Player player) {
        lock.lock();
        try {
            ensureAssignments(player.getUniqueId(), System.currentTimeMillis());
            return resolveAll(player.getUniqueId());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Игрок берёт задание в GUI (ЛКМ по карточке задания).
     * До взятия прогресс по заданиям с require-accept=true не идёт!
     */
    public boolean acceptQuest(Player player, String questId) {
        if (questId == null || questId.isBlank()) return false;
        UUID uuid = player.getUniqueId();
        lock.lock();
        try {
            ensureAssignments(uuid, System.currentTimeMillis());
            Map<QuestBoard.Period, Bucket> perPeriod = data.getOrDefault(uuid, Map.of());
            for (QuestBoard.Period period : QuestBoard.Period.values()) {
                Bucket bucket = perPeriod.get(period);
                if (bucket == null) continue;
                Map<String, QuestDef> defs = period == QuestBoard.Period.DAILY ? settings.dailyDefs() : settings.weeklyDefs();
                for (QuestAssignment a : bucket.quests) {
                    if (a.id.equals(questId)) {
                        QuestDef def = defs.get(a.id);
                        if (def == null || a.completed || !def.requireAccept || a.accepted) {
                            return false;
                        }
                        a.accepted = true;
                        saveAll();
                        player.playSound(player.getLocation(), "minecraft:entity.experience_orb.pickup", 1.0f, 1.15f);
                        player.sendMessage(Frontiks.Craft.utils.color.color(
                                Frontiks.Craft.utils.Msg.PREFIX + "&aВы взяли задание: &f" + def.title));
                        QuestStep first = def.steps.get(Math.min(a.stepIndex, def.steps.size() - 1));
                        player.sendMessage(Frontiks.Craft.utils.color.color(
                                "&8 » &eТекущий этап (1/" + def.steps.size() + "): &f" + first.title));
                        return true;
                    }
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ события прогресса

    public void applyBlockBreak(Player player, String blockMaterial, int brokenCount) {
        if (!settings.questsEnabled() || blockMaterial == null || brokenCount <= 0) return;
        boolean working = WorkManager.isWorking(player) && HrabotaConfig.contains(player.getLocation());
        dispatchEvent(player, new QuestBoard.QuestEvent(
                working,
                Map.of(blockMaterial.toUpperCase(Locale.ROOT), brokenCount),
                Map.of(), Map.of(), Map.of(), 0, 0
        ));
    }

    public void applyBlockBreakAndCoins(Player player, String blockMaterial, int coinsEarned) {
        if (!settings.questsEnabled()) return;
        boolean working = WorkManager.isWorking(player) && HrabotaConfig.contains(player.getLocation());
        Map<String, Integer> broken = (blockMaterial == null || blockMaterial.isBlank())
                ? Map.of()
                : Map.of(blockMaterial.toUpperCase(Locale.ROOT), 1);
        dispatchEvent(player, new QuestBoard.QuestEvent(
                working, broken, Map.of(), Map.of(), Map.of(), 0, Math.max(0, coinsEarned)
        ));
    }

    public void applyNodeCompletion(Player player, String brokenStageMaterial,
                                    Map<String, Integer> lootCounts, int nodesCompleted, int coinsEarned) {
        if (!settings.questsEnabled()) return;
        boolean working = WorkManager.isWorking(player) && HrabotaConfig.contains(player.getLocation());
        dispatchEvent(player, QuestBoard.QuestEvent.mineCompletion(
                working, brokenStageMaterial, lootCounts, nodesCompleted, Math.max(0, coinsEarned)
        ));
    }

    public void applyItemSmelted(Player player, String itemMaterial, int count) {
        if (!settings.questsEnabled() || itemMaterial == null || count <= 0) return;
        boolean working = WorkManager.isWorking(player) && HrabotaConfig.contains(player.getLocation());
        dispatchEvent(player, new QuestBoard.QuestEvent(
                working,
                Map.of(), Map.of(),
                Map.of(itemMaterial.toUpperCase(Locale.ROOT), count),
                Map.of(), 0, 0
        ));
    }

    public void applyItemCrafted(Player player, String itemMaterial, int count) {
        if (!settings.questsEnabled() || itemMaterial == null || count <= 0) return;
        boolean working = WorkManager.isWorking(player) && HrabotaConfig.contains(player.getLocation());
        dispatchEvent(player, new QuestBoard.QuestEvent(
                working,
                Map.of(), Map.of(), Map.of(),
                Map.of(itemMaterial.toUpperCase(Locale.ROOT), count),
                0, 0
        ));
    }

    private void dispatchEvent(Player player, QuestBoard.QuestEvent event) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            ensureAssignments(uuid, now);
            Map<QuestBoard.Period, Bucket> perPeriod = data.computeIfAbsent(uuid, k -> new HashMap<>());
            boolean anyChanged = false;

            for (QuestBoard.Period period : QuestBoard.Period.values()) {
                Bucket bucket = perPeriod.computeIfAbsent(period, k -> new Bucket());
                Map<String, QuestDef> defs = period == QuestBoard.Period.DAILY ? settings.dailyDefs() : settings.weeklyDefs();
                QuestBoard.ApplyResult result = QuestBoard.applyEvent(
                        bucket.quests, defs, event,
                        (questId, reward) -> payoutFull(player, defs.get(questId)));
                if (!result.completedNow().isEmpty() || !result.paidNow().isEmpty()
                        || !result.payFailed().isEmpty() || !result.inProgress().isEmpty()
                        || !result.stepAdvances().isEmpty()) {
                    anyChanged = true;
                }
                report(player, result, defs);
            }
            if (anyChanged) {
                saveAll();
            }
        } finally {
            lock.unlock();
        }
    }

    public void syncPoolsAndTopUp() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            for (UUID uuid : new ArrayList<>(data.keySet())) {
                ensureAssignments(uuid, now);
            }
            saveAll();
        } finally {
            lock.unlock();
        }
    }

    public QuestBoard.ApplyResult retryPendingPayouts(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            ensureAssignments(uuid, now);
            Map<QuestBoard.Period, Bucket> perPeriod = data.getOrDefault(uuid, Map.of());
            List<String> paid = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (QuestBoard.Period period : QuestBoard.Period.values()) {
                Bucket bucket = perPeriod.get(period);
                if (bucket == null) continue;
                Map<String, QuestDef> defs = period == QuestBoard.Period.DAILY ? settings.dailyDefs() : settings.weeklyDefs();
                QuestBoard.ApplyResult r = QuestBoard.retryPendingPayouts(bucket.quests, defs,
                        (questId, reward) -> payoutFull(player, defs.get(questId)));
                paid.addAll(r.paidNow());
                failed.addAll(r.payFailed());
            }
            saveAll();
            return new QuestBoard.ApplyResult(List.of(), paid, failed, List.of(), List.of());
        } finally {
            lock.unlock();
        }
    }

    private void report(Player player, QuestBoard.ApplyResult result, Map<String, QuestDef> defs) {
        for (QuestBoard.StepAdvance adv : result.stepAdvances()) {
            QuestDef def = defs.get(adv.questId());
            String qTitle = def == null ? adv.questId() : def.title;
            player.playSound(player.getLocation(), "minecraft:entity.player.levelup", 0.8f, 1.35f);
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&aЭтап " + adv.completedStepNumber() + "/" + adv.totalSteps()
                            + " задания «&f" + qTitle + "&a» выполнен!"));
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    "&8 » &eСледующий этап: &f" + adv.nextStepTitle()));
        }
        for (String id : result.completedNow()) {
            QuestDef def = defs.get(id);
            String title = def == null ? id : def.title;
            player.playSound(player.getLocation(), "minecraft:entity.player.levelup", 1.0f, 1.0f);
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&a&lЗадание выполнено: &f" + title));
        }
        for (String id : result.paidNow()) {
            QuestDef def = defs.get(id);
            if (def == null || def.reward <= 0) continue;
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&6+" + def.reward + " монет &7за задание &f" + def.title));
        }
        for (String id : result.payFailed()) {
            QuestDef def = defs.get(id);
            String title = def == null ? id : def.title;
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&cНе удалось выдать монеты за «" + title
                            + "». Нажмите «Проверить награды» в меню задач позже."));
        }
    }

    /** Выдаёт полную награду задания: монеты + предметы + консольные команды. */
    private boolean payoutFull(Player player, QuestDef def) {
        if (def == null) return false;
        if (def.reward > 0) {
            try {
                EconomyService economy = FcpServices.require(EconomyService.class);
                economy.addCoins(player.getUniqueId(), def.reward);
            } catch (Throwable t) {
                plugin.getLogger().severe("Ошибка начисления " + def.reward + " монет за задание "
                        + def.id + " игроку " + player.getName() + ": " + t);
                return false;
            }
        }
        // Предметы в награду ("DIAMOND 2")
        for (String spec : def.rewardItems) {
            if (spec == null || spec.isBlank()) continue;
            String[] parts = spec.trim().split("\\s+");
            Material mat = Material.matchMaterial(parts[0].toUpperCase(Locale.ROOT));
            int amt = parts.length >= 2 ? QuestDef.num(parts[1], 1) : 1;
            if (mat != null && mat.isItem() && amt > 0) {
                int left = amt;
                while (left > 0) {
                    int stackSize = Math.min(mat.getMaxStackSize(), left);
                    left -= stackSize;
                    for (ItemStack overflow : player.getInventory().addItem(new ItemStack(mat, stackSize)).values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), overflow);
                    }
                }
            }
        }
        // Консольные команды
        for (String cmd : def.rewardCommands) {
            if (cmd == null || cmd.isBlank()) continue;
            String parsed = cmd.replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString());
            if (parsed.startsWith("/")) parsed = parsed.substring(1);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
        return true;
    }

    private void ensureAssignments(UUID uuid, long nowMs) {
        var zone = settings.zoneId();
        Map<QuestBoard.Period, Bucket> perPeriod = data.computeIfAbsent(uuid, k -> new HashMap<>());

        ensureBucket(perPeriod.computeIfAbsent(QuestBoard.Period.DAILY, k -> new Bucket()),
                QuestBoard.periodKey(QuestBoard.Period.DAILY, zone, nowMs),
                settings.dailyPool(), settings.dailyDefs(), settings.dailyCount());

        ensureBucket(perPeriod.computeIfAbsent(QuestBoard.Period.WEEKLY, k -> new Bucket()),
                QuestBoard.periodKey(QuestBoard.Period.WEEKLY, zone, nowMs),
                settings.weeklyPool(), settings.weeklyDefs(), settings.weeklyCount());
    }

    private void ensureBucket(Bucket bucket, String currentKey,
                              List<QuestDef> pool, Map<String, QuestDef> defs, int targetCount) {
        Random rng = new Random();
        if (!currentKey.equals(bucket.period)) {
            bucket.period = currentKey;
            bucket.quests = pool.isEmpty()
                    ? new ArrayList<>()
                    : new ArrayList<>(QuestBoard.assign(pool, targetCount, rng));
            return;
        }
        bucket.quests.removeIf(a -> !defs.containsKey(a.id));
        for (QuestAssignment a : bucket.quests) {
            QuestDef d = defs.get(a.id);
            if (d != null && !d.requireAccept) {
                a.accepted = true;
            }
        }
        int want = Math.min(targetCount, pool.size());
        if (bucket.quests.size() < want) {
            Set<String> existing = new HashSet<>();
            for (QuestAssignment a : bucket.quests) existing.add(a.id);
            List<QuestDef> candidates = new ArrayList<>();
            for (QuestDef d : pool) {
                if (!existing.contains(d.id)) candidates.add(d);
            }
            bucket.quests.addAll(QuestBoard.assign(candidates, want - bucket.quests.size(), rng));
        }
    }

    private List<ResolvedQuest> resolveAll(UUID uuid) {
        List<ResolvedQuest> out = new ArrayList<>();
        Map<QuestBoard.Period, Bucket> perPeriod = data.getOrDefault(uuid, Map.of());
        for (QuestBoard.Period period : new QuestBoard.Period[]{QuestBoard.Period.DAILY, QuestBoard.Period.WEEKLY}) {
            Bucket bucket = perPeriod.get(period);
            if (bucket == null) continue;
            Map<String, QuestDef> defs = period == QuestBoard.Period.DAILY ? settings.dailyDefs() : settings.weeklyDefs();
            for (QuestAssignment state : bucket.quests) {
                QuestDef def = defs.get(state.id);
                if (def != null) out.add(new ResolvedQuest(period, def, state));
            }
        }
        return out;
    }

    public String resetText(QuestBoard.Period period) {
        var zone = settings.zoneId();
        long now = System.currentTimeMillis();
        long resets = QuestBoard.periodResetsAt(period, zone, now);
        return QuestBoard.formatInstant(resets, zone);
    }
}

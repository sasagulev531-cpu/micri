package Frontiks.Craft.quests;

import Frontiks.Craft.api.EconomyService;
import Frontiks.Craft.api.FcpServices;
import Frontiks.Craft.api.FcpYamlStorage;
import Frontiks.Craft.api.LegacyFiles;
import Frontiks.Craft.mining.MiningSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Хранение и начисление заданий шахтёра (mining-quests.yml).
 * Прогресс привязан к игроку (UUID), переживает relog/restart, награда — ровно один раз.
 */
public final class QuestManager {

    /** Отображаемое состояние задания для GUI/сообщений. */
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
    /** uuid -> period(DAILY/WEEKLY) -> bucket */
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
                config.set("version", 1);
                FcpYamlStorage.save(plugin, file, config);
            } else {
                config = FcpYamlStorage.load(plugin, file);
                if (!config.contains("version")) config.set("version", 1);
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
                plugin.getLogger().warning("mining-quests.yml: пропущен некорректный UUID " + uuidRaw);
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

    /** Возвращает отображение заданий игрока на текущий период (назначает новые при смене периода или нехватке). */
    public List<ResolvedQuest> resolvedQuests(Player player) {
        lock.lock();
        try {
            ensureAssignments(player.getUniqueId(), System.currentTimeMillis());
            return resolveAll(player.getUniqueId());
        } finally {
            lock.unlock();
        }
    }

    /** Начисляет прогресс за ломку блока/стадии в шахте (для типа СЛОМАТЬ / BREAK_BLOCK). */
    public void applyBlockBreak(Player player, String blockMaterial, int brokenCount) {
        if (!settings.questsEnabled() || blockMaterial == null || brokenCount <= 0) return;
        applyEvent(player, Map.of(blockMaterial.toUpperCase(), brokenCount), Map.of(), Map.of(), 0);
    }

    /** Начисляет прогресс за получение предмета (например, из печи для типа ПОЛУЧИТЬ / OBTAIN_ITEM). */
    public void applyItemObtained(Player player, String itemMaterial, int count) {
        if (!settings.questsEnabled() || itemMaterial == null || count <= 0) return;
        applyEvent(player, Map.of(), Map.of(), Map.of(itemMaterial.toUpperCase(), count), 0);
    }

    /** Совместимый вызов завершения узла. */
    public void applyNodeCompletion(Player player, Map<String, Integer> lootCounts, int nodesCompleted) {
        applyNodeCompletion(player, null, lootCounts, nodesCompleted);
    }

    /** Полное завершение узла: засчитывает сломанный блок последней стадии, добытый лут, полученные предметы и сам узел. */
    public void applyNodeCompletion(Player player, String brokenStageMaterial,
                                    Map<String, Integer> lootCounts, int nodesCompleted) {
        if (!settings.questsEnabled()) return;
        Map<String, Integer> broken = (brokenStageMaterial == null || brokenStageMaterial.isBlank())
                ? Map.of()
                : Map.of(brokenStageMaterial.toUpperCase(), 1);
        Map<String, Integer> safeLoot = lootCounts == null ? Map.of() : lootCounts;
        applyEvent(player, broken, safeLoot, safeLoot, nodesCompleted);
    }

    private void applyEvent(Player player,
                            Map<String, Integer> brokenBlocks,
                            Map<String, Integer> lootCounts,
                            Map<String, Integer> obtainedItems,
                            int nodesCompleted) {
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
                QuestBoard.ApplyResult result = QuestBoard.applyProgress(
                        bucket.quests, defs, brokenBlocks, lootCounts, obtainedItems, nodesCompleted,
                        (questId, reward) -> payout(uuid, questId, reward));
                if (!result.completedNow().isEmpty() || !result.paidNow().isEmpty()
                        || !result.payFailed().isEmpty() || !result.inProgress().isEmpty()) {
                    anyChanged = true;
                }
                report(player, period, result, defs);
            }
            if (anyChanged) {
                saveAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Синхронизирует активные назначения всех игроков с обновлённым пулом (удаляет удалённые и добирает недостающие). */
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

    /** Принудительно перевыдаёт задачи всем игрокам заново из текущего пула (/quests reroll). */
    public void forceRerollAll() {
        lock.lock();
        try {
            data.clear();
            saveAll();
        } finally {
            lock.unlock();
        }
    }

    /** Повторная попытка выплаты наград за уже выполненные, но неоплаченные задания (/quests claim). */
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
                        (questId, reward) -> payout(uuid, questId, reward));
                paid.addAll(r.paidNow());
                failed.addAll(r.payFailed());
            }
            saveAll();
            return new QuestBoard.ApplyResult(List.of(), paid, failed, List.of());
        } finally {
            lock.unlock();
        }
    }

    private void report(Player player, QuestBoard.Period period, QuestBoard.ApplyResult result,
                        Map<String, QuestDef> defs) {
        for (String id : result.completedNow()) {
            QuestDef def = defs.get(id);
            String title = def == null ? id : def.title;
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&aЗадание выполнено: &f" + title));
        }
        for (String id : result.paidNow()) {
            QuestDef def = defs.get(id);
            if (def == null) continue;
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&f+" + def.reward + " монет &7за задание &f" + def.title));
        }
        for (String id : result.payFailed()) {
            QuestDef def = defs.get(id);
            String title = def == null ? id : def.title;
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&cНе удалось начислить награду за «" + title
                            + "». Попробуйте /quests claim позже."));
        }
    }

    /** true = деньги начислены. Любая ошибка логируется, игроку об успехе не сообщаем. */
    private boolean payout(UUID uuid, String questId, int reward) {
        if (reward <= 0) return true;
        try {
            EconomyService economy = FcpServices.require(EconomyService.class);
            economy.addCoins(uuid, reward);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().severe("Ошибка начисления награды за задание " + questId
                    + " игроку " + uuid + ": " + t);
            return false;
        }
    }

    /** Гарантирует, что у игрока есть назначения на текущие суточный/недельный периоды и добирает новые при добавлении в пул. */
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
        // Убираем удалённые из конфига задания
        bucket.quests.removeIf(a -> !defs.containsKey(a.id));
        // Добираем недостающие слоты, если админ добавил новые задачи в пул или увеличил лимит
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

    /** Есть ли у игрока невыплаченные выполненные задания (для кнопки /quests claim). */
    public boolean hasPendingPayouts(Player player) {
        lock.lock();
        try {
            ensureAssignments(player.getUniqueId(), System.currentTimeMillis());
            Map<QuestBoard.Period, Bucket> perPeriod = data.getOrDefault(player.getUniqueId(), Map.of());
            for (Bucket bucket : perPeriod.values()) {
                for (QuestAssignment a : bucket.quests) {
                    if (a.completed && !a.paid) return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Текст дедлайна для GUI. */
    public String resetText(QuestBoard.Period period) {
        var zone = settings.zoneId();
        long now = System.currentTimeMillis();
        long resets = QuestBoard.periodResetsAt(period, zone, now);
        return QuestBoard.formatInstant(resets, zone);
    }
}

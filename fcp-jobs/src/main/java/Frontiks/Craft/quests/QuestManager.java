package Frontiks.Craft.quests;

import Frontiks.Craft.actions.ActionKindRegistry;
import Frontiks.Craft.actions.ActionReporter;
import Frontiks.Craft.actions.JobAction;
import Frontiks.Craft.api.EconomyService;
import Frontiks.Craft.api.FcpServices;
import Frontiks.Craft.api.FcpYamlStorage;
import Frontiks.Craft.api.LegacyFiles;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.quests.v2.CommandWhitelistGuard;
import Frontiks.Craft.quests.v2.PlayerQuestProfile;
import Frontiks.Craft.quests.v2.QuestEngineV2;
import Frontiks.Craft.quests.v2.QuestV2Models;
import Frontiks.Craft.quests.v2.RewardHandlerRegistry;
import Frontiks.Craft.utils.DebouncedSaver;
import Frontiks.Craft.work.HrabotaConfig;
import Frontiks.Craft.work.WorkManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Менеджер заданий v2 (mining-quests.yml):
 * - Единая точка приёма событий через ActionReporter (R2);
 * - Машина состояний QuestEngineV2 (R4);
 * - Дебаунс записи <= 1 раз в 5 с + синхронный flush в onDisable (§3.5);
 * - Фоновые повторы невыплаченных наград каждые 30 с (до 30 мин, R4);
 * - Защита reward-команд через CommandWhitelistGuard (R8.1).
 */
public final class QuestManager {

    private final JavaPlugin plugin;
    private final MiningSettings settings;
    private final RewardHandlerRegistry rewardRegistry = new RewardHandlerRegistry();
    private final ActionReporter actionReporter;
    private final ReentrantLock lock = new ReentrantLock();
    private final Random rng = new Random();

    private File file;
    private FileConfiguration config;
    private DebouncedSaver saver;
    private BukkitTask retryTask;

    private final Map<UUID, PlayerQuestProfile> profiles = new HashMap<>();

    public QuestManager(JavaPlugin plugin, MiningSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.actionReporter = new ActionReporter(this::handleAction);
    }

    public ActionReporter actionReporter() {
        return actionReporter;
    }

    public RewardHandlerRegistry rewardRegistry() {
        return rewardRegistry;
    }

    public void init() {
        lock.lock();
        try {
            file = LegacyFiles.migrate(plugin, "mining-quests.yml");
            if (!file.exists()) {
                config = new YamlConfiguration();
                config.set("version", 3);
                FcpYamlStorage.save(plugin, file, config);
            } else {
                config = FcpYamlStorage.load(plugin, file);
                config.set("version", 3);
            }
            loadAll();
            saver = new DebouncedSaver(plugin, 100L, this::saveAllNow);
            // Фоновый повтор невыплаченных наград и зарплат смены каждые 30 секунд (600 тиков, R4/R7)
            retryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBackgroundRetries, 600L, 600L);
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось инициализировать mining-quests.yml: " + e);
            if (config == null) config = new YamlConfiguration();
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        if (retryTask != null) {
            retryTask.cancel();
            retryTask = null;
        }
        if (saver != null) {
            saver.flushNow();
        } else {
            saveAllNow();
        }
    }

    private void markDirty() {
        if (saver != null) {
            saver.markDirty();
        } else {
            saveAllNow();
        }
    }

    private void loadAll() {
        profiles.clear();
        if (config == null || !config.isConfigurationSection("players")) return;
        ConfigurationSection playersSec = config.getConfigurationSection("players");
        if (playersSec == null) return;
        for (String uuidRaw : playersSec.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidRaw);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection pSec = playersSec.getConfigurationSection(uuidRaw);
            if (pSec != null) {
                profiles.put(uuid, PlayerQuestProfile.fromMap(sectionToMap(pSec)));
            }
        }
    }

    private static Map<String, Object> sectionToMap(ConfigurationSection sec) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : sec.getKeys(false)) {
            Object val = sec.get(key);
            if (val instanceof ConfigurationSection nested) {
                out.put(key, sectionToMap(nested));
            } else {
                out.put(key, val);
            }
        }
        return out;
    }

    private void saveAllNow() {
        lock.lock();
        try {
            if (config == null || file == null) return;
            config.set("version", 3);
            config.set("players", null);
            for (Map.Entry<UUID, PlayerQuestProfile> e : profiles.entrySet()) {
                config.set("players." + e.getKey(), e.getValue().toMap());
            }
            FcpYamlStorage.save(plugin, file, config);
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось сохранить mining-quests.yml: " + e);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ представление и взятие для GUI

    public List<QuestEngineV2.QuestView> viewsFor(Player player) {
        lock.lock();
        try {
            PlayerQuestProfile profile = profiles.computeIfAbsent(player.getUniqueId(), k -> new PlayerQuestProfile());
            QuestEngineV2.PlayerContext ctx = contextOf(player);
            boolean synced = QuestEngineV2.ensureSynced(profile, settings.questsConfigV2(), System.currentTimeMillis(), rng);
            if (synced) markDirty();
            return QuestEngineV2.buildViews(profile, settings.questsConfigV2(), ctx, System.currentTimeMillis(), rng);
        } finally {
            lock.unlock();
        }
    }

    public boolean acceptQuest(Player player, String questId) {
        if (player == null || questId == null || questId.isBlank()) return false;
        lock.lock();
        try {
            PlayerQuestProfile profile = profiles.computeIfAbsent(player.getUniqueId(), k -> new PlayerQuestProfile());
            QuestV2Models.QuestsConfig qcfg = settings.questsConfigV2();
            boolean ok = QuestEngineV2.acceptQuest(profile, qcfg, contextOf(player), questId, System.currentTimeMillis(), rng);
            if (ok) {
                markDirty();
                QuestV2Models.QuestSpec spec = qcfg.questsById().get(questId);
                String title = spec != null ? spec.title() : questId;
                player.playSound(player.getLocation(), "minecraft:entity.experience_orb.pickup", 1.0f, 1.15f);
                player.sendMessage(Frontiks.Craft.utils.color.color(
                        Frontiks.Craft.utils.Msg.PREFIX + "&aВы взяли задание: &f" + title));
                if (spec != null && !spec.steps().isEmpty()) {
                    player.sendMessage(Frontiks.Craft.utils.color.color(
                            "&8 » &eТекущий этап (1/" + spec.steps().size() + "): &f" + spec.steps().get(0).name()));
                }
            }
            return ok;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ обработка событий ActionReporter (R2)

    private void handleAction(UUID playerId, Player onlinePlayer, JobAction action) {
        QuestV2Models.QuestsConfig qcfg = settings.questsConfigV2();
        if (!qcfg.enabled()) return;

        lock.lock();
        try {
            PlayerQuestProfile profile = profiles.computeIfAbsent(playerId, k -> new PlayerQuestProfile());
            QuestEngineV2.PlayerContext ctx = onlinePlayer != null
                    ? contextOf(onlinePlayer)
                    : new QuestEngineV2.PlayerContext(action.jobId(), perm -> true);

            QuestEngineV2.EngineResult result = QuestEngineV2.applyAction(
                    profile, qcfg, ctx, action, System.currentTimeMillis(), rng,
                    spec -> payoutFull(playerId, onlinePlayer, spec, qcfg)
            );

            if (result.dirty()) {
                markDirty();
            }
            if (onlinePlayer != null) {
                reportToPlayer(onlinePlayer, result, qcfg);
            }
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ обёртки обратной совместимости (R2)

    public void applyBlockBreak(Player player, String blockMaterial, int brokenCount) {
        applyBlockBreak(player, blockMaterial, brokenCount, player != null ? player.getLocation() : null);
    }

    public void applyBlockBreak(Player player, String blockMaterial, int brokenCount, Location loc) {
        if (player == null || blockMaterial == null || brokenCount <= 0) return;
        boolean working = WorkManager.isWorking(player) && HrabotaConfig.contains(loc != null ? loc : player.getLocation());
        String job = WorkManager.currentJobId(player);
        actionReporter.report(player, ActionKindRegistry.BREAK, blockMaterial, brokenCount, loc, working, job);
        if (working) {
            actionReporter.report(player, ActionKindRegistry.JOB_ACTION, null, brokenCount, loc, true, job);
        }
    }

    public void applyBlockBreakAndCoins(Player player, String blockMaterial, int coinsEarned) {
        applyBlockBreakAndCoins(player, blockMaterial, coinsEarned, player != null ? player.getLocation() : null);
    }

    public void applyBlockBreakAndCoins(Player player, String blockMaterial, int coinsEarned, Location loc) {
        if (player == null) return;
        boolean working = WorkManager.isWorking(player) && HrabotaConfig.contains(loc != null ? loc : player.getLocation());
        String job = WorkManager.currentJobId(player);
        if (blockMaterial != null && !blockMaterial.isBlank()) {
            actionReporter.report(player, ActionKindRegistry.BREAK, blockMaterial, 1, loc, working, job);
        }
        if (coinsEarned > 0) {
            actionReporter.report(player, ActionKindRegistry.EARN_COINS, null, coinsEarned, loc, working, job);
        }
        if (working) {
            actionReporter.report(player, ActionKindRegistry.JOB_ACTION, null, 1, loc, true, job);
        }
    }

    public void applyNodeCompletion(Player player, String brokenStageMaterial,
                                    Map<String, Integer> lootCounts, int nodesCompleted, int coinsEarned) {
        applyNodeCompletion(player, brokenStageMaterial, lootCounts, nodesCompleted, coinsEarned,
                player != null ? player.getLocation() : null);
    }

    public void applyNodeCompletion(Player player, String brokenStageMaterial,
                                    Map<String, Integer> lootCounts, int nodesCompleted,
                                    int coinsEarned, Location nodeLocation) {
        if (player == null) return;
        boolean working = WorkManager.isWorking(player)
                && HrabotaConfig.contains(nodeLocation != null ? nodeLocation : player.getLocation());
        String job = WorkManager.currentJobId(player);

        if (brokenStageMaterial != null && !brokenStageMaterial.isBlank()) {
            actionReporter.report(player, ActionKindRegistry.BREAK, brokenStageMaterial, 1, nodeLocation, working, job);
        }
        if (lootCounts != null) {
            for (Map.Entry<String, Integer> e : lootCounts.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                    actionReporter.report(player, ActionKindRegistry.MINE, e.getKey(), e.getValue(), nodeLocation, working, job);
                }
            }
        }
        if (nodesCompleted > 0) {
            actionReporter.report(player, ActionKindRegistry.COMPLETE_NODES, null, nodesCompleted, nodeLocation, working, job);
        }
        if (coinsEarned > 0) {
            actionReporter.report(player, ActionKindRegistry.EARN_COINS, null, coinsEarned, nodeLocation, working, job);
        }
        if (working && nodesCompleted > 0) {
            actionReporter.report(player, ActionKindRegistry.JOB_ACTION, null, nodesCompleted, nodeLocation, true, job);
        }
    }

    public void applyItemSmelted(Player player, String itemMaterial, int count) {
        applyItemSmelted(player, itemMaterial, count, player != null ? player.getLocation() : null);
    }

    public void applyItemSmelted(Player player, String itemMaterial, int count, Location loc) {
        if (player == null || itemMaterial == null || count <= 0) return;
        boolean working = WorkManager.isWorking(player) && HrabotaConfig.contains(player.getLocation());
        actionReporter.report(player, ActionKindRegistry.SMELT, itemMaterial, count, loc, working, WorkManager.currentJobId(player));
    }

    public void applyItemCrafted(Player player, String itemMaterial, int count) {
        if (player == null || itemMaterial == null || count <= 0) return;
        boolean working = WorkManager.isWorking(player) && HrabotaConfig.contains(player.getLocation());
        actionReporter.report(player, ActionKindRegistry.CRAFT, itemMaterial, count,
                player.getLocation(), working, WorkManager.currentJobId(player));
    }

    // ------------------------------------------------------------------ синхронизация и повторы выплат

    public void syncPoolsAndTopUp() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            boolean anyDirty = false;
            for (PlayerQuestProfile profile : profiles.values()) {
                if (QuestEngineV2.ensureSynced(profile, settings.questsConfigV2(), now, rng)) {
                    anyDirty = true;
                }
            }
            if (anyDirty) markDirty();
        } finally {
            lock.unlock();
        }
    }

    public QuestEngineV2.EngineResult retryPendingPayouts(Player player) {
        UUID uuid = player.getUniqueId();
        WorkManager.retryPendingPayouts(true);
        lock.lock();
        try {
            PlayerQuestProfile profile = profiles.computeIfAbsent(uuid, k -> new PlayerQuestProfile());
            QuestV2Models.QuestsConfig qcfg = settings.questsConfigV2();
            QuestEngineV2.EngineResult r = QuestEngineV2.retryPendingPayouts(
                    profile, qcfg, System.currentTimeMillis(), true,
                    spec -> payoutFull(uuid, player, spec, qcfg),
                    msg -> plugin.getLogger().severe(msg)
            );
            if (r.dirty()) markDirty();
            return r;
        } finally {
            lock.unlock();
        }
    }

    private void tickBackgroundRetries() {
        WorkManager.retryPendingPayouts(false);
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            QuestV2Models.QuestsConfig qcfg = settings.questsConfigV2();
            boolean anyDirty = false;
            for (Map.Entry<UUID, PlayerQuestProfile> e : profiles.entrySet()) {
                UUID uuid = e.getKey();
                Player online = Bukkit.getPlayer(uuid);
                QuestEngineV2.EngineResult r = QuestEngineV2.retryPendingPayouts(
                        e.getValue(), qcfg, now, false,
                        spec -> payoutFull(uuid, online, spec, qcfg),
                        msg -> plugin.getLogger().severe(msg)
                );
                if (r.dirty()) anyDirty = true;
                if (online != null && !r.paidNow().isEmpty()) {
                    reportToPlayer(online, r, qcfg);
                }
            }
            if (anyDirty) markDirty();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ выдача наград и уведомления

    private boolean payoutFull(UUID playerId,
                               Player onlinePlayer,
                               QuestV2Models.QuestSpec spec,
                               QuestV2Models.QuestsConfig qcfg) {
        if (spec == null) return false;
        QuestV2Models.RewardsSpec rewards = spec.rewards();

        // Если в награде есть предметы, требуется онлайн-игрок, чтобы не потерять лут
        if (!rewards.items().isEmpty() && onlinePlayer == null) {
            return false;
        }

        // 1) Монеты строго через EconomyService (§3.4)
        if (rewards.coins() > 0) {
            try {
                EconomyService economy = FcpServices.require(EconomyService.class);
                economy.addCoins(playerId, rewards.coins());
            } catch (Throwable t) {
                plugin.getLogger().warning("Ошибка начисления " + rewards.coins() + " монет за задание '"
                        + spec.id() + "' игроку " + playerId + ": " + t.getMessage());
                return false;
            }
        }

        // 2) Предметы с шансом (переполнение инвентаря — под ноги игроку)
        if (onlinePlayer != null) {
            for (QuestV2Models.RewardItemSpec itemSpec : rewards.items()) {
                if (itemSpec.chance() < 1.0 && ThreadLocalRandom.current().nextDouble() >= itemSpec.chance()) {
                    continue;
                }
                Material mat = Material.matchMaterial(itemSpec.material().toUpperCase(Locale.ROOT));
                if (mat == null || !mat.isItem()) continue;
                int left = itemSpec.amount();
                while (left > 0) {
                    int stack = Math.min(mat.getMaxStackSize(), left);
                    left -= stack;
                    for (ItemStack overflow : onlinePlayer.getInventory().addItem(new ItemStack(mat, stack)).values()) {
                        onlinePlayer.getWorld().dropItemNaturally(onlinePlayer.getLocation(), overflow);
                    }
                }
            }
        }

        // 3) Консольные команды ТОЛЬКО через белый список префиксов (R8.1, §3.6)
        String safeName = onlinePlayer != null ? onlinePlayer.getName() : null;
        CommandWhitelistGuard.executeAllowed(
                rewards.commands(),
                qcfg.rewardCommandsWhitelist(),
                safeName,
                playerId,
                cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd),
                warn -> plugin.getLogger().warning("[Quests Security] " + warn)
        );

        // 4) Расширяемые обработчики будущих типов наград (§5.6)
        for (Map.Entry<String, Object> extra : rewards.extra().entrySet()) {
            var handler = rewardRegistry.get(extra.getKey());
            if (handler != null) {
                try {
                    handler.grant(playerId, spec.id(), extra.getValue());
                } catch (Throwable t) {
                    plugin.getLogger().warning("Ошибка доп. награды '" + extra.getKey() + "': " + t.getMessage());
                }
            }
        }

        return true;
    }

    private void reportToPlayer(Player player, QuestEngineV2.EngineResult result, QuestV2Models.QuestsConfig qcfg) {
        for (QuestEngineV2.StepAdvanceInfo adv : result.stepAdvances()) {
            QuestV2Models.QuestSpec spec = qcfg.questsById().get(adv.questId());
            String title = spec != null ? spec.title() : adv.questId();
            player.playSound(player.getLocation(), "minecraft:entity.player.levelup", 0.8f, 1.35f);
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&aЭтап " + adv.completedStepNumber() + "/" + adv.totalSteps()
                            + " задания «&f" + title + "&a» выполнен!"));
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    "&8 » &eСледующий этап: &f" + adv.nextStepName()));
        }
        for (String id : result.completedNow()) {
            QuestV2Models.QuestSpec spec = qcfg.questsById().get(id);
            String title = spec != null ? spec.title() : id;
            player.playSound(player.getLocation(), "minecraft:entity.player.levelup", 1.0f, 1.0f);
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&a&lЗадание выполнено: &f" + title));
        }
        for (String id : result.paidNow()) {
            QuestV2Models.QuestSpec spec = qcfg.questsById().get(id);
            if (spec != null && spec.rewards().coins() > 0) {
                player.sendMessage(Frontiks.Craft.utils.color.color(
                        Frontiks.Craft.utils.Msg.PREFIX + "&6+" + spec.rewards().coins()
                                + " монет &7за задание &f" + spec.title()));
            }
        }
        for (String unlockedId : result.unlockedNow()) {
            QuestV2Models.QuestSpec unlocked = qcfg.questsById().get(unlockedId);
            String title = unlocked != null ? unlocked.title() : unlockedId;
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&bОткрыто новое задание: &f" + title));
        }
        for (String id : result.payFailed()) {
            QuestV2Models.QuestSpec spec = qcfg.questsById().get(id);
            String title = spec != null ? spec.title() : id;
            player.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&eЗадание «&f" + title
                            + "&e» выполнено! Награда будет выдана автоматически или по кнопке «Проверить награды»."));
        }
    }

    private QuestEngineV2.PlayerContext contextOf(Player p) {
        return new QuestEngineV2.PlayerContext(WorkManager.currentJobId(p), p::hasPermission);
    }

    public String resetTextForCategory(Player player, String category) {
        QuestV2Models.QuestsConfig qcfg = settings.questsConfigV2();
        QuestV2Models.PeriodTemplate tpl = qcfg.periods().get(category);
        if (tpl == null) return "—";
        long anchor = 0L;
        lock.lock();
        try {
            PlayerQuestProfile p = profiles.get(player.getUniqueId());
            if (p != null) {
                anchor = p.bucket(category).intervalAnchorMs;
            }
        } finally {
            lock.unlock();
        }
        long next = QuestEngineV2.nextResetEpochMs(tpl, qcfg.timezone(), System.currentTimeMillis(), anchor);
        return QuestEngineV2.formatReset(next, qcfg.timezone());
    }

    /** Полная диагностика конфига заданий v2, периодов и реестров для /questsadmin validate (R6). */
    public List<String> validateReport() {
        List<String> lines = new ArrayList<>();
        QuestV2Models.QuestsConfig qcfg = settings.questsConfigV2();
        lines.add("&eСостояние системы заданий: " + (qcfg.enabled() ? "&aвключена" : "&cвыключена"));
        lines.add("&7Часовой пояс: &f" + qcfg.timezone().getId()
                + " &8| &7По умолчанию accept: &f" + (qcfg.defaultManualAccept() ? "manual" : "auto"));
        lines.add("&7Видов работы в реестре: &f" + settings.jobRegistry().all().size()
                + " &8(" + settings.jobRegistry().defaultJob().id + ")");
        lines.add("&7Типов событий (ActionKind): &f" + settings.actionRegistry().allCanonical().size());
        lines.add("&7Белый список reward-команд: &f"
                + (qcfg.rewardCommandsWhitelist().isEmpty()
                ? "&eпуст (исполнение команд отключено)"
                : String.join(", ", qcfg.rewardCommandsWhitelist())));

        for (QuestV2Models.PeriodTemplate tpl : qcfg.periods().values()) {
            long inCat = qcfg.quests().stream().filter(q -> tpl.name().equals(q.category())).count();
            lines.add("&7Период &f" + tpl.name() + " &8(" + tpl.kind()
                    + (tpl.kind() == QuestV2Models.PeriodKind.INTERVAL ? ", " + tpl.days() + " дн." : "")
                    + ")&7: в пуле &f" + inCat + " &7заданий"
                    + (tpl.kind() == QuestV2Models.PeriodKind.ONCE ? "" : ", выдаётся по &f" + tpl.count()));
        }

        List<String> errs = settings.configErrors();
        if (errs.isEmpty()) {
            lines.add("&aОшибок конфигурации quests.yml не обнаружено! Загружено валидных заданий: &f" + qcfg.quests().size());
        } else {
            lines.add("&cОбнаружено ошибок конфигурации (" + errs.size() + "):");
            for (String err : errs) {
                lines.add("  &c• &f" + err);
            }
        }
        return lines;
    }
}

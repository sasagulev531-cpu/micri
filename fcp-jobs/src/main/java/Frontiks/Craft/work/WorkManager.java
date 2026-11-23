package Frontiks.Craft.work;

import Frontiks.Craft.api.EconomyService;
import Frontiks.Craft.api.FcpServices;
import Frontiks.Craft.api.FcpYamlStorage;
import Frontiks.Craft.api.LegacyFiles;
import Frontiks.Craft.jobs.JobKind;
import Frontiks.Craft.utils.DebouncedSaver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static Frontiks.Craft.utils.Msg.PREFIX;
import static Frontiks.Craft.utils.color.color;

/**
 * Менеджер рабочей смены шахтёра с персистентностью заработка в work-sessions.yml (R7)
 * и дебаунсом записи (не чаще 1 раза в 5 с + синхронный flush при выключении).
 */
public class WorkManager {

    private static final WorkSessionTracker tracker = new WorkSessionTracker();
    private static final Map<UUID, Long> needJobThrottle = new HashMap<>();

    private static JavaPlugin plugin;
    private static File file;
    private static FileConfiguration config;
    private static DebouncedSaver saver;

    public static void init(JavaPlugin owner) {
        plugin = owner;
        try {
            file = LegacyFiles.migrate(owner, "work-sessions.yml");
            if (!file.exists()) {
                config = new YamlConfiguration();
                config.set("version", 1);
                FcpYamlStorage.save(owner, file, config);
            } else {
                config = FcpYamlStorage.load(owner, file);
            }
            ConfigurationSection sec = config.getConfigurationSection("sessions");
            if (sec != null) {
                Map<String, Object> raw = new LinkedHashMap<>();
                for (String key : sec.getKeys(false)) {
                    ConfigurationSection sub = sec.getConfigurationSection(key);
                    if (sub != null) raw.put(key, sub.getValues(false));
                }
                tracker.loadFromMap(raw);
            }
            saver = new DebouncedSaver(owner, 100L, WorkManager::writeToDiskNow);
            // После рестарта переводим прерванные смены в очередь выплаты и пытаемся зачислить
            tracker.recoverAfterRestart(System.currentTimeMillis(), WorkManager::tryAddCoins);
            saver.markDirty();
        } catch (Exception e) {
            owner.getLogger().severe("Не удалось инициализировать work-sessions.yml: " + e);
            if (config == null) config = new YamlConfiguration();
        }
    }

    public static void shutdown() {
        if (saver != null) {
            saver.flushNow();
        } else {
            writeToDiskNow();
        }
    }

    private static synchronized void writeToDiskNow() {
        if (plugin == null || file == null || config == null) return;
        try {
            config.set("version", 1);
            config.set("sessions", tracker.toMap());
            FcpYamlStorage.save(plugin, file, config);
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось сохранить work-sessions.yml: " + e);
        }
    }

    private static void markDirty() {
        if (saver != null) {
            saver.markDirty();
        }
    }

    public static void start(Player p) {
        if (p == null) return;
        tracker.startShift(p.getUniqueId(), JobKind.MINER_ID, System.currentTimeMillis());
        markDirty();
        p.sendMessage(color("" + PREFIX + "§fВы начали работу на Шахте"));
    }

    public static void stop(Player p) {
        if (p == null) return;
        UUID id = p.getUniqueId();
        WorkSessionTracker.StopOutcome out = tracker.stopShift(id, System.currentTimeMillis(), WorkManager::tryAddCoins);
        markDirty();

        if (out.totalEarnedThisShift() > 0 || out.paidCoinsNow() > 0) {
            if (out.paidOk()) {
                p.sendMessage(color("" + PREFIX + "§aРабота завершена! &fВы заработали §f"
                        + out.paidCoinsNow() + " §aмонет!"));
            } else {
                p.sendMessage(color("" + PREFIX
                        + "§eРабота завершена! Экономика временно недоступна — ваш заработок (§f"
                        + tracker.getPendingPayout(id) + " §eмонет) сохранён и будет зачислен автоматически."));
            }
        } else {
            p.sendMessage(color("" + PREFIX + "§fВы ничего не заработали &a:("));
        }
    }

    public static boolean isWorking(Player p) {
        return p != null && tracker.isWorking(p.getUniqueId());
    }

    public static String currentJobId(Player p) {
        return p == null ? JobKind.MINER_ID : tracker.currentJobId(p.getUniqueId());
    }

    public static void add(Player p, int amount) {
        if (p == null || amount <= 0) return;
        tracker.addEarned(p.getUniqueId(), amount);
        markDirty();
    }

    public static int getEarned(Player p) {
        return p == null ? 0 : tracker.getEarned(p.getUniqueId());
    }

    public static int tickSecondAndDrainMinutes(Player p) {
        if (p == null) return 0;
        return tracker.addActiveSecondsAndDrainMinutes(p.getUniqueId(), 1);
    }

    public static int retryPendingPayouts(boolean manual) {
        int count = tracker.retryPendingPayouts(
                System.currentTimeMillis(),
                manual,
                WorkManager::tryAddCoins,
                msg -> {
                    if (plugin != null) plugin.getLogger().severe(msg);
                    else Bukkit.getLogger().severe("[FCP-Jobs] " + msg);
                }
        );
        if (count > 0) {
            markDirty();
        }
        return count;
    }

    private static boolean tryAddCoins(UUID playerId, int coins) {
        if (coins <= 0) return true;
        try {
            FcpServices.require(EconomyService.class).addCoins(playerId, coins);
            return true;
        } catch (Throwable t) {
            if (plugin != null) {
                plugin.getLogger().warning("Не удалось начислить " + coins + " монет смены игроку " + playerId + ": " + t.getMessage());
            }
            return false;
        }
    }

    public static void notifyNeedJob(Player p) {
        if (p == null) return;
        long now = System.currentTimeMillis();
        Long until = needJobThrottle.get(p.getUniqueId());
        if (until != null && now < until) {
            return;
        }
        needJobThrottle.put(p.getUniqueId(), now + 1200L);

        p.sendTitle(
                color("&c&lУстройтесь на работу!"),
                color("&fОткройте &e/work &fчтобы начать смену"),
                5, 35, 10
        );
        p.playSound(p.getLocation(), "minecraft:entity.villager.no", 1.0f, 1.0f);
    }

    /** Доступ к трекеру для тестов и диагностики. */
    public static WorkSessionTracker sessionTracker() {
        return tracker;
    }
}

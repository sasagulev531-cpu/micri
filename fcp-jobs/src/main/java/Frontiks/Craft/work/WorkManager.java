package Frontiks.Craft.work;

import Frontiks.Craft.api.EconomyService;
import Frontiks.Craft.api.FcpServices;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static Frontiks.Craft.utils.Msg.PREFIX;
import static Frontiks.Craft.utils.color.color;

public class WorkManager {

    private static final Set<UUID> working = new HashSet<>();
    private static final Map<UUID, Integer> money = new HashMap<>();
    private static final Map<UUID, Long> needJobThrottle = new HashMap<>();

    public static void start(Player p) {
        UUID id = p.getUniqueId();

        working.add(id);
        money.put(id, 0);

        p.sendMessage(color("" + PREFIX + "§fВы начали работу на Шахте"));
    }

    public static void stop(Player p) {
        UUID id = p.getUniqueId();

        int earned = money.getOrDefault(id, 0);
        working.remove(id);
        money.remove(id);

        if (earned > 0) {
            try {
                FcpServices.require(EconomyService.class).addCoins(id, earned);
                p.sendMessage(color("" + PREFIX + "§aРабота завершена! &fВы заработали §f" + earned + " §aмонет!"));
            } catch (Throwable t) {
                Bukkit.getLogger().severe("[FCP-Jobs] Не удалось выплатить " + earned + " монет игроку " + p.getName() + ": " + t);
                p.sendMessage(color("" + PREFIX + "§eРабота завершена, но экономика недоступна — не удалось зачислить §f" + earned + " §eмонет."));
            }
        } else {
            p.sendMessage(color("" + PREFIX + "§fВы ничего не заработали &a:("));
        }
    }

    public static boolean isWorking(Player p) {
        return working.contains(p.getUniqueId());
    }

    public static void add(Player p, int amount) {
        UUID id = p.getUniqueId();
        money.put(id, money.getOrDefault(id, 0) + amount);
    }

    public static int getEarned(Player p) {
        return money.getOrDefault(p.getUniqueId(), 0);
    }

    /**
     * Показывает на экране «Устройтесь на работу!» и проигрывает недовольный звук жителя,
     * если игрок пытается копать в шахте без начатой смены.
     */
    public static void notifyNeedJob(Player p) {
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
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }
}

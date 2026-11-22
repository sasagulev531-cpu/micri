package Frontiks.Craft.work;

import Frontiks.Craft.api.FcpServices;
import Frontiks.Craft.api.EconomyService;
import org.bukkit.entity.Player;

import java.util.*;

import static Frontiks.Craft.utils.color.color;
import static Frontiks.Craft.utils.Msg.PREFIX;

public class WorkManager {

    private static final Set<UUID> working = new HashSet<>();
    private static final Map<UUID, Integer> money = new HashMap<>();

    public static void start(Player p) {
        UUID id = p.getUniqueId();

        working.add(id);
        money.put(id, 0);

        p.sendMessage(color(""+PREFIX+"§fВы начали работу на Шахте"));
    }

    public static void stop(Player p) {
        UUID id = p.getUniqueId();

        int earned = money.getOrDefault(id, 0);

        if (earned > 0) {
            FcpServices.require(EconomyService.class).addCoins(id, earned);
            p.sendMessage(color(""+PREFIX+"§aРабота завершена! &fВы заработали §f" + earned + " §aмонет!"));
        } else {
            p.sendMessage(color(""+PREFIX+"§fВы ничего не заработали &a:("));
        }

        working.remove(id);
        money.remove(id);
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
}
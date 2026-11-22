package Frontiks.Craft.listeners;

import Frontiks.Craft.work.WorkManager;
import Frontiks.Craft.work.HrabotaConfig;
import Frontiks.Craft.utils.color;
import static Frontiks.Craft.utils.Msg.PREFIX;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class WorkCheckListener implements Listener {

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();

        if (!WorkManager.isWorking(p)) return;

        if (!inRegion(p.getLocation())) {
            WorkManager.stop(p);
            p.sendMessage(color.color(""+PREFIX+"&fВы вышли с зоны работы, вы были уволены."));
        }
    }

    private boolean inRegion(Location loc) {
        return HrabotaConfig.contains(loc);
    }
}
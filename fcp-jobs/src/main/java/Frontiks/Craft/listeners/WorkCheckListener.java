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
        FileConfiguration config = HrabotaConfig.get();

        // Проверяем, есть ли данные в конфиге
        if (!config.contains("pos1.world") || !config.contains("pos2.world")) {
            return false;
        }

        String worldName1 = config.getString("pos1.world");
        String worldName2 = config.getString("pos2.world");

        // Проверяем, что мир не null
        if (worldName1 == null || worldName2 == null) {
            return false;
        }

        World world1 = Bukkit.getWorld(worldName1);
        World world2 = Bukkit.getWorld(worldName2);

        if (world1 == null || world2 == null) {
            return false;
        }

        Location p1 = new Location(
                world1,
                config.getDouble("pos1.x"),
                config.getDouble("pos1.y"),
                config.getDouble("pos1.z")
        );

        Location p2 = new Location(
                world2,
                config.getDouble("pos2.x"),
                config.getDouble("pos2.y"),
                config.getDouble("pos2.z")
        );

        return loc.getWorld().equals(p1.getWorld()) &&
                loc.getX() >= Math.min(p1.getX(), p2.getX()) &&
                loc.getX() <= Math.max(p1.getX(), p2.getX()) &&
                loc.getY() >= Math.min(p1.getY(), p2.getY()) &&
                loc.getY() <= Math.max(p1.getY(), p2.getY()) &&
                loc.getZ() >= Math.min(p1.getZ(), p2.getZ()) &&
                loc.getZ() <= Math.max(p1.getZ(), p2.getZ());
    }
}
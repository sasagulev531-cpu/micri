package Frontiks.Craft.commands;

import Frontiks.Craft.api.FcpPermissions;

import Frontiks.Craft.work.HrabotaConfig;
import Frontiks.Craft.work.RegionManager;
import Frontiks.Craft.utils.color;
import static Frontiks.Craft.utils.Msg.PREFIX;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class SetGranicCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) {
            sender.sendMessage(color.color("&cТолько для игроков!"));
            return true;
        }

        if (!FcpPermissions.adminOr(p, "fcp.admin.jobs.region")) {
            p.sendMessage(color.color(""+PREFIX+"&cНет прав"));
            return true;
        }

        if (args.length < 1) {
            p.sendMessage(color.color("&cИспользуй: /setgranic <1/2>"));
            p.sendMessage(color.color("&7/setgranic 1 - установить первую точку"));
            p.sendMessage(color.color("&7/setgranic 2 - установить вторую точку"));
            return true;
        }

        try {
            int pos = Integer.parseInt(args[0]);
            FileConfiguration config = HrabotaConfig.get();

            if (pos == 1) {
                RegionManager.pos1 = p.getLocation().clone();
                p.sendMessage(color.color("&aТочка 1 установлена!"));
                p.sendMessage(color.color("&7Мир: &f" + RegionManager.pos1.getWorld().getName()));
                p.sendMessage(color.color("&7X: &f" + RegionManager.pos1.getBlockX() +
                        " &7Y: &f" + RegionManager.pos1.getBlockY() +
                        " &7Z: &f" + RegionManager.pos1.getBlockZ()));

                config.set("pos1.world", RegionManager.pos1.getWorld().getName());
                config.set("pos1.x", RegionManager.pos1.getX());
                config.set("pos1.y", RegionManager.pos1.getY());
                config.set("pos1.z", RegionManager.pos1.getZ());
                HrabotaConfig.save();

                p.sendMessage(color.color("&aКоординаты сохранены в hrabota.yml!"));

            } else if (pos == 2) {
                RegionManager.pos2 = p.getLocation().clone();
                p.sendMessage(color.color("&aТочка 2 установлена!"));
                p.sendMessage(color.color("&7Мир: &f" + RegionManager.pos2.getWorld().getName()));
                p.sendMessage(color.color("&7X: &f" + RegionManager.pos2.getBlockX() +
                        " &7Y: &f" + RegionManager.pos2.getBlockY() +
                        " &7Z: &f" + RegionManager.pos2.getBlockZ()));

                config.set("pos2.world", RegionManager.pos2.getWorld().getName());
                config.set("pos2.x", RegionManager.pos2.getX());
                config.set("pos2.y", RegionManager.pos2.getY());
                config.set("pos2.z", RegionManager.pos2.getZ());
                HrabotaConfig.save();

                p.sendMessage(color.color("&aКоординаты сохранены в hrabota.yml!"));

            } else {
                p.sendMessage(color.color("&cИспользуй 1 или 2!"));
            }

        } catch (NumberFormatException e) {
            p.sendMessage(color.color("&cВведите число!"));
        }

        return true;
    }
}
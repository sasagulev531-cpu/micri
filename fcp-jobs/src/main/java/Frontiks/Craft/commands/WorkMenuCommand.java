package Frontiks.Craft.commands;

import Frontiks.Craft.gui.WorkMenu;
import Frontiks.Craft.utils.color;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class WorkMenuCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) {
            sender.sendMessage(color.color("&cТолько для игроков!"));
            return true;
        }

        try {
            WorkMenu.open(p);
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[FCP-Jobs] Ошибка открытия /work: " + t);
            t.printStackTrace();
            p.sendMessage(color.color("&cОшибка открытия меню работы: &f" + t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
        return true;
    }
}

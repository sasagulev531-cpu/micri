package Frontiks.Craft.commands;

import Frontiks.Craft.gui.WorkMenu;
import Frontiks.Craft.utils.color;
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

        WorkMenu.open(p);
        return true;
    }
}
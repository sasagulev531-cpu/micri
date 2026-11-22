package Frontiks.Craft.commands;

import Frontiks.Craft.gui.WorkMenu;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class WorkCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player)) return true;

        Player p = (Player) sender;

        WorkMenu.open(p);

        return true;
    }
}
package Frontiks.Craft.commands;

import Frontiks.Craft.gui.WorkHubMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class WorkCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        WorkHubMenu.open(p, (Frontiks.Craft.mining.MiningSettings) null);
        return true;
    }
}

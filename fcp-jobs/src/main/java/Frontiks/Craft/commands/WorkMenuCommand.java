package Frontiks.Craft.commands;

import Frontiks.Craft.gui.WorkHubMenu;
import Frontiks.Craft.gui.WorkMenu;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.utils.color;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

public class WorkMenuCommand implements CommandExecutor {

    private final MiningSettings settings;

    public WorkMenuCommand() {
        this(null);
    }

    public WorkMenuCommand(MiningSettings settings) {
        this.settings = settings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(color.color("&cТолько для игроков!"));
            return true;
        }

        try {
            if (args.length > 0) {
                String arg = args[0].trim().toLowerCase(Locale.ROOT);
                if (arg.equals("miner") || arg.equals("mine") || arg.equals("shahta") || arg.equals("шахтер") || arg.equals("шахтёр")) {
                    WorkMenu.openDirect(p);
                    return true;
                }
            }
            WorkHubMenu.open(p, settings);
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[FCP-Jobs] Ошибка открытия /work: " + t);
            t.printStackTrace();
            p.sendMessage(color.color("&cОшибка открытия меню работы: &f" + t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
        return true;
    }
}

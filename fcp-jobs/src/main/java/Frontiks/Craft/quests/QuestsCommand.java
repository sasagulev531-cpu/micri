package Frontiks.Craft.quests;

import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.utils.color;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * /quests — меню заданий шахтёра.
 * /quests claim — повторная попытка выплаты наград за уже выполненные, но неоплаченные задания.
 */
public final class QuestsCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final QuestManager quests;
    private final MiningSettings settings;

    public QuestsCommand(JavaPlugin plugin, QuestManager quests, MiningSettings settings) {
        this.plugin = plugin;
        this.quests = quests;
        this.settings = settings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(color.color("&cТолько для игроков!"));
            return true;
        }
        if (!p.hasPermission("fcp.jobs.quests")) {
            p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cНет прав."));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("claim")) {
            var result = quests.retryPendingPayouts(p);
            QuestsMenuListener.sendSummary(p, result);
            return true;
        }

        new QuestsMenu(quests, settings).open(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("claim").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return List.of();
    }
}

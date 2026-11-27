package Frontiks.Craft.quests;

import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.utils.color;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /quests — только открытие GUI задач шахтёра (настройка всех задач ведётся в plugins/FCP-Jobs/quests.yml).
 */
public final class QuestsCommand implements CommandExecutor {

    private final QuestManager quests;
    private final MiningSettings settings;

    public QuestsCommand(QuestManager quests, MiningSettings settings) {
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
        new QuestsHubMenu(quests, settings).open(p);
        return true;
    }
}

package Frontiks.Craft.quests;

import Frontiks.Craft.api.FcpPermissions;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.utils.Msg;
import Frontiks.Craft.utils.color;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Админ-команда диагностики и перезагрузки quests.yml v2 (/questsadmin validate|reload, R6).
 * Работает как для админа в игре (fcp.admin.jobs.quests), так и из консоли сервера!
 */
public final class QuestsAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("validate", "reload");

    private final QuestManager quests;
    private final MiningSettings settings;

    public QuestsAdminCommand(QuestManager quests, MiningSettings settings) {
        this.quests = quests;
        this.settings = settings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player p && !FcpPermissions.adminOr(p, "fcp.admin.jobs.quests")) {
            p.sendMessage(color.color(Msg.PREFIX + "&cНет прав (fcp.admin.jobs.quests)."));
            return true;
        }

        String sub = args.length == 0 ? "validate" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                settings.reload();
                quests.syncPoolsAndTopUp();
                sender.sendMessage(color.color(Msg.PREFIX + "&aКонфиг quests.yml перезагружен."));
                for (String line : quests.validateReport()) {
                    sender.sendMessage(color.color("  " + line));
                }
            }
            case "validate" -> {
                sender.sendMessage(color.color(Msg.PREFIX + "&6Отчёт валидации quests.yml:"));
                for (String line : quests.validateReport()) {
                    sender.sendMessage(color.color("  " + line));
                }
            }
            default -> sender.sendMessage(color.color(Msg.PREFIX + "&eИспользование: /" + label + " <validate|reload>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (sender instanceof Player p && !FcpPermissions.adminOr(p, "fcp.admin.jobs.quests")) {
            return List.of();
        }
        if (args.length == 1) {
            String pref = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(pref)).collect(Collectors.toList());
        }
        return List.of();
    }
}

package Frontiks.Craft.quests;

import Frontiks.Craft.api.FcpPermissions;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.utils.color;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /quests (алиасы: /quest, /zadachi, /miningquests)
 * Для игроков:
 *   /quests         — открыть меню задач шахтёра
 *   /quests claim   — забрать невыплаченные награды
 * Для админов (fcp.admin.jobs.node):
 *   /quests create <сломать|добыть|получить> <предмет/блок|ANY|NODE> <кол-во 1..65536> [награда] [daily|weekly] [название...]
 *   /quests list [daily|weekly]
 *   /quests remove <daily|weekly> <номер_или_id>
 *   /quests clear [daily|weekly|all]
 *   /quests count <daily|weekly> <1..7>
 *   /quests reroll
 *   /quests help
 */
public final class QuestsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ADMIN_SUBS = List.of(
            "claim", "create", "add", "list", "remove", "delete", "clear", "count", "reroll", "help"
    );
    private static final List<String> ACTIONS_TAB = List.of(
            "сломать", "добыть", "получить", "break", "mine", "get"
    );
    private static final List<String> PERIODS_TAB = List.of("daily", "weekly");

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
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(color.color("&cУкажите подкоманду: /" + label + " help"));
                return true;
            }
            if (!p.hasPermission("fcp.jobs.quests")) {
                p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cНет прав."));
                return true;
            }
            new QuestsMenu(quests, settings).open(p);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("claim")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(color.color("&cТолько для игроков!"));
                return true;
            }
            if (!p.hasPermission("fcp.jobs.quests")) {
                p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cНет прав."));
                return true;
            }
            var result = quests.retryPendingPayouts(p);
            QuestsMenuListener.sendSummary(p, result);
            return true;
        }

        // Остальные подкоманды — админские
        if (!FcpPermissions.adminOr(sender, "fcp.admin.jobs.node")) {
            sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cНет прав (fcp.admin.jobs.node)."));
            return true;
        }

        switch (sub) {
            case "create", "add" -> handleCreate(sender, label, args);
            case "list" -> handleList(sender, args);
            case "remove", "delete", "del" -> handleRemove(sender, label, args);
            case "clear" -> handleClear(sender, args);
            case "count" -> handleCount(sender, label, args);
            case "reroll", "reset" -> {
                quests.forceRerollAll();
                sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX
                        + "&aАктивные задачи всех игроков сброшены и будут выданы заново из текущего пула!"));
            }
            default -> sendAdminHelp(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------------ /quests create

    private void handleCreate(CommandSender sender, String label, String[] args) {
        // Поддерживаем два порядка:
        // 1) /quests create <сломать|добыть|получить> <предмет|ANY|NODE> <1..65536> [награда] [daily|weekly] [название...]
        // 2) /quests create <daily|weekly> <сломать|добыть|получить> <предмет|ANY|NODE> <1..65536> [награда] [название...]
        int offset = 1;
        QuestBoard.Period explicitPeriod = null;
        if (args.length > offset) {
            explicitPeriod = parsePeriodOrNull(args[offset]);
            if (explicitPeriod != null) {
                offset++;
            }
        }

        if (args.length < offset + 3) {
            sender.sendMessage(color.color("&cИспользование: &e/" + label
                    + " create <сломать|добыть|получить> <предмет_или_блок|ANY|NODE> <кол-во 1..65536> [награда] [daily|weekly] [название...]"));
            sender.sendMessage(color.color("&7Примеры:"));
            sender.sendMessage(color.color("&8 • &f/" + label + " create сломать STONE 500 250"));
            sender.sendMessage(color.color("&8 • &f/" + label + " create добыть DIAMOND 15 1000 weekly"));
            sender.sendMessage(color.color("&8 • &f/" + label + " create получить IRON_INGOT 64 300 daily Переплавить железо"));
            return;
        }

        String actionRaw = args[offset].trim().toLowerCase(Locale.ROOT);
        String targetRaw = args[offset + 1].trim().toUpperCase(Locale.ROOT);
        Integer amount = parseInt(args[offset + 2]);

        QuestDef.Type type;
        switch (actionRaw) {
            case "сломать", "break", "сломай" -> type = QuestDef.Type.BREAK_BLOCK;
            case "добыть", "mine", "добудь", "вскопать" -> type = QuestDef.Type.LOOT_AMOUNT;
            case "получить", "get", "obtain", "получи", "собрать" -> type = QuestDef.Type.OBTAIN_ITEM;
            default -> {
                sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX
                        + "&cНеизвестное действие &f" + args[offset] + "&c! Доступно: &eсломать&c, &eдобыть&c, &eполучить"));
                return;
            }
        }

        // Проверяем "что именно"
        String material;
        if (targetRaw.equals("NODE") || targetRaw.equals("NODES") || targetRaw.equals("УЗЕЛ") || targetRaw.equals("УЗЛЫ")) {
            type = QuestDef.Type.NODES_COMPLETED;
            material = null;
        } else if (targetRaw.equals("ANY") || targetRaw.equals("*") || targetRaw.equals("ЛЮБОЙ") || targetRaw.equals("ВСЕ")) {
            material = "ANY";
        } else {
            Material mat = Material.matchMaterial(targetRaw);
            if (mat == null) {
                sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX
                        + "&cНеизвестный предмет/блок: &f" + targetRaw + "&c (можно также указать &eANY &cили &eNODE&c)."));
                return;
            }
            if (type == QuestDef.Type.BREAK_BLOCK && !mat.isBlock()) {
                sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX
                        + "&cДля действия «сломать» нужен БЛОК (например STONE, TUFF, IRON_ORE), а &f" + mat.name() + " &c— не блок!"));
                return;
            }
            material = mat.name();
        }

        // Проверяем количество 1..65536 (2^16)
        if (amount == null || amount < 1 || amount > QuestDef.MAX_AMOUNT) {
            sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX
                    + "&cКоличество должно быть целым числом от &e1 &cдо &e" + QuestDef.MAX_AMOUNT + " &7(2^16)&c!"));
            return;
        }

        int idx = offset + 3;
        int reward = 100; // награда по умолчанию, если не указана
        if (idx < args.length) {
            Integer parsedReward = parseInt(args[idx]);
            if (parsedReward != null) {
                if (parsedReward < 0) {
                    sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cНаграда не может быть отрицательной."));
                    return;
                }
                reward = parsedReward;
                idx++;
            }
        }

        QuestBoard.Period period = explicitPeriod != null ? explicitPeriod : QuestBoard.Period.DAILY;
        if (explicitPeriod == null && idx < args.length) {
            QuestBoard.Period maybePeriod = parsePeriodOrNull(args[idx]);
            if (maybePeriod != null) {
                period = maybePeriod;
                idx++;
            }
        }

        String customTitle = idx < args.length
                ? String.join(" ", Arrays.copyOfRange(args, idx, args.length)).trim()
                : "";

        String title = customTitle.isEmpty() ? autoTitle(type, material, amount) : customTitle;
        String description = autoDescription(type, material, amount);
        String id = generateId(period, type, material, amount);

        QuestDef created = new QuestDef(id, type, material, amount, title, description, reward);

        List<QuestDef> pool = new ArrayList<>(period == QuestBoard.Period.DAILY
                ? settings.dailyPool()
                : settings.weeklyPool());
        pool.add(created);
        settings.saveQuestPool(period, pool);

        // Если лимит выдаваемых задач меньше размера пула (до 7 слотов в строке GUI) — расширяем автоматически,
        // чтобы созданная задача сразу появилась в меню!
        int currentCount = period == QuestBoard.Period.DAILY ? settings.dailyCount() : settings.weeklyCount();
        if (pool.size() <= 7 && currentCount < pool.size()) {
            settings.saveQuestCount(period, pool.size());
        }
        quests.syncPoolsAndTopUp();

        String periodRu = period == QuestBoard.Period.DAILY ? "Ежедневная" : "Недельная";
        sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&a" + periodRu + " задача создана!"));
        sender.sendMessage(color.color("&8 • &7ID: &f" + created.id));
        sender.sendMessage(color.color("&8 • &7Задача: &f" + created.title + " &8(" + created.actionLabelRu()
                + (created.material == null ? "" : " " + created.material) + " x" + created.amount + ")"));
        sender.sendMessage(color.color("&8 • &7Награда: &6" + created.reward + " монет"));
    }

    // ------------------------------------------------------------------ list / remove / clear / count

    private void handleList(CommandSender sender, String[] args) {
        QuestBoard.Period filter = args.length >= 2 ? parsePeriodOrNull(args[1]) : null;
        if (filter == null || filter == QuestBoard.Period.DAILY) {
            printPool(sender, "Ежедневные задачи (выдаётся по " + settings.dailyCount() + " шт.)", settings.dailyPool());
        }
        if (filter == null || filter == QuestBoard.Period.WEEKLY) {
            printPool(sender, "Недельные задачи (выдаётся по " + settings.weeklyCount() + " шт.)", settings.weeklyPool());
        }
    }

    private void printPool(CommandSender sender, String header, List<QuestDef> pool) {
        sender.sendMessage(color.color("&8---- &6" + header + " &8----"));
        if (pool.isEmpty()) {
            sender.sendMessage(color.color("&7Список пуст."));
            return;
        }
        for (int i = 0; i < pool.size(); i++) {
            QuestDef d = pool.get(i);
            String target = d.material == null ? "УЗЛЫ" : d.material;
            sender.sendMessage(color.color("&e" + (i + 1) + ". &f" + d.title
                    + " &8[&7" + d.actionLabelRu() + " " + target + " x" + d.amount
                    + "&8, &6" + d.reward + " монет&8, id=" + d.id + "]"));
        }
    }

    private void handleRemove(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color.color("&cИспользование: &e/" + label + " remove <daily|weekly> <номер_или_id>"));
            return;
        }
        QuestBoard.Period period = parsePeriodOrNull(args[1]);
        if (period == null) {
            sender.sendMessage(color.color("&cУкажите период: &edaily &cили &eweekly"));
            return;
        }
        List<QuestDef> pool = new ArrayList<>(period == QuestBoard.Period.DAILY
                ? settings.dailyPool()
                : settings.weeklyPool());
        if (pool.isEmpty()) {
            sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cСписок задач уже пуст."));
            return;
        }

        QuestDef removed = null;
        Integer num = parseInt(args[2]);
        if (num != null && num >= 1 && num <= pool.size()) {
            removed = pool.remove(num - 1);
        } else {
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).id.equalsIgnoreCase(args[2])) {
                    removed = pool.remove(i);
                    break;
                }
            }
        }

        if (removed == null) {
            sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX
                    + "&cЗадача не найдена. Посмотрите номера через &e/" + label + " list"));
            return;
        }

        settings.saveQuestPool(period, pool);
        quests.syncPoolsAndTopUp();
        sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX
                + "&aУдалена задача: &f" + removed.title + " &8(" + removed.id + ")"));
    }

    private void handleClear(CommandSender sender, String[] args) {
        String which = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "all";
        QuestBoard.Period single = parsePeriodOrNull(which);
        if (single == QuestBoard.Period.DAILY) {
            settings.saveQuestPool(QuestBoard.Period.DAILY, List.of());
            quests.syncPoolsAndTopUp();
            sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aПул ежедневных задач очищен."));
        } else if (single == QuestBoard.Period.WEEKLY) {
            settings.saveQuestPool(QuestBoard.Period.WEEKLY, List.of());
            quests.syncPoolsAndTopUp();
            sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aПул недельных задач очищен."));
        } else {
            settings.saveQuestPool(QuestBoard.Period.DAILY, List.of());
            settings.saveQuestPool(QuestBoard.Period.WEEKLY, List.of());
            quests.forceRerollAll();
            sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aВсе ежедневные и недельные задачи очищены."));
        }
    }

    private void handleCount(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color.color("&cИспользование: &e/" + label + " count <daily|weekly> <1..7>"));
            return;
        }
        QuestBoard.Period period = parsePeriodOrNull(args[1]);
        Integer cnt = parseInt(args[2]);
        if (period == null || cnt == null || cnt < 1 || cnt > 7) {
            sender.sendMessage(color.color("&cУкажите &edaily&c/&eweekly &cи количество от &e1 &cдо &e7&c."));
            return;
        }
        settings.saveQuestCount(period, cnt);
        quests.syncPoolsAndTopUp();
        sender.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aЛимит активных задач (" + period + "): &f" + cnt));
    }

    private void sendAdminHelp(CommandSender sender, String label) {
        sender.sendMessage(color.color("&8---- &6Управление задачами /" + label + " &8----"));
        sender.sendMessage(color.color("&e/" + label + " &7— открыть меню задач"));
        sender.sendMessage(color.color("&e/" + label + " create <сломать|добыть|получить> <предмет|ANY|NODE> <1..65536> [награда] [daily|weekly] [название]"));
        sender.sendMessage(color.color("&e/" + label + " list [daily|weekly] &7— список всех задач"));
        sender.sendMessage(color.color("&e/" + label + " remove <daily|weekly> <номер|id> &7— удалить задачу"));
        sender.sendMessage(color.color("&e/" + label + " clear [daily|weekly|all] &7— очистить задачи"));
        sender.sendMessage(color.color("&e/" + label + " count <daily|weekly> <1..7> &7— сколько задач выдавать игроку"));
        sender.sendMessage(color.color("&e/" + label + " reroll &7— перевыдать задачи всем игрокам сейчас"));
    }

    // ------------------------------------------------------------------ helpers

    private static QuestBoard.Period parsePeriodOrNull(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "daily", "day", "d", "день", "ежедневная", "ежедневное" -> QuestBoard.Period.DAILY;
            case "weekly", "week", "w", "неделя", "недельная", "недельное" -> QuestBoard.Period.WEEKLY;
            default -> null;
        };
    }

    private static String autoTitle(QuestDef.Type type, String material, int amount) {
        String target = (material == null || "ANY".equalsIgnoreCase(material)) ? "любых блоков/предметов" : material;
        return switch (type) {
            case BREAK_BLOCK -> ("ANY".equalsIgnoreCase(material) ? "Сломать блоки: " + amount + " шт." : "Сломать " + material + " x" + amount);
            case LOOT_AMOUNT -> ("ANY".equalsIgnoreCase(material) ? "Добыть ресурсы: " + amount + " шт." : "Добыть " + material + " x" + amount);
            case OBTAIN_ITEM -> ("ANY".equalsIgnoreCase(material) ? "Получить предметы: " + amount + " шт." : "Получить " + target + " x" + amount);
            case NODES_COMPLETED -> "Разработать " + amount + " узлов";
        };
    }

    private static String autoDescription(QuestDef.Type type, String material, int amount) {
        return switch (type) {
            case BREAK_BLOCK -> "ANY".equalsIgnoreCase(material)
                    ? "Сломайте " + amount + " блоков на узлах шахты во время смены"
                    : "Сломайте блок " + material + " (" + amount + " раз) в шахте";
            case LOOT_AMOUNT -> "ANY".equalsIgnoreCase(material)
                    ? "Добудьте " + amount + " любых ресурсов с узлов шахты"
                    : "Добудьте " + material + " (" + amount + " шт.) при разработке узлов";
            case OBTAIN_ITEM -> "ANY".equalsIgnoreCase(material)
                    ? "Получите " + amount + " предметов в шахте или из печи"
                    : "Получите " + material + " (" + amount + " шт.) в шахте или переплавкой";
            case NODES_COMPLETED -> "Полностью пройдите все стадии " + amount + " узлов шахты";
        };
    }

    private static String generateId(QuestBoard.Period period, QuestDef.Type type, String material, int amount) {
        String p = period == QuestBoard.Period.DAILY ? "d" : "w";
        String t = switch (type) {
            case BREAK_BLOCK -> "brk";
            case LOOT_AMOUNT -> "min";
            case OBTAIN_ITEM -> "get";
            case NODES_COMPLETED -> "nod";
        };
        String m = (material == null ? "node" : material.toLowerCase(Locale.ROOT));
        String shortRand = UUID.randomUUID().toString().substring(0, 4);
        return p + "_" + t + "_" + m + "_" + amount + "_" + shortRand;
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ TabComplete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean isAdmin = FcpPermissions.adminOr(sender, "fcp.admin.jobs.node");
        if (args.length == 1) {
            String pfx = args[0].toLowerCase(Locale.ROOT);
            List<String> base = isAdmin ? ADMIN_SUBS : List.of("claim");
            return base.stream().filter(s -> s.startsWith(pfx)).collect(Collectors.toList());
        }
        if (!isAdmin) return List.of();

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("create") || sub.equals("add")) {
            int offset = parsePeriodOrNull(args[1]) != null ? 2 : 1;
            if (args.length == 2) {
                String pfx = args[1].toLowerCase(Locale.ROOT);
                List<String> opts = new ArrayList<>(ACTIONS_TAB);
                opts.addAll(PERIODS_TAB);
                return opts.stream().filter(s -> s.startsWith(pfx)).collect(Collectors.toList());
            }
            if (args.length == offset + 1) {
                String pfx = args[args.length - 1].toLowerCase(Locale.ROOT);
                return ACTIONS_TAB.stream().filter(s -> s.startsWith(pfx)).collect(Collectors.toList());
            }
            if (args.length == offset + 2) {
                String action = args[offset].toLowerCase(Locale.ROOT);
                boolean blocksOnly = action.equals("сломать") || action.equals("break");
                String pfx = args[args.length - 1].toUpperCase(Locale.ROOT);
                List<String> mats = new ArrayList<>();
                mats.add("ANY");
                mats.add("NODE");
                for (Material m : Material.values()) {
                    if (m.isAir()) continue;
                    if (blocksOnly ? m.isBlock() : (m.isItem() || m.isBlock())) {
                        mats.add(m.name());
                    }
                }
                return mats.stream().filter(s -> s.startsWith(pfx)).limit(60).collect(Collectors.toList());
            }
            if (args.length == offset + 3) {
                return List.of("16", "32", "64", "128", "512", "1024", "65536");
            }
            if (args.length == offset + 4) {
                return List.of("100", "250", "500", "1000", "2500");
            }
            if (args.length == offset + 5 && offset == 1) {
                String pfx = args[args.length - 1].toLowerCase(Locale.ROOT);
                return PERIODS_TAB.stream().filter(s -> s.startsWith(pfx)).collect(Collectors.toList());
            }
        } else if (sub.equals("list") || sub.equals("remove") || sub.equals("delete") || sub.equals("count")) {
            if (args.length == 2) {
                String pfx = args[1].toLowerCase(Locale.ROOT);
                return PERIODS_TAB.stream().filter(s -> s.startsWith(pfx)).collect(Collectors.toList());
            }
            if ((sub.equals("remove") || sub.equals("delete")) && args.length == 3) {
                QuestBoard.Period p = parsePeriodOrNull(args[1]);
                List<QuestDef> pool = p == QuestBoard.Period.WEEKLY ? settings.weeklyPool() : settings.dailyPool();
                List<String> out = new ArrayList<>();
                for (int i = 0; i < pool.size(); i++) {
                    out.add(String.valueOf(i + 1));
                    out.add(pool.get(i).id);
                }
                String pfx = args[2].toLowerCase(Locale.ROOT);
                return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(pfx)).collect(Collectors.toList());
            }
            if (sub.equals("count") && args.length == 3) {
                return List.of("1", "2", "3", "4", "5", "6", "7");
            }
        } else if (sub.equals("clear") && args.length == 2) {
            String pfx = args[1].toLowerCase(Locale.ROOT);
            return List.of("daily", "weekly", "all").stream().filter(s -> s.startsWith(pfx)).collect(Collectors.toList());
        }
        return List.of();
    }
}

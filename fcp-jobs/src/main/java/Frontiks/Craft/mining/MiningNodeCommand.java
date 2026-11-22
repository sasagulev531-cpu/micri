package Frontiks.Craft.mining;

import Frontiks.Craft.api.FcpPermissions;
import Frontiks.Craft.utils.color;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * /miningnode — администрирование добываемых узлов шахты.
 * Все подкоманды доступны только привилегированным игрокам (fcp.admin.jobs.node).
 */
public final class MiningNodeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "wand", "list", "info", "create", "delete", "restore",
            "validate", "reload", "cooldown", "loot", "help");
    private static final List<String> LOOT_SUB = List.of("list", "add", "remove", "clear");

    private final JavaPlugin plugin;
    private final MiningNodeManager manager;

    public MiningNodeCommand(JavaPlugin plugin, MiningNodeManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(color.color("&cТолько для игроков!"));
            return true;
        }
        if (!FcpPermissions.adminOr(p, "fcp.admin.jobs.node")) {
            p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cНет прав (fcp.admin.jobs.node)."));
            return true;
        }

        if (args.length == 0) {
            sendHelp(p, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "wand" -> {
                p.getInventory().addItem(MiningWand.create(plugin));
                p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aВы получили маркер шахты. Кликайте им по блоку в зоне."));
            }
            case "list" -> listNodes(p);
            case "info" -> {
                MiningNode node = targetNode(p);
                if (node != null) sendNodeInfo(p, node);
            }
            case "create" -> {
                Block b = targetBlock(p);
                if (b == null) return true;
                manager.handleWandClick(p, b, true);
            }
            case "delete" -> {
                Block b = targetBlock(p);
                if (b == null) return true;
                MiningNode removed = manager.deleteNodeAt(b.getLocation());
                if (removed == null) {
                    p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cНа этом блоке нет узла."));
                } else {
                    p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aУзел " + manager.shortId(removed.id) + " удалён."));
                }
            }
            case "restore" -> {
                MiningNode node = targetNode(p);
                if (node == null) return true;
                try {
                    manager.forceRestore(node);
                    p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aУзел восстановлен."));
                } catch (MiningNodeManager.MiningNodeException ex) {
                    p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&c" + ex.getMessage()));
                }
            }
            case "validate" -> {
                for (String line : manager.validateAll()) {
                    p.sendMessage(color.color("&7- " + line));
                }
            }
            case "reload" -> {
                manager.reload();
                p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aКонфигурация и узлы перезагружены."));
            }
            case "cooldown" -> cooldown(p, args);
            case "loot" -> loot(p, args);
            default -> sendHelp(p, label);
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void listNodes(Player p) {
        List<MiningNode> all = manager.getAllNodes();
        if (all.isEmpty()) {
            p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&7Узлов пока нет. Выдайте себе wand: /miningnode wand"));
            return;
        }
        p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&fУзлы шахты (&e" + all.size() + "&f):"));
        int shown = 0;
        for (MiningNode n : all) {
            if (shown >= 12) {
                p.sendMessage(color.color("&7… и ещё " + (all.size() - shown) + ". Используйте /miningnode info наведясь на блок."));
                break;
            }
            String state = n.broken ? "&cдесинк" : (n.depleted ? "&eистощён" : "&aактивен");
            p.sendMessage(color.color("&8» &f" + manager.shortId(n.id) + " &7" + n.worldName + " "
                    + n.x + " " + n.y + " " + n.z + " " + state));
            shown++;
        }
    }

    private void sendNodeInfo(Player p, MiningNode node) {
        p.sendMessage(color.color("&8---- &6Узел " + manager.shortId(node.id) + " &8----"));
        p.sendMessage(color.color("&7Мир: &f" + node.worldName + " &8(" + node.worldUuid + ")"));
        p.sendMessage(color.color("&7Координаты: &f" + node.x + " " + node.y + " " + node.z));
        p.sendMessage(color.color("&7Цепочка: &f" + String.join(" &8→ &f", node.chain)
                + " &8→ &7(истощён: &f" + node.depletedMaterial + "&7)"));
        String state = node.broken ? "&cДЕСИНХРОНИЗИРОВАН"
                : node.depleted ? "&eИстощён (ещё ~" + Math.max(0, (node.depletedUntilMs - System.currentTimeMillis()) / 1000) + " с)"
                : "&aАктивен (стадия " + (NodeLogic.clampIndex(node) + 1) + "/" + node.chain.size() + ")";
        p.sendMessage(color.color("&7Состояние: " + state));
        p.sendMessage(color.color("&7Кулдаун: &f" + (node.cooldownMillis / 1000) + " с"));
        if (node.loot.isEmpty()) {
            p.sendMessage(color.color("&cЛут пуст — награды не будет."));
        } else {
            p.sendMessage(color.color("&7Лут:"));
            for (int i = 0; i < node.loot.size(); i++) {
                LootEntry e = node.loot.get(i);
                p.sendMessage(color.color("&8" + (i + 1) + ". &f" + e.material + " &7x" + e.min + "–" + e.max
                        + " &8(" + Math.round(e.chance * 100) + "%)"));
            }
        }
    }

    private void cooldown(Player p, String[] args) {
        MiningNode node = targetNode(p);
        if (node == null) return;
        if (args.length < 2) {
            p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&7Кулдаун узла: &f" + (node.cooldownMillis / 1000) + " с. Изменить: /miningnode cooldown <сек>"));
            return;
        }
        Integer seconds = parseInt(args[1]);
        if (seconds == null || seconds < 0 || seconds > 86400) {
            p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cКулдаун: целое число 0..86400 секунд."));
            return;
        }
        manager.setCooldownSeconds(node, seconds);
        p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aКулдаун узла: " + seconds + " с."));
    }

    private void loot(Player p, String[] args) {
        MiningNode node = targetNode(p);
        if (node == null) return;
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            sendNodeInfo(p, node);
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add" -> {
                if (args.length < 6) {
                    p.sendMessage(color.color("&cИспользование: /miningnode loot add <материал> <min> <max> <шанс 0..1>"));
                    p.sendMessage(color.color("&7Пример: /miningnode loot add COBBLESTONE 2 4 1.0"));
                    return;
                }
                Material material = Material.matchMaterial(args[2].trim().toUpperCase(Locale.ROOT));
                Integer min = parseInt(args[3]);
                Integer max = parseInt(args[4]);
                Double chance = parseDouble(args[5]);
                if (material == null) {
                    p.sendMessage(color.color("&cНеизвестный материал: " + args[2]));
                    return;
                }
                if (min == null || max == null || chance == null || min < 1 || max < min || chance <= 0.0 || chance > 1.0) {
                    p.sendMessage(color.color("&cНекорректные значения: min>=1, max>=min, 0<шанс<=1."));
                    return;
                }
                List<LootEntry> loot = new ArrayList<>(node.loot);
                loot.add(new LootEntry(material.name(), min, max, chance));
                try {
                    manager.setLoot(node, loot);
                    p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aЛут добавлен: " + material.name()));
                } catch (MiningNodeManager.MiningNodeException ex) {
                    p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&c" + ex.getMessage()));
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    p.sendMessage(color.color("&cИспользование: /miningnode loot remove <номер>"));
                    return;
                }
                Integer index = parseInt(args[2]);
                if (index == null || index < 1 || index > node.loot.size()) {
                    p.sendMessage(color.color("&cНомер от 1 до " + node.loot.size()));
                    return;
                }
                List<LootEntry> loot = new ArrayList<>(node.loot);
                LootEntry removed = loot.remove(index - 1);
                try {
                    manager.setLoot(node, loot);
                    p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aУдалено: " + removed.material));
                } catch (MiningNodeManager.MiningNodeException ex) {
                    // Лут не может стать пустым через setLoot — обходим валидацию через clear semantics:
                    if (loot.isEmpty()) {
                        manager.clearLoot(node);
                        p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aЛут очищен."));
                    } else {
                        p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&c" + ex.getMessage()));
                    }
                }
            }
            case "clear" -> {
                manager.clearLoot(node);
                p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aЛут узла очищен (задания по луту не будут выполняться этой ломкой)."));
            }
            default -> p.sendMessage(color.color("&cНеизвестная подкоманда loot: " + sub + ". Доступно: list, add, remove, clear"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private MiningNode targetNode(Player p) {
        Block b = targetBlock(p);
        if (b == null) return null;
        MiningNode node = manager.getNodeAt(b.getLocation());
        if (node == null) {
            p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cЭто не узел шахты. Наведитесь на блок узла."));
        }
        return node;
    }

    private Block targetBlock(Player p) {
        Block b = p.getTargetBlockExact(8);
        if (b == null) {
            p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cНаведитесь на блок (до 8 метров)."));
        }
        return b;
    }

    private void sendHelp(Player p, String label) {
        p.sendMessage(color.color("&8---- &6/" + label + " &8----"));
        p.sendMessage(color.color("&e/" + label + " wand &7— получить инструмент настройки"));
        p.sendMessage(color.color("&e/" + label + " list &7— список узлов"));
        p.sendMessage(color.color("&e/" + label + " info &7— информация об узле в прицеле"));
        p.sendMessage(color.color("&e/" + label + " create &7— настроить узел на блоке в прицеле"));
        p.sendMessage(color.color("&e/" + label + " delete &7— удалить узел на блоке в прицеле"));
        p.sendMessage(color.color("&e/" + label + " restore &7— восстановить истощённый узел"));
        p.sendMessage(color.color("&e/" + label + " validate &7— проверить конфиг и узлы"));
        p.sendMessage(color.color("&e/" + label + " reload &7— перечитать hrabota.yml и узлы"));
        p.sendMessage(color.color("&e/" + label + " cooldown [сек] &7— показать/задать кулдаун узла"));
        p.sendMessage(color.color("&e/" + label + " loot list|add|remove|clear &7— настройка лута узла"));
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    private static Double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return null; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || !FcpPermissions.adminOr((Player) sender, "fcp.admin.jobs.node")) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("loot")) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return LOOT_SUB.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args[1].equalsIgnoreCase("add") && args.length == 3) {
                String prefix = args[2].toUpperCase(Locale.ROOT);
                return materialNames().stream()
                        .filter(s -> s.startsWith(prefix))
                        .limit(50)
                        .collect(Collectors.toList());
            }
        }
        return List.of();
    }

    private static List<String> materialNames() {
        List<String> names = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isItem() || m.isBlock()) names.add(m.name());
        }
        return names;
    }
}

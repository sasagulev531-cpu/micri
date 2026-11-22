package Frontiks.Craft.mining;

import Frontiks.Craft.gui.MenuManager;
import Frontiks.Craft.utils.color;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Админское GUI настройки одного узла (27 слотов).
 * Состояние открытых меню хранится в статической карте (по паттерну существующего MenuManager).
 */
public final class MiningNodeMenu {

    public static final String MENU_ID = "mining_node_menu";

    static final class State {
        String nodeId;
        boolean confirmDelete;
        long confirmUntil;
        int palettePage; // смещение в палитре для быстрого перебора (не используется, палитра линейна)
    }

    private static final Map<UUID, State> OPEN = new HashMap<>();

    public static State stateOf(UUID uuid) { return OPEN.get(uuid); }
    public static void openState(UUID uuid, String nodeId) {
        State st = OPEN.computeIfAbsent(uuid, k -> new State());
        st.nodeId = nodeId;
    }
    public static void clearState(UUID uuid) { OPEN.remove(uuid); }

    private final JavaPlugin plugin;
    private final MiningNodeManager manager;
    private final Player viewer;
    private final MiningNode node;

    public MiningNodeMenu(JavaPlugin plugin, MiningNodeManager manager, Player viewer, MiningNode node) {
        this.plugin = plugin;
        this.manager = manager;
        this.viewer = viewer;
        this.node = node;
    }

    public void open() {
        State st = OPEN.computeIfAbsent(viewer.getUniqueId(), k -> new State());
        st.nodeId = node.id;
        Inventory inv = Bukkit.createInventory(null, 27, color.color("&8Настройка узла"));

        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        // Стадии цепочки (слоты 9..16)
        List<String> chain = node.chain;
        int slot = 9;
        for (int i = 0; i < chain.size() && slot <= 16; i++, slot++) {
            Material material = Material.matchMaterial(chain.get(i));
            if (material == null || !material.isBlock()) material = Material.BARRIER;
            inv.setItem(slot, named(material, "&eСтадия #" + (i + 1) + " &8— &f" + chain.get(i),
                    List.of("&7ЛКМ — следующий материал из палитры",
                            "&7ПКМ — предыдущий материал",
                            "&7Shift+ЛКМ — удалить стадию")));
        }
        if (chain.size() < 16 && slot <= 16) {
            inv.setItem(slot, named(Material.LIME_DYE, "&aДобавить стадию",
                    List.of("&7Добавит стадию TUFF в конец цепочки", "&7и приведёт блок к ней")));
        }

        // Истощённый вид (слот 18)
        Material depleted = Material.matchMaterial(node.depletedMaterial);
        if (depleted == null || !depleted.isBlock()) depleted = Material.BARRIER;
        inv.setItem(18, named(depleted, "&7Истощённый вид: &f" + node.depletedMaterial,
                List.of("&7Показывает, во что превращается блок,", "&7когда узел добыт. Лут и бездроп", "&7при этом уже выданы.", "&7ЛКМ/ПКМ — сменить материал")));

        // Кулдаун (слот 19)
        int cooldown = (int) (node.cooldownMillis / 1000L);
        inv.setItem(19, named(Material.CLOCK, "&eКулдаун: &f" + cooldown + " с",
                List.of("&7Через сколько секунд узел вернётся", "&7к первой стадии.", "&7ЛКМ — +5 с, ПКМ — −5 с")));

        // Лут (слот 20) — только просмотр, редактирование командами
        List<String> lootLore = new ArrayList<>();
        lootLore.add("&7Выдаётся при поломке последней стадии.");
        lootLore.add("&7Команды:");
        lootLore.add("&f/miningnode loot list");
        lootLore.add("&f/miningnode loot add <материал> <min> <max> <шанс 0..1>");
        lootLore.add("&f/miningnode loot remove <№>");
        lootLore.add("&f/miningnode loot clear");
        if (node.loot.isEmpty()) {
            lootLore.add("&cЛут ПУСТ — награды не будет!");
        } else {
            lootLore.add("&fТекущий лут:");
            for (LootEntry e : node.loot) {
                lootLore.add("&8• &f" + e.material + " &7x" + e.min + "–" + e.max
                        + " &8(" + Math.round(e.chance * 100) + "%)");
            }
        }
        inv.setItem(20, named(Material.CHEST, "&6Лут узла", lootLore));

        // Принудительное восстановление (слот 21)
        if (node.depleted) {
            long leftSec = Math.max(0, (node.depletedUntilMs - System.currentTimeMillis()) / 1000);
            inv.setItem(21, named(Material.TOTEM_OF_UNDYING, "&aВосстановить сейчас",
                    List.of("&7Узел истощён. Осталось: &f" + leftSec + " с", "&7ЛКМ — восстановить немедленно")));
        } else {
            inv.setItem(21, named(Material.GRAY_DYE, "&7Узел не истощён",
                    List.of("&7Восстановление доступно только", "&7для истощённого узла")));
        }

        // Информация (слот 22)
        List<String> info = new ArrayList<>();
        info.add("&7ID: &f" + manager.shortId(node.id));
        info.add("&7Мир: &f" + node.worldName);
        info.add("&7Координаты: &f" + node.x + " " + node.y + " " + node.z);
        info.add("&7Состояние: " + stateText());
        info.add("&7Стадий: &f" + node.chain.size());
        if (node.broken) info.add("&cДесинхронизирован — блок изменён извне.");
        inv.setItem(22, named(Material.PAPER, "&fИнформация об узле", info));

        // Удаление (слот 23) с двухшаговым подтверждением
        boolean confirm = st.confirmDelete && System.currentTimeMillis() < st.confirmUntil;
        inv.setItem(23, named(confirm ? Material.RED_CONCRETE : Material.BARRIER,
                confirm ? "&c&lПОДТВЕРДИТЬ УДАЛЕНИЕ?" : "&cУдалить узел",
                confirm
                        ? List.of("&7Shift+ЛКМ — удалить безвозвратно", "&7Меню закроется")
                        : List.of("&7Shift+ЛКМ — запросить подтверждение")));

        // Закрыть (слот 26)
        inv.setItem(26, named(Material.OAK_DOOR, "&7Закрыть", List.of("&7Закрыть меню")));

        viewer.openInventory(inv);
        OPEN.put(viewer.getUniqueId(), st);
        MenuManager.setMenu(viewer.getUniqueId(), MENU_ID);
    }

    private String stateText() {
        if (node.broken) return "&cДесинхронизирован";
        if (node.depleted) {
            long leftSec = Math.max(0, (node.depletedUntilMs - System.currentTimeMillis()) / 1000);
            return "&eИстощён (~" + leftSec + " с)";
        }
        return "&aАктивен (стадия " + (NodeLogic.clampIndex(node) + 1) + "/" + node.chain.size() + ")";
    }

    static ItemStack named(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material == null ? Material.BARRIER : material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(color.color(displayName));
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore.stream().map(color::color).collect(java.util.stream.Collectors.toList()));
        }
        if (material != null && material == Material.WRITABLE_BOOK) {
            // косметика
        }
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack glowing(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            try {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);
            } catch (Throwable ignored) {
                // свечение косметически, не критично
            }
        }
        return item;
    }
}

package Frontiks.Craft.quests;

import Frontiks.Craft.gui.MenuManager;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.utils.color;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI «Задания шахтёра»: ежедневные и недельные задания, прогресс, дедлайн и награды.
 * Открывается из меню работы (слот 16) и командой /quests.
 */
public final class QuestsMenu {

    public static final String MENU_ID = "quests_menu";

    private final QuestManager quests;
    private final MiningSettings settings;

    public QuestsMenu(QuestManager quests, MiningSettings settings) {
        this.quests = quests;
        this.settings = settings;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, 36, color.color("&8Задания шахтёра"));
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 36; i++) inv.setItem(i, glass);

        // Инфо (слот 4)
        inv.setItem(4, named(Material.PAPER, "&6&lЗадания шахтёра", List.of(
                "&7Выполняйте задания, добывая узлы",
                "&7и получая лут в шахте.",
                "&7Награда начисляется в &fмонеты&7 (coins).",
                "&7Часовой пояс: &f" + settings.timezone())));

        // Шапка ежедневных (слот 9)
        inv.setItem(9, named(Material.SUNFLOWER, "&e&lЕжедневные", List.of(
                "&7Сброс: &f" + quests.resetText(QuestBoard.Period.DAILY))));
        // Шапка недельных (слот 18)
        inv.setItem(18, named(Material.COMPASS, "&6&lНедельные", List.of(
                "&7Сброс (понедельник 00:00): &f" + quests.resetText(QuestBoard.Period.WEEKLY))));

        List<QuestManager.ResolvedQuest> resolved = quests.resolvedQuests(player);

        int dailySlot = 10, weeklySlot = 19;

        for (QuestManager.ResolvedQuest rq : resolved) {
            ItemStack item = questItem(rq);
            if (rq.period() == QuestBoard.Period.DAILY && dailySlot <= 16) {
                inv.setItem(dailySlot++, item);
            } else if (rq.period() == QuestBoard.Period.WEEKLY && weeklySlot <= 25) {
                inv.setItem(weeklySlot++, item);
            }
        }

        // Кнопка повторной выплаты (слот 31)
        inv.setItem(31, named(Material.WRITABLE_BOOK, "&aПроверить награды", List.of(
                "&7Если задание выполнено, но монеты",
                "&7не пришли — попробуйте ещё раз.",
                "&7ЛКМ — проверить")));

        // Закрыть (слот 35)
        inv.setItem(35, named(Material.OAK_DOOR, "&7Закрыть", List.of("&7Закрыть меню")));

        player.openInventory(inv);
        MenuManager.setMenu(player.getUniqueId(), MENU_ID);
    }

    private ItemStack questItem(QuestManager.ResolvedQuest rq) {
        QuestDef def = rq.def();
        QuestAssignment state = rq.state();
        List<String> lore = new ArrayList<>();
        if (def.description != null && !def.description.isBlank()) {
            lore.add("&7" + def.description);
            lore.add("");
        }
        lore.add("&7Прогресс: &f" + state.progress + "&7/&f" + def.amount);
        lore.add("&7Награда: &6" + def.reward + " монет");
        if (state.completed) {
            if (state.paid) {
                lore.add("&aВыполнено и оплачено ✔");
            } else {
                lore.add("&cВыполнено, но выплата не прошла!");
                lore.add("&7Нажмите «Проверить награды» ниже.");
            }
        } else {
            lore.add("&eВ процессе…");
        }

        ItemStack item = named(Material.WRITTEN_BOOK, (state.completed ? "&a" : "&f") + def.title, lore);
        if (state.completed && state.paid) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                try {
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    item.setItemMeta(meta);
                } catch (Throwable ignored) {
                    // свечение косметически, не критично
                }
            }
        }
        return item;
    }

    static ItemStack named(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material == null ? Material.BARRIER : material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(color.color(displayName));
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore.stream().map(color::color).collect(java.util.stream.Collectors.toList()));
        }
        item.setItemMeta(meta);
        return item;
    }
}

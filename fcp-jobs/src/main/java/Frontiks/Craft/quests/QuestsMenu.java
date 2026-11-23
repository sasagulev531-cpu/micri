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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * GUI «Задачи шахтёра»:
 * - Ежедневные задачи шахты (выполняются сразу на смене);
 * - Еженедельные сложные цепочки этапов (нужно кликнуть ЛКМ, чтобы взять задание).
 */
public final class QuestsMenu {

    public static final String MENU_ID = "quests_menu";
    private static final Map<UUID, Map<Integer, String>> SLOT_QUESTS = new ConcurrentHashMap<>();

    private final QuestManager quests;
    private final MiningSettings settings;

    public QuestsMenu(QuestManager quests, MiningSettings settings) {
        this.quests = quests;
        this.settings = settings;
    }

    public static String questIdAt(UUID playerId, int slot) {
        Map<Integer, String> map = SLOT_QUESTS.get(playerId);
        return map == null ? null : map.get(slot);
    }

    public static void clearState(UUID playerId) {
        SLOT_QUESTS.remove(playerId);
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, 36, color.color("&8Задачи шахтёра"));
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 36; i++) inv.setItem(i, glass);

        inv.setItem(4, named(Material.PAPER, "&6&lЗадачи шахтёра", List.of(
                "&7• &eЕжедневные задачи шахты &7выполняются",
                "&7  автоматически во время рабочей смены.",
                "&7• &6Еженедельные цепочки &7нужно сначала",
                "&7  &aвзять (ЛКМ)&7, после чего этапы идут по очереди!"
        )));

        inv.setItem(9, named(Material.SUNFLOWER, "&e&lЕжедневные задачи", List.of(
                "&7Сброс: &f" + quests.resetText(QuestBoard.Period.DAILY),
                "&7Касаются шахты — зачёт идёт сразу на смене"
        )));

        inv.setItem(18, named(Material.BLAST_FURNACE, "&6&lЕженедельные цепочки", List.of(
                "&7Сброс (понедельник 00:00): &f" + quests.resetText(QuestBoard.Period.WEEKLY),
                "&7Нажмите на задание, чтобы взять его!"
        )));

        List<QuestManager.ResolvedQuest> resolved = quests.resolvedQuests(player);
        Map<Integer, String> slotMap = new HashMap<>();

        int dailySlot = 10;
        int weeklySlot = 19;

        for (QuestManager.ResolvedQuest rq : resolved) {
            ItemStack item = questItem(rq);
            if (rq.period() == QuestBoard.Period.DAILY && dailySlot <= 16) {
                slotMap.put(dailySlot, rq.def().id);
                inv.setItem(dailySlot++, item);
            } else if (rq.period() == QuestBoard.Period.WEEKLY && weeklySlot <= 25) {
                slotMap.put(weeklySlot, rq.def().id);
                inv.setItem(weeklySlot++, item);
            }
        }

        inv.setItem(31, named(Material.GOLD_INGOT, "&aПроверить награды", List.of(
                "&7Если задание выполнено, но награда",
                "&7не пришла — нажмите здесь."
        )));

        inv.setItem(35, named(Material.OAK_DOOR, "&7Закрыть", List.of("&7Закрыть меню")));

        player.openInventory(inv);
        SLOT_QUESTS.put(player.getUniqueId(), slotMap);
        MenuManager.setMenu(player.getUniqueId(), MENU_ID);
    }

    private ItemStack questItem(QuestManager.ResolvedQuest rq) {
        QuestDef def = rq.def();
        QuestAssignment state = rq.state();
        boolean active = state.accepted || !def.requireAccept;

        List<String> lore = new ArrayList<>();
        if (!def.descriptionLines.isEmpty()) {
            for (String line : def.descriptionLines) {
                lore.add("&7" + line);
            }
            lore.add("");
        }

        // Отображение цепочки этапов (или одиночного этапа)
        if (def.steps.size() == 1) {
            QuestStep step = def.steps.get(0);
            int shownProgress = state.completed ? step.amount : state.progress;
            lore.add("&7Цель: &f" + step.title);
            lore.add("&7Прогресс: &e" + shownProgress + "&7/&f" + step.amount);
        } else {
            lore.add("&fЭтапы цепочки (" + def.steps.size() + "):");
            int curIdx = Math.max(0, Math.min(state.stepIndex, def.steps.size() - 1));
            for (int i = 0; i < def.steps.size(); i++) {
                QuestStep step = def.steps.get(i);
                if (state.completed || i < curIdx) {
                    lore.add("&a ✔ " + (i + 1) + ". " + step.title + " &8(" + step.amount + "/" + step.amount + ")");
                } else if (i == curIdx && active) {
                    lore.add("&e ▶ " + (i + 1) + ". &f" + step.title + " &e(" + state.progress + "/" + step.amount + ")");
                } else if (i == curIdx) {
                    lore.add("&7 • " + (i + 1) + ". " + step.title + " &8(0/" + step.amount + ")");
                } else {
                    lore.add("&8 🔒 " + (i + 1) + ". " + step.title + " (0/" + step.amount + ")");
                }
            }
        }

        lore.add("");
        if (def.reward > 0) {
            lore.add("&7Награда: &6" + def.reward + " монет");
        }
        if (!def.rewardItems.isEmpty()) {
            lore.add("&7Предметы: &b" + String.join(", ", def.rewardItems));
        }
        lore.add("");

        if (state.completed) {
            if (state.paid) {
                lore.add("&a&l✔ ВЫПОЛНЕНО И ПОЛУЧЕНО");
            } else {
                lore.add("&cВыполнено! Нажмите ЛКМ, чтобы получить награду.");
            }
        } else if (!active) {
            lore.add("&e&l▶ НАЖМИТЕ ЛКМ, ЧТОБЫ ВЗЯТЬ ЗАДАНИЕ");
            lore.add("&8(Пока не взято — прогресс не засчитывается)");
        } else {
            lore.add("&aАктивно — выполняйте текущий этап!");
        }

        Material iconMat = resolveIcon(def, state, active);
        String prefix = state.completed ? "&a" : (active ? "&e" : "&f");
        ItemStack item = named(iconMat, prefix + def.title, lore);

        if ((state.completed && state.paid) || (def.requireAccept && active && !state.completed)) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                try {
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    item.setItemMeta(meta);
                } catch (Throwable ignored) {
                }
            }
        }
        return item;
    }

    private static Material resolveIcon(QuestDef def, QuestAssignment state, boolean active) {
        if (def.icon != null) {
            Material custom = Material.matchMaterial(def.icon);
            if (custom != null && custom.isItem()) return custom;
        }
        if (state.completed) return Material.ENCHANTED_BOOK;
        return active ? Material.WRITTEN_BOOK : Material.BOOK;
    }

    static ItemStack named(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material == null ? Material.BOOK : material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(color.color(displayName));
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore.stream().map(color::color).collect(Collectors.toList()));
        }
        item.setItemMeta(meta);
        return item;
    }
}

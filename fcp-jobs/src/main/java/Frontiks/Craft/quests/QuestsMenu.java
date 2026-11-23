package Frontiks.Craft.quests;

import Frontiks.Craft.gui.MenuManager;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.quests.v2.PlayerQuestProfile.QuestProgressState;
import Frontiks.Craft.quests.v2.QuestEngineV2;
import Frontiks.Craft.quests.v2.QuestEngineV2.LifecycleState;
import Frontiks.Craft.quests.v2.QuestEngineV2.QuestView;
import Frontiks.Craft.quests.v2.QuestV2Models.*;
import Frontiks.Craft.utils.color;
import Frontiks.Craft.work.WorkManager;
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
 * GUI «Задачи шахтёра» v2 (54 слота с поддержкой страниц, категорий, цепочек, all/any/time и пререквизитов).
 */
public final class QuestsMenu {

    public static final String MENU_ID = "quests_menu";
    public static final int INV_SIZE = 54;
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private static final Map<UUID, Map<Integer, String>> SLOT_QUESTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> CURRENT_PAGE = new ConcurrentHashMap<>();

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

    public static int pageOf(UUID playerId) {
        return CURRENT_PAGE.getOrDefault(playerId, 0);
    }

    public static void clearState(UUID playerId) {
        SLOT_QUESTS.remove(playerId);
    }

    public void open(Player player) {
        open(player, CURRENT_PAGE.getOrDefault(player.getUniqueId(), 0));
    }

    public void open(Player player, int requestedPage) {
        List<QuestView> views = quests.viewsFor(player);
        int perPage = CONTENT_SLOTS.length;
        int totalPages = Math.max(1, (views.size() + perPage - 1) / perPage);
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        String titleSuffix = totalPages > 1 ? " &8(" + (page + 1) + "/" + totalPages + ")" : "";
        Inventory inv = Bukkit.createInventory(null, INV_SIZE, color.color("&8Задачи шахтёра" + titleSuffix));
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < INV_SIZE; i++) inv.setItem(i, glass);

        boolean working = WorkManager.isWorking(player);
        inv.setItem(4, named(Material.IRON_PICKAXE, "&6&lЗадачи шахтёра", List.of(
                "&7Статус смены: " + (working ? "&aНа смене (Шахтёр)" : "&cНе на смене (&e/work&c)"),
                "&7Сброс ежедневных: &f" + quests.resetTextForCategory(player, "daily"),
                "&7Сброс еженедельных: &f" + quests.resetTextForCategory(player, "weekly"),
                "",
                "&7• &aАвто-задачи &7идут сразу на смене в шахте.",
                "&7• &eРучные задачи &7нужно сначала &fвзять (ЛКМ)&7."
        )));

        Map<Integer, String> slotMap = new HashMap<>();
        int startIdx = page * perPage;
        int endIdx = Math.min(views.size(), startIdx + perPage);

        for (int i = startIdx; i < endIdx; i++) {
            int guiSlot = CONTENT_SLOTS[i - startIdx];
            QuestView view = views.get(i);
            slotMap.put(guiSlot, view.spec().id());
            inv.setItem(guiSlot, buildQuestCard(player, view));
        }

        if (page > 0) {
            inv.setItem(45, named(Material.ARROW, "&e« Предыдущая страница", List.of("&7Страница " + page)));
        }
        if (page + 1 < totalPages) {
            inv.setItem(53, named(Material.ARROW, "&eСледующая страница »", List.of("&7Страница " + (page + 2))));
        }

        inv.setItem(49, named(Material.GOLD_INGOT, "&aПроверить награды", List.of(
                "&7Если задание выполнено, но награда",
                "&7не пришла — нажмите здесь."
        )));
        inv.setItem(50, named(Material.OAK_DOOR, "&7Закрыть", List.of("&7Закрыть меню")));

        player.openInventory(inv);
        CURRENT_PAGE.put(player.getUniqueId(), page);
        SLOT_QUESTS.put(player.getUniqueId(), slotMap);
        MenuManager.setMenu(player.getUniqueId(), MENU_ID);
    }

    private ItemStack buildQuestCard(Player player, QuestView view) {
        QuestSpec spec = view.spec();
        QuestProgressState st = view.state();
        LifecycleState lc = view.lifecycle();

        List<String> lore = new ArrayList<>();
        lore.add("&8Категория: " + formatCategoryLabel(view.period(), quests.resetTextForCategory(player, spec.category())));

        if (spec.requires().jobId() != null && !spec.requires().jobId().isBlank()) {
            lore.add("&8Роль: &e" + spec.requires().jobId());
        }

        if (!spec.description().isEmpty()) {
            lore.add("");
            for (String line : spec.description()) {
                lore.add("&7" + line);
            }
        }

        if (!view.lockReasons().isEmpty()) {
            lore.add("");
            for (String reason : view.lockReasons()) {
                lore.add("&c🔒 " + reason);
            }
        }

        lore.add("");
        List<StepSpec> steps = spec.steps();
        int curStepIdx = Math.max(0, Math.min(st.stepIndex, steps.size() - 1));

        if (steps.size() == 1) {
            appendSingleStepLore(lore, steps.get(0), st, lc == LifecycleState.COMPLETED);
        } else {
            lore.add("&fЭтапы цепочки (" + steps.size() + "):");
            for (int i = 0; i < steps.size(); i++) {
                StepSpec step = steps.get(i);
                if (lc == LifecycleState.COMPLETED || i < curStepIdx) {
                    lore.add("&a ✔ " + (i + 1) + ". " + step.name());
                } else if (i == curStepIdx && lc == LifecycleState.IN_PROGRESS) {
                    lore.add("&e ▶ " + (i + 1) + ". &f" + step.name());
                    appendStepDetailsIndented(lore, step, st);
                } else if (i == curStepIdx) {
                    lore.add("&7 • " + (i + 1) + ". " + step.name());
                    appendStepDetailsIndented(lore, step, st);
                } else {
                    lore.add("&8 🔒 " + (i + 1) + ". " + step.name());
                }
            }
        }

        // Награды
        RewardsSpec rew = spec.rewards();
        if (rew.coins() > 0 || !rew.items().isEmpty()) {
            lore.add("");
            if (rew.coins() > 0) {
                lore.add("&7Награда: &6" + rew.coins() + " монет");
            }
            for (RewardItemSpec it : rew.items()) {
                String pct = it.chance() < 1.0 ? " &8(" + Math.round(it.chance() * 100.0) + "%)" : "";
                lore.add("&7Предмет: &b" + it.material() + " x" + it.amount() + pct);
            }
        }

        lore.add("");
        switch (lc) {
            case LOCKED -> lore.add("&cНедоступно (выполните условия выше)");
            case AVAILABLE -> {
                lore.add("&e&l▶ НАЖМИТЕ ЛКМ, ЧТОБЫ ВЗЯТЬ ЗАДАНИЕ");
                lore.add("&8(Пока не взято — прогресс не засчитывается)");
            }
            case IN_PROGRESS -> lore.add("&aАктивно — выполняйте текущий этап!");
            case COMPLETED -> {
                if (st.paid) {
                    lore.add("&a&l✔ ВЫПОЛНЕНО И ПОЛУЧЕНО");
                } else {
                    lore.add("&cВыполнено! Нажмите ЛКМ, чтобы получить награду.");
                }
            }
        }

        Material icon = resolveIcon(spec, lc);
        String colorPrefix = switch (lc) {
            case COMPLETED -> "&a";
            case IN_PROGRESS -> "&e";
            case AVAILABLE -> "&f";
            case LOCKED -> "&8";
        };
        ItemStack item = named(icon, colorPrefix + spec.title(), lore);

        if ((lc == LifecycleState.COMPLETED && st.paid)
                || (lc == LifecycleState.IN_PROGRESS && spec.manualAccept())) {
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

    private static void appendSingleStepLore(List<String> lore, StepSpec step, QuestProgressState st, boolean done) {
        if (step.groupMode() == GroupMode.ALL) {
            List<ConditionSpec> conds = step.conditions();
            for (int i = 0; i < conds.size(); i++) {
                ConditionSpec c = conds.get(i);
                int cur = done ? c.amount() : (i < st.conditionProgress.length ? st.conditionProgress[i] : 0);
                lore.add("&7• &f" + c.describe() + ": &e" + cur + "&7/&f" + c.amount());
            }
        } else if (step.groupMode() == GroupMode.ANY) {
            int cur = done ? step.anyAmount() : (st.conditionProgress.length > 0 ? st.conditionProgress[0] : 0);
            lore.add("&7• &f" + step.name() + " (любое из): &e" + cur + "&7/&f" + step.anyAmount());
            for (ConditionSpec c : step.conditions()) {
                lore.add("  &8- " + c.describe());
            }
        }
        if (step.time() != null) {
            int curMin = done ? step.time().minutes() : st.timeMinutes;
            lore.add("&7• &f" + step.time().describe() + ": &e" + curMin + "&7/&f" + step.time().minutes() + " мин.");
        }
    }

    private static void appendStepDetailsIndented(List<String> lore, StepSpec step, QuestProgressState st) {
        if (step.groupMode() == GroupMode.ALL) {
            List<ConditionSpec> conds = step.conditions();
            for (int i = 0; i < conds.size(); i++) {
                ConditionSpec c = conds.get(i);
                int cur = i < st.conditionProgress.length ? st.conditionProgress[i] : 0;
                lore.add("   &7- " + c.describe() + ": &e" + cur + "&7/&f" + c.amount());
            }
        } else if (step.groupMode() == GroupMode.ANY) {
            int cur = st.conditionProgress.length > 0 ? st.conditionProgress[0] : 0;
            lore.add("   &7- Суммарно (любое): &e" + cur + "&7/&f" + step.anyAmount());
        }
        if (step.time() != null) {
            lore.add("   &7- Смена: &e" + st.timeMinutes + "&7/&f" + step.time().minutes() + " мин.");
        }
    }

    private static String formatCategoryLabel(PeriodTemplate tpl, String resetText) {
        if (tpl == null) return "&7Обычное";
        return switch (tpl.kind()) {
            case DAILY -> "&eЕжедневное &8(до " + resetText + ")";
            case WEEKLY -> "&6Еженедельное &8(до " + resetText + ")";
            case INTERVAL -> "&bКаждые " + tpl.days() + " дн. &8(до " + resetText + ")";
            case ONCE -> "&dРазовое задание";
        };
    }

    private static Material resolveIcon(QuestSpec spec, LifecycleState lc) {
        if (lc == LifecycleState.LOCKED) {
            return Material.BARRIER;
        }
        if (spec.icon() != null) {
            Material m = Material.matchMaterial(spec.icon());
            if (m != null && m.isItem()) return m;
        }
        return switch (lc) {
            case COMPLETED -> Material.ENCHANTED_BOOK;
            case IN_PROGRESS -> Material.WRITTEN_BOOK;
            default -> Material.BOOK;
        };
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

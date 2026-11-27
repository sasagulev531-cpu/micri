package Frontiks.Craft.quests;

import Frontiks.Craft.gui.MenuManager;
import Frontiks.Craft.gui.WorkMenu;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.quests.v2.QuestEngineV2;
import Frontiks.Craft.utils.color;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Слушатель:
 * - кликов в 54-слотовом GUI «Задачи шахтёра» (взятие ЛКМ, пагинация, повтор выплат);
 * - переплавки в печи/плавильне (SMELT);
 * - крафта с антидюп-дельтой инвентаря/курсора (CRAFT).
 */
public final class QuestsMenuListener implements Listener {

    private final JavaPlugin plugin;
    private final QuestManager quests;
    private final MiningSettings settings;

    public QuestsMenuListener(JavaPlugin plugin, QuestManager quests, MiningSettings settings) {
        this.plugin = plugin;
        this.quests = quests;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView() == null || e.getView().getTitle() == null) return;

        // 1) Клики в хабе разделов квестов (/quests)
        if (MenuManager.isInMenu(p.getUniqueId(), QuestsHubMenu.MENU_ID)) {
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot < 0 || slot >= QuestsHubMenu.INV_SIZE) return;
            if (slot == 22) {
                p.closeInventory();
                return;
            }
            String jobId = QuestsHubMenu.jobIdAt(p.getUniqueId(), slot);
            if (jobId != null) {
                MenuManager.removeMenu(p.getUniqueId());
                QuestsHubMenu.clearState(p.getUniqueId());
                new QuestsMenu(quests, settings).openFromHub(p);
            }
            return;
        }

        // 2) Клики в списке квестов Шахтёра
        if (!MenuManager.isInMenu(p.getUniqueId(), QuestsMenu.MENU_ID)) return;

        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= QuestsMenu.INV_SIZE) return;

        int curPage = QuestsMenu.pageOf(p.getUniqueId());
        if (slot == 45 && curPage > 0) {
            new QuestsMenu(quests, settings).open(p, curPage - 1);
            return;
        }
        if (slot == 53) {
            new QuestsMenu(quests, settings).open(p, curPage + 1);
            return;
        }
        if (slot == 48) {
            QuestsMenu.ReturnTarget rt = QuestsMenu.returnTargetOf(p.getUniqueId());
            if (rt == QuestsMenu.ReturnTarget.WORK_MENU) {
                MenuManager.removeMenu(p.getUniqueId());
                QuestsMenu.clearState(p.getUniqueId());
                WorkMenu.open(p);
                return;
            }
            if (rt == QuestsMenu.ReturnTarget.HUB_MENU) {
                MenuManager.removeMenu(p.getUniqueId());
                QuestsMenu.clearState(p.getUniqueId());
                new QuestsHubMenu(quests, settings).open(p);
                return;
            }
        }
        if (slot == 49) {
            var result = quests.retryPendingPayouts(p);
            sendSummary(p, result);
            new QuestsMenu(quests, settings).open(p, curPage);
            return;
        }
        if (slot == 50) {
            p.closeInventory();
            return;
        }

        String questId = QuestsMenu.questIdAt(p.getUniqueId(), slot);
        if (questId != null) {
            if (quests.acceptQuest(p, questId)) {
                new QuestsMenu(quests, settings).open(p, curPage);
            } else {
                var result = quests.retryPendingPayouts(p);
                if (!result.paidNow().isEmpty() || !result.payFailed().isEmpty()) {
                    sendSummary(p, result);
                    new QuestsMenu(quests, settings).open(p, curPage);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p
                && (MenuManager.isInMenu(p.getUniqueId(), QuestsMenu.MENU_ID)
                || MenuManager.isInMenu(p.getUniqueId(), QuestsHubMenu.MENU_ID))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (MenuManager.isInMenu(p.getUniqueId(), QuestsMenu.MENU_ID)) {
            MenuManager.removeMenu(p.getUniqueId());
            QuestsMenu.clearState(p.getUniqueId());
        } else if (MenuManager.isInMenu(p.getUniqueId(), QuestsHubMenu.MENU_ID)) {
            MenuManager.removeMenu(p.getUniqueId());
            QuestsHubMenu.clearState(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceExtract(FurnaceExtractEvent e) {
        Player p = e.getPlayer();
        if (p != null && e.getItemType() != null && e.getItemAmount() > 0) {
            quests.applyItemSmelted(p, e.getItemType().name(), e.getItemAmount(),
                    e.getBlock() != null ? e.getBlock().getLocation() : p.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack recipeResult = e.getRecipe() == null ? null : e.getRecipe().getResult();
        if (recipeResult == null || recipeResult.getType() == Material.AIR) return;

        Material targetMat = recipeResult.getType();
        ClickType click = e.getClick();
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            quests.applyItemCrafted(p, targetMat.name(), Math.max(1, recipeResult.getAmount()));
            return;
        }

        int before = countMaterialOnPlayer(p, targetMat);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            int after = countMaterialOnPlayer(p, targetMat);
            int crafted = after - before;
            if (crafted > 0) {
                quests.applyItemCrafted(p, targetMat.name(), crafted);
            }
        });
    }

    private static int countMaterialOnPlayer(Player p, Material mat) {
        int total = 0;
        for (ItemStack item : p.getInventory().getStorageContents()) {
            if (item != null && item.getType() == mat) {
                total += item.getAmount();
            }
        }
        ItemStack offhand = p.getInventory().getItemInOffHand();
        if (offhand.getType() == mat) {
            total += offhand.getAmount();
        }
        ItemStack cursor = p.getItemOnCursor();
        if (cursor.getType() == mat) {
            total += cursor.getAmount();
        }
        return total;
    }

    static void sendSummary(Player p, QuestEngineV2.EngineResult result) {
        if (result.paidNow().isEmpty() && result.payFailed().isEmpty()) {
            p.sendMessage(color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&7Нет невыплаченных наград."));
            return;
        }
        for (String id : result.paidNow()) {
            p.sendMessage(color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&aНаграда по заданию выплачена (" + id + ")."));
        }
        for (String id : result.payFailed()) {
            p.sendMessage(color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&cНе удалось выплатить награду (" + id + "). Экономика недоступна."));
        }
    }
}

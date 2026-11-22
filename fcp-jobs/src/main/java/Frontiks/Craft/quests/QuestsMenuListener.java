package Frontiks.Craft.quests;

import Frontiks.Craft.gui.MenuManager;
import Frontiks.Craft.utils.color;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.List;

/** Обработчик кликов GUI заданий. Каждая клетка либо отменена, либо выполняет действие. */
public final class QuestsMenuListener implements Listener {

    private final QuestManager quests;
    private final Frontiks.Craft.mining.MiningSettings settings;

    public QuestsMenuListener(QuestManager quests, Frontiks.Craft.mining.MiningSettings settings) {
        this.quests = quests;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView() == null || e.getView().getTitle() == null) return;
        if (!MenuManager.isInMenu(p.getUniqueId(), QuestsMenu.MENU_ID)) return;

        e.setCancelled(true);

        int slot = e.getSlot();
        if (slot == 31) { // проверить награды
            var result = quests.retryPendingPayouts(p);
            sendSummary(p, result);
            new QuestsMenu(quests, settings).open(p); // обновить отображение
            return;
        }
        if (slot == 35) { // закрыть
            p.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p
                && MenuManager.isInMenu(p.getUniqueId(), QuestsMenu.MENU_ID)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p
                && MenuManager.isInMenu(p.getUniqueId(), QuestsMenu.MENU_ID)) {
            MenuManager.removeMenu(p.getUniqueId());
        }
    }

    static void sendSummary(Player p, QuestBoard.ApplyResult result) {
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
                    Frontiks.Craft.utils.Msg.PREFIX + "&cНе удалось выплатить награду (" + id + "). Обратитесь к администрации."));
        }
    }
}

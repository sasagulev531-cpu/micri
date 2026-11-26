package Frontiks.Craft.listeners;

import Frontiks.Craft.gui.MenuManager;
import Frontiks.Craft.gui.WorkMenu;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.quests.QuestManager;
import Frontiks.Craft.quests.QuestsMenu;
import Frontiks.Craft.utils.color;
import Frontiks.Craft.work.WorkManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class WorkMenuListener implements Listener {

    private final QuestManager questManager;
    private final MiningSettings settings;

    public WorkMenuListener(QuestManager questManager, MiningSettings settings) {
        this.questManager = questManager;
        this.settings = settings;
    }

    public WorkMenuListener() {
        this(null, null);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getView() == null || e.getView().getTitle() == null) return;

        // Проверяем по ID меню
        if (!MenuManager.isInMenu(p.getUniqueId(), WorkMenu.MENU_ID)) {
            return;
        }

        e.setCancelled(true);

        // Игнорируем клики по собственному нижнему инвентарю игрока
        if (e.getRawSlot() < 0 || e.getRawSlot() >= 27) {
            return;
        }

        int slot = e.getRawSlot();

        // Слот 12 - Задачи шахтёра (открываем GUI напрямую, чтобы не конфликтовать с FCP-Quests /quests)
        if (slot == 12) {
            MenuManager.removeMenu(p.getUniqueId());
            if (!p.hasPermission("fcp.jobs.quests")) {
                p.closeInventory();
                p.sendMessage(color.color("" + Frontiks.Craft.utils.Msg.PREFIX + "&cНет прав."));
                return;
            }
            if (questManager != null && settings != null) {
                new QuestsMenu(questManager, settings).open(p);
            } else {
                p.closeInventory();
                Bukkit.dispatchCommand(p, "fcp-jobs:quests");
            }
            return;
        }

        // Слот 14 - Начать/Закончить работу
        if (slot == 14) {
            if (WorkManager.isWorking(p)) {
                WorkManager.stop(p);
            } else {
                if (!Frontiks.Craft.work.HrabotaConfig.contains(p.getLocation())) {
                    p.closeInventory();
                    p.sendMessage(color.color("" + Frontiks.Craft.utils.Msg.PREFIX + "&cВы должны находиться в зоне шахты, чтобы начать работу!"));
                    return;
                }
                WorkManager.start(p);
            }

            p.closeInventory();
            WorkMenu.open(p);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (MenuManager.isInMenu(p.getUniqueId(), WorkMenu.MENU_ID)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;

        if (MenuManager.isInMenu(p.getUniqueId(), WorkMenu.MENU_ID)) {
            MenuManager.removeMenu(p.getUniqueId());
        }
    }
}

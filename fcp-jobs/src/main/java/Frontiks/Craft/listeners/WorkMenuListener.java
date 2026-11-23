package Frontiks.Craft.listeners;

import Frontiks.Craft.gui.MenuManager;
import Frontiks.Craft.gui.WorkMenu;
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

        // Слот 12 - Задачи шахтёра (на месте старого магазина кирок)
        if (slot == 12) {
            p.closeInventory();
            MenuManager.removeMenu(p.getUniqueId());
            Bukkit.dispatchCommand(p, "quests");
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

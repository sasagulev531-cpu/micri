package Frontiks.Craft.listeners;

import Frontiks.Craft.work.WorkManager;
import Frontiks.Craft.gui.WorkMenu;
import Frontiks.Craft.gui.MenuManager;
import Frontiks.Craft.utils.color;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

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

        int slot = e.getSlot();

        // Слот 12 - Магазин кирок (открываем DM меню)
        if (slot == 12) {
            // Закрываем наше меню
            p.closeInventory();
            // Удаляем игрока из нашего меню
            MenuManager.removeMenu(p.getUniqueId());
            // Открываем DM меню
            Bukkit.dispatchCommand(p, "dm open kirka");
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
            return;
        }

        // Слот 16 - Задания шахтёра
        if (slot == 16) {
            p.closeInventory();
            Bukkit.dispatchCommand(p, "quests");
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

        // Удаляем игрока из меню при закрытии только если он был в меню работы
        if (MenuManager.isInMenu(p.getUniqueId(), WorkMenu.MENU_ID)) {
            MenuManager.removeMenu(p.getUniqueId());
        }
    }
}
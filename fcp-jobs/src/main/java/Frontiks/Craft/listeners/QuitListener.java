package Frontiks.Craft.listeners;

import Frontiks.Craft.work.WorkManager;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.entity.Player;

public class QuitListener implements Listener {

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();

        if (WorkManager.isWorking(p)) {
            WorkManager.stop(p);
        }
    }
}
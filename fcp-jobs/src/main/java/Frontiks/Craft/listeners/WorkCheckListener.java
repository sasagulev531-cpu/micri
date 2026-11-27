package Frontiks.Craft.listeners;

import Frontiks.Craft.actions.ActionKindRegistry;
import Frontiks.Craft.actions.ActionReporter;
import Frontiks.Craft.utils.color;
import Frontiks.Craft.work.HrabotaConfig;
import Frontiks.Craft.work.WorkManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import static Frontiks.Craft.utils.Msg.PREFIX;

/**
 * Контролирует выход игрока из зоны шахты любым способом + считает минуты активной смены (TIME_AS_JOB).
 */
public class WorkCheckListener implements Listener {

    private final JavaPlugin plugin;
    private final ActionReporter reporter;

    public WorkCheckListener() {
        this(null, null);
    }

    public WorkCheckListener(JavaPlugin plugin) {
        this(plugin, null);
    }

    public WorkCheckListener(JavaPlugin plugin, ActionReporter reporter) {
        this.plugin = plugin;
        this.reporter = reporter;
        if (plugin != null) {
            Bukkit.getScheduler().runTaskTimer(plugin, this::checkAllWorkingPlayers, 20L, 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!WorkManager.isWorking(p)) return;
        Location to = e.getTo();
        if (to != null && !inRegion(to)) {
            fireFromWork(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!WorkManager.isWorking(p)) return;
        Location to = e.getTo();
        if (to == null || !inRegion(to)) {
            fireFromWork(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        Player p = e.getPlayer();
        if (!WorkManager.isWorking(p)) return;
        Location to = e.getTo();
        if (to == null || !inRegion(to)) {
            fireFromWork(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!WorkManager.isWorking(p)) return;
        if (!inRegion(p.getLocation())) {
            fireFromWork(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (WorkManager.isWorking(p)) {
            fireFromWork(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!WorkManager.isWorking(p)) return;
        Location to = e.getRespawnLocation();
        if (to == null || !inRegion(to)) {
            fireFromWork(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (e.getVehicle().getPassengers().isEmpty()) return;
        Location to = e.getTo();
        if (to == null || inRegion(to)) return;
        for (var passenger : e.getVehicle().getPassengers()) {
            if (passenger instanceof Player p && WorkManager.isWorking(p)) {
                fireFromWork(p);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent e) {
        Player p = e.getPlayer();
        if (WorkManager.isWorking(p)) {
            WorkManager.stop(p);
        }
    }

    private void checkAllWorkingPlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!WorkManager.isWorking(p)) continue;
            if (!inRegion(p.getLocation())) {
                fireFromWork(p);
                continue;
            }
            // Накопление времени на активной смене в зоне шахты (§5.4 time)
            int minutes = WorkManager.tickSecondAndDrainMinutes(p);
            if (minutes > 0 && reporter != null) {
                reporter.report(p, ActionKindRegistry.TIME_AS_JOB, null, minutes,
                        p.getLocation(), true, WorkManager.currentJobId(p));
            }
        }
    }

    private void fireFromWork(Player p) {
        if (!WorkManager.isWorking(p)) return;
        WorkManager.stop(p);
        p.sendMessage(color.color("" + PREFIX + "&fВы вышли с зоны работы, вы были уволены."));
    }

    private boolean inRegion(Location loc) {
        return HrabotaConfig.contains(loc);
    }
}

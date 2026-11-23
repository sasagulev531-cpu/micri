package Frontiks.Craft.actions;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Единая точка входа отчёта всех игровых действий в слой заданий и статистики (R2).
 */
public final class ActionReporter {

    @FunctionalInterface
    public interface Listener {
        void onAction(UUID playerId, Player onlinePlayer, JobAction action);
    }

    private final Listener sink;

    public ActionReporter(Listener sink) {
        this.sink = sink;
    }

    public void report(Player player, String eventId, String material, int amount,
                       Location actionLocation, boolean workingInMine, String jobId) {
        if (player == null || amount <= 0) return;
        Integer y = actionLocation != null ? actionLocation.getBlockY()
                : (player.getLocation() != null ? player.getLocation().getBlockY() : null);
        report(player.getUniqueId(), player, new JobAction(eventId, material, amount, y, workingInMine, jobId));
    }

    public void report(UUID playerId, Player onlinePlayer, JobAction action) {
        if (playerId == null || action == null || action.amount() <= 0) return;
        if (sink != null) {
            sink.onAction(playerId, onlinePlayer, action);
        }
    }
}

package Frontiks.Craft.utils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Дебаунсер сохранения YAML-файлов (§3 п. 5 ТЗ):
 * пишет на диск не чаще 1 раза в minIntervalTicks (по умолчанию 100 тиков = 5 секунд)
 * и гарантирует синхронный flushNow() при выключении плагина.
 */
public final class DebouncedSaver {

    private final JavaPlugin plugin;
    private final long minIntervalTicks;
    private final Runnable saveAction;

    private boolean dirty = false;
    private BukkitTask scheduledTask = null;

    public DebouncedSaver(JavaPlugin plugin, long minIntervalTicks, Runnable saveAction) {
        this.plugin = plugin;
        this.minIntervalTicks = Math.max(20L, minIntervalTicks);
        this.saveAction = saveAction;
    }

    public synchronized void markDirty() {
        dirty = true;
        if (plugin == null || !plugin.isEnabled()) {
            flushNow();
            return;
        }
        if (scheduledTask == null) {
            scheduledTask = Bukkit.getScheduler().runTaskLater(plugin, this::runScheduled, minIntervalTicks);
        }
    }

    private synchronized void runScheduled() {
        scheduledTask = null;
        if (dirty) {
            dirty = false;
            saveAction.run();
        }
    }

    public synchronized void flushNow() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
        if (dirty) {
            dirty = false;
            saveAction.run();
        }
    }
}

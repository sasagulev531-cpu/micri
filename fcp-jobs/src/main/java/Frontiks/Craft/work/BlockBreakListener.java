package Frontiks.Craft.work;

import org.bukkit.plugin.java.JavaPlugin;
import Frontiks.Craft.utils.color;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class BlockBreakListener implements Listener {

    private final JavaPlugin plugin;
    private final Frontiks.Craft.mining.MiningNodeManager nodeManager;

    public BlockBreakListener(JavaPlugin plugin) {
        this(plugin, null);
    }

    public BlockBreakListener(JavaPlugin plugin, Frontiks.Craft.mining.MiningNodeManager nodeManager) {
        this.plugin = plugin;
        this.nodeManager = nodeManager;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        // Не считаем отменённые другими плагинами/механиками события.
        if (e.isCancelled()) return;

        // Блоки настраиваемых узлов обслуживает MiningNodeListener.
        if (nodeManager != null && nodeManager.isMiningNode(e.getBlock().getLocation())) return;

        Player p = e.getPlayer();

        if (!WorkManager.isWorking(p)) return;

        if (!inRegion(p.getLocation())) {
            WorkManager.stop(p);
            e.setCancelled(true);
            return;
        }

        Block b = e.getBlock();

        if (!isOre(b.getType())) {
            e.setCancelled(true);
            return;
        }

        e.setCancelled(true);

        Material ore = b.getType();

        int money = getOrePrice(b.getType());
        WorkManager.add(p, money);

        b.setType(Material.COBBLESTONE);

        new BukkitRunnable() {
            @Override
            public void run() {
                b.setType(ore);
            }
        }.runTaskLater(plugin, 20 * 45);
    }

    private int getOrePrice(Material m) {
        switch (m) {
            case COAL_ORE:
            case DEEPSLATE_COAL_ORE:
                return 13;

            case LAPIS_ORE:
            case DEEPSLATE_LAPIS_ORE:
                return 19;

            case REDSTONE_ORE:
            case DEEPSLATE_REDSTONE_ORE:
                return 23;

            case COPPER_ORE:
            case DEEPSLATE_COPPER_ORE:
                return 24;

            case IRON_ORE:
            case DEEPSLATE_IRON_ORE:
                return 32;

            case GOLD_ORE:
            case DEEPSLATE_GOLD_ORE:
                return 46;

            case DIAMOND_ORE:
            case DEEPSLATE_DIAMOND_ORE:
                return 152;

            case EMERALD_ORE:
            case DEEPSLATE_EMERALD_ORE:
                return 213;

            case NETHER_QUARTZ_ORE:
                return 31;

            case ANCIENT_DEBRIS:
                return 543;

            default:
                return 0;
        }
    }

    private boolean isOre(Material m) {
        String name = m.name();
        return name.contains("_ORE") || name.equals("NETHER_QUARTZ_ORE") || name.equals("ANCIENT_DEBRIS");
    }

    private boolean inRegion(Location loc) {
        return HrabotaConfig.contains(loc);
    }
}
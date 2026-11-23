package Frontiks.Craft.mining;

import Frontiks.Craft.api.FcpPermissions;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Слушатель механики узлов. Зарегистрирован с приоритетом HIGH и РАНЬШЕ
 * старого BlockBreakListener — отмена события блокирует ванильный дроп/XP
 * и старую механику по блокам узлов.
 */
public final class MiningListener implements Listener {

    private final JavaPlugin plugin;
    private final MiningNodeManager manager;

    public MiningListener(JavaPlugin plugin, MiningNodeManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        manager.handleBlockBreak(e);
        manager.markNeighborsChanged(e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (MiningWand.isWand(plugin, p.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            return;
        }
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE
                && Frontiks.Craft.work.HrabotaConfig.contains(e.getBlock().getLocation())) {
            e.setCancelled(true);
            return;
        }
        manager.markNeighborsChanged(e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!MiningWand.isWand(plugin, e.getItem())) return;

        // Палочка «съедает» взаимодействие всегда.
        e.setCancelled(true);

        if (!FcpPermissions.adminOr(p, "fcp.admin.jobs.node")) {
            p.sendMessage(Frontiks.Craft.utils.color.color(
                    Frontiks.Craft.utils.Msg.PREFIX + "&cНет прав (fcp.admin.jobs.node)."));
            return;
        }
        Block block = e.getClickedBlock();
        if (block == null) return;
        Action action = e.getAction();
        if (action == Action.RIGHT_CLICK_BLOCK && p.isSneaking() && manager.isMiningNode(block.getLocation())) {
            MiningNode removed = manager.deleteNodeAt(block.getLocation());
            if (removed != null) {
                p.sendMessage(Frontiks.Craft.utils.color.color(
                        Frontiks.Craft.utils.Msg.PREFIX + "&aУзел " + manager.shortId(removed.id) + " удалён."));
            }
            return;
        }
        if (action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK) {
            manager.handleWandClick(p, block);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onBlockDamage(BlockDamageEvent e) {
        Player p = e.getPlayer();
        if (MiningWand.isWand(plugin, p.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            return;
        }
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE
                && p.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && !Frontiks.Craft.work.WorkManager.isWorking(p)
                && (manager.isMiningNode(e.getBlock().getLocation())
                || Frontiks.Craft.work.HrabotaConfig.contains(e.getBlock().getLocation()))) {
            e.setCancelled(true);
            Frontiks.Craft.work.WorkManager.notifyNeedJob(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> manager.isMiningNode(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> manager.isMiningNode(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (manager.isMiningNode(b.getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (manager.isMiningNode(b.getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent e) {
        if (manager.isMiningNode(e.getBlock().getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent e) {
        if (manager.isMiningNode(e.getBlock().getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        manager.handleChunkLoad(e);
    }
}

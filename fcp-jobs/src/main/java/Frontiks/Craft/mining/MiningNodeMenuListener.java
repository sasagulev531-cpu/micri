package Frontiks.Craft.mining;

import Frontiks.Craft.api.FcpPermissions;
import Frontiks.Craft.gui.MenuManager;
import Frontiks.Craft.utils.color;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Обработчик кликов админского GUI узла.
 * Права проверяются при КАЖДОМ клике — предмет/открытое меню не обходит permission.
 */
public final class MiningNodeMenuListener implements Listener {

    private final JavaPlugin plugin;
    private final MiningNodeManager manager;

    public MiningNodeMenuListener(JavaPlugin plugin, MiningNodeManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView() == null || e.getView().getTitle() == null) return;
        if (!MenuManager.isInMenu(p.getUniqueId(), MiningNodeMenu.MENU_ID)) return;

        e.setCancelled(true);

        if (!FcpPermissions.adminOr(p, "fcp.admin.jobs.node")) {
            p.closeInventory();
            p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cНет прав (fcp.admin.jobs.node)."));
            return;
        }

        MiningNodeMenu.State state = MiningNodeMenu.stateOf(p.getUniqueId());
        if (state == null) return;
        MiningNode node = manager.getNode(state.nodeId);
        if (node == null) {
            p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&cУзел больше не существует."));
            p.closeInventory();
            return;
        }

        int slot = e.getSlot();
        ClickType click = e.getClick();

        try {
            // Стадии: слоты 9..16
            if (slot >= 9 && slot <= 16) {
                int stageIndex = slot - 9;
                if (stageIndex < node.chain.size()) {
                    if (click.isShiftClick() && click.isLeftClick()) {
                        manager.removeStage(node, stageIndex);
                    } else if (click.isLeftClick()) {
                        manager.setStageMaterial(node, stageIndex, cycle(node.chain.get(stageIndex), +1));
                    } else if (click.isRightClick()) {
                        manager.setStageMaterial(node, stageIndex, cycle(node.chain.get(stageIndex), -1));
                    }
                    refresh(p, node);
                } else if (stageIndex == node.chain.size()) {
                    // кнопка «Добавить стадию»
                    manager.addStage(node, "TUFF");
                    refresh(p, node);
                }
                return;
            }

            switch (slot) {
                case 18 -> { // истощённый вид
                    if (click.isRightClick()) {
                        manager.setDepletedMaterial(node, cycle(node.depletedMaterial, -1));
                    } else {
                        manager.setDepletedMaterial(node, cycle(node.depletedMaterial, +1));
                    }
                    refresh(p, node);
                }
                case 19 -> { // кулдаун
                    int delta = click.isRightClick() ? -5 : +5;
                    int next = (int) (node.cooldownMillis / 1000L) + delta;
                    manager.setCooldownSeconds(node, Math.max(0, Math.min(86400, next)));
                    refresh(p, node);
                }
                case 20 -> { // подсказка по луту в чат
                    p.sendMessage(color.color("&7Лут редактируется командами:"));
                    p.sendMessage(color.color("&f/miningnode loot list"));
                    p.sendMessage(color.color("&f/miningnode loot add <материал> <min> <max> <шанс 0..1>"));
                    p.sendMessage(color.color("&f/miningnode loot remove <№>"));
                    p.sendMessage(color.color("&f/miningnode loot clear"));
                }
                case 21 -> { // принудительное восстановление
                    if (node.depleted && click.isLeftClick()) {
                        manager.forceRestore(node);
                        p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aУзел восстановлен."));
                        refresh(p, node);
                    }
                }
                case 23 -> { // удаление с двухшаговым подтверждением
                    boolean confirm = state.confirmDelete && System.currentTimeMillis() < state.confirmUntil;
                    if (click.isShiftClick() && click.isLeftClick()) {
                        if (confirm) {
                            manager.removeNode(node);
                            MiningNodeMenu.clearState(p.getUniqueId());
                            MenuManager.removeMenu(p.getUniqueId());
                            p.closeInventory();
                            p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX
                                    + "&aУзел " + manager.shortId(node.id) + " удалён."));
                        } else {
                            state.confirmDelete = true;
                            state.confirmUntil = System.currentTimeMillis() + 10_000;
                            refresh(p, node);
                        }
                    }
                }
                case 26 -> { // закрыть
                    p.closeInventory();
                }
                default -> { /* фон — клик проигнорирован */ }
            }
        } catch (MiningNodeManager.MiningNodeException ex) {
            p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&c" + ex.getMessage()));
            refresh(p, node);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p
                && MenuManager.isInMenu(p.getUniqueId(), MiningNodeMenu.MENU_ID)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p
                && MenuManager.isInMenu(p.getUniqueId(), MiningNodeMenu.MENU_ID)) {
            MenuManager.removeMenu(p.getUniqueId());
            MiningNodeMenu.clearState(p.getUniqueId());
        }
    }

    private void refresh(Player p, MiningNode node) {
        new MiningNodeMenu(plugin, manager, p, node).open();
    }

    private String cycle(String current, int direction) {
        List<String> palette = manager.settings().editableMaterials();
        if (palette.isEmpty()) return current;
        int idx = palette.indexOf(current == null ? "" : current.toUpperCase());
        if (idx < 0) idx = 0;
        int next = Math.floorMod(idx + direction, palette.size());
        return palette.get(next);
    }
}

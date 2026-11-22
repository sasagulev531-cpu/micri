package Frontiks.Craft.mining;

import Frontiks.Craft.api.FcpPermissions;
import Frontiks.Craft.gui.MenuManager;
import Frontiks.Craft.utils.color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
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

        ClickType click = e.getClick();

        // Клик по предмету в НИЖНЕМ инвентаре игрока -> быстрое добавление этого предмета в лут узла!
        if (e.getClickedInventory() != null && e.getClickedInventory().equals(p.getInventory())) {
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR || MiningWand.isWand(plugin, clicked)) {
                return;
            }
            int amt = Math.max(1, clicked.getAmount());
            double chance = click.isRightClick() ? 0.25 : 1.0;
            List<LootEntry> loot = new ArrayList<>(node.loot);
            loot.add(new LootEntry(clicked.getType().name(), 1, amt, chance));
            try {
                manager.setLoot(node, loot);
                p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&aВ лут добавлен: &f"
                        + clicked.getType().name() + " &7x1–" + amt + " (" + Math.round(chance * 100) + "%)"));
            } catch (MiningNodeManager.MiningNodeException ex) {
                p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&c" + ex.getMessage()));
            }
            refresh(p, node);
            return;
        }

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 27) return;

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
                case 20 -> { // сундук лута
                    if (click == ClickType.MIDDLE || click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
                        manager.clearLoot(node);
                        p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&eЛут узла полностью очищен."));
                        refresh(p, node);
                    } else if (click.isShiftClick() && click.isRightClick() && !node.loot.isEmpty()) {
                        List<LootEntry> loot = new ArrayList<>(node.loot);
                        LootEntry removed = loot.remove(loot.size() - 1);
                        if (loot.isEmpty()) {
                            manager.clearLoot(node);
                        } else {
                            manager.setLoot(node, loot);
                        }
                        p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX + "&eУдалена строка лута: &f" + removed.material));
                        refresh(p, node);
                    } else {
                        p.sendMessage(color.color("&7Подсказка: кликните предметом в своём инвентаре, чтобы добавить его в лут, или используйте &f/miningnode lootall ... &7для всех узлов сразу!"));
                    }
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
                case 25 -> { // ПРИМЕНИТЬ КО ВСЕМ УЗЛАМ!
                    if (click.isShiftClick() && click.isLeftClick()) {
                        int updated = manager.applyTemplateToAll(node);
                        p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX
                                + "&aНастройки этого узла (лут, стадии, кулдаун) применены ко ВСЕМ узлам (&e"
                                + updated + " шт.&a) и сохранены по умолчанию для новых!"));
                        refresh(p, node);
                    } else {
                        p.sendMessage(color.color(Frontiks.Craft.utils.Msg.PREFIX
                                + "&eНажмите &6Shift + ЛКМ &eпо звезде, чтобы скопировать настройки этого узла на все остальные!"));
                    }
                }
                case 26 -> p.closeInventory();
                default -> { }
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

package Frontiks.Craft.mining;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Админский инструмент «Маркер шахты»: палка с PDC-меткой.
 * Идентификация ТОЛЬКО по PDC, название/материал не используются.
 */
public final class MiningWand {

    private static final String KEY = "mining_node_wand";

    private MiningWand() {}

    public static NamespacedKey key(JavaPlugin plugin) {
        return new NamespacedKey(plugin, KEY);
    }

    public static ItemStack create(JavaPlugin plugin) {
        ItemStack item = new ItemStack(Material.STICK, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Frontiks.Craft.utils.color.color("&6&lМаркер шахты"));
        meta.setLore(List.of(
                Frontiks.Craft.utils.color.color("&7Кликните по блоку в шахте, чтобы"),
                Frontiks.Craft.utils.color.color("&7настроить добываемый узел"),
                Frontiks.Craft.utils.color.color("&8Только для администраторов")
        ));
        // Небольшой «сияющий» акцент штатными средствами (без новых эффектов)
        try {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        } catch (Throwable ignored) {
            // визуальная косметика не критична
        }
        meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isWand(JavaPlugin plugin, ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        Byte b = item.getItemMeta().getPersistentDataContainer().get(key(plugin), PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }
}

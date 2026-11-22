package Frontiks.Craft.commands;

import Frontiks.Craft.api.EconomyService;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import static Frontiks.Craft.utils.color.color;
import static Frontiks.Craft.utils.Msg.PREFIX;

public class FishingGiveCommand implements CommandExecutor {

    private final EconomyService economy;

    public FishingGiveCommand(EconomyService economy) {
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;

        int price = 0;
        boolean donate = false;

        ItemStack rod = null;

        switch (cmd.getName().toLowerCase()) {

            case "fishinggive1":
                price = 1500;
                rod = new ItemStack(Material.FISHING_ROD);
                addEnchant(rod, Enchantment.LURE, 1);
                addEnchant(rod, Enchantment.LUCK_OF_THE_SEA, 1);
                addEnchant(rod, Enchantment.UNBREAKING, 1);
                setName(rod, color("&#2159D6&lУ&#1D63D8&lд&#1A6DD9&lо&#1677DB&lч&#1381DC&lк&#0F8BDE&lа"));
                break;

            case "fishinggive2":
                price = 3000;
                rod = new ItemStack(Material.FISHING_ROD);
                addEnchant(rod, Enchantment.LURE, 2);
                addEnchant(rod, Enchantment.LUCK_OF_THE_SEA, 2);
                addEnchant(rod, Enchantment.UNBREAKING, 2);
                setName(rod, color("&#2159D6&lУ&#1D63D8&lд&#1A6DD9&lо&#1677DB&lч&#1381DC&lк&#0F8BDE&lа"));
                break;

            case "fishinggive3":
                price = 7000;
                rod = new ItemStack(Material.FISHING_ROD);
                addEnchant(rod, Enchantment.LURE, 2);
                addEnchant(rod, Enchantment.LUCK_OF_THE_SEA, 2);
                addEnchant(rod, Enchantment.UNBREAKING, 3);
                setName(rod, color("&#2159D6&lУ&#1D63D8&lд&#1A6DD9&lо&#1677DB&lч&#1381DC&lк&#0F8BDE&lа"));
                break;

            case "fishinggive4":
                price = 30000;
                rod = new ItemStack(Material.FISHING_ROD);
                addEnchant(rod, Enchantment.LURE, 3);
                addEnchant(rod, Enchantment.LUCK_OF_THE_SEA, 3);
                addEnchant(rod, Enchantment.UNBREAKING, 3);
                setName(rod, color("&#2159D6&lУ&#1D63D8&lд&#1A6DD9&lо&#1677DB&lч&#1381DC&lк&#0F8BDE&lа"));
                break;

            case "fishinggive5":
                donate = true;
                price = 300;
                rod = new ItemStack(Material.FISHING_ROD);
                addEnchant(rod, Enchantment.LURE, 3);
                addEnchant(rod, Enchantment.LUCK_OF_THE_SEA, 3);
                addEnchant(rod, Enchantment.UNBREAKING, 3);
                addEnchant(rod, Enchantment.MENDING, 1);
                setName(rod, color("&#2159D6&lУ&#205DD7&lд&#1E61D7&lо&#1D65D8&lч&#1B68D8&lк&#1A6CD9&lа &#1774DA&lР&#1678DB&lы&#157CDC&lб&#137FDC&lа&#1283DD&lч&#1087DD&lк&#0F8BDE&lи"));
                break;
        }

        if (donate) {

            if (economy.getDCoins(p.getUniqueId()) < price) {
                p.sendMessage(color(""+PREFIX+"§cНедостаточно Тюльпанов."));
                return true;
            }

            economy.removeDCoins(p.getUniqueId(), price);

        } else {

            if (economy.getCoins(p.getUniqueId()) < price) {
                p.sendMessage(color(""+PREFIX+"§cНедостаточно монет."));
                return true;
            }

            economy.removeCoins(p.getUniqueId(), price);
        }

        for (ItemStack leftover : p.getInventory().addItem(rod).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), leftover);
        }
        p.sendMessage(color(""+PREFIX+"§fВы успешно купили удочку!"));

        return true;
    }

    private void addEnchant(ItemStack item, Enchantment enchantment, int level) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(enchantment, level, true);
        item.setItemMeta(meta);
    }

    private void setName(ItemStack item, String name) {
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        item.setItemMeta(meta);
    }
}
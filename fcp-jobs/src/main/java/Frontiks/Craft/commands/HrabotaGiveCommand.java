package Frontiks.Craft.commands;

import Frontiks.Craft.api.EconomyService;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import static Frontiks.Craft.utils.color.color;
import static Frontiks.Craft.utils.Msg.PREFIX;

public class HrabotaGiveCommand implements CommandExecutor {

    private final EconomyService economy;

    public HrabotaGiveCommand(EconomyService economy) {
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;

        int price = 0;
        ItemStack pick = null;

        switch (cmd.getName().toLowerCase()) {

            case "hrabotagive1":
                price = 10000;
                pick = new ItemStack(Material.IRON_PICKAXE);
                break;

            case "hrabotagive2":
                price = 25000;
                pick = new ItemStack(Material.IRON_PICKAXE);
                addEnchant(pick, Enchantment.EFFICIENCY, 2);
                addEnchant(pick, Enchantment.UNBREAKING, 1);
                break;

            case "hrabotagive3":
                price = 40000;
                pick = new ItemStack(Material.DIAMOND_PICKAXE);
                addEnchant(pick, Enchantment.EFFICIENCY, 3);
                addEnchant(pick, Enchantment.FORTUNE, 1);
                addEnchant(pick, Enchantment.UNBREAKING, 2);
                break;

            case "hrabotagive4":
                price = 75000;
                pick = new ItemStack(Material.DIAMOND_PICKAXE);
                addEnchant(pick, Enchantment.FORTUNE, 2);
                addEnchant(pick, Enchantment.EFFICIENCY, 4);
                addEnchant(pick, Enchantment.UNBREAKING, 3);
                break;

            case "hrabotagive5":
                price = 150000;
                pick = new ItemStack(Material.NETHERITE_PICKAXE);
                addEnchant(pick, Enchantment.FORTUNE, 3);
                addEnchant(pick, Enchantment.EFFICIENCY, 4);
                addEnchant(pick, Enchantment.UNBREAKING, 3);
                addEnchant(pick, Enchantment.MENDING, 1);
                break;
        }

        int balance = economy.getCoins(p.getUniqueId());

        if (balance < price) {
            p.sendMessage(color(""+PREFIX+"§cНедостаточно монет"));
            return true;
        }

        economy.removeCoins(p.getUniqueId(), price);

        for (ItemStack leftover : p.getInventory().addItem(pick).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), leftover);
        }
        p.sendMessage(color(""+PREFIX+"§aВы приобрели кирку"));

        return true;
    }

    private void addEnchant(ItemStack item, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(ench, level, true);
        item.setItemMeta(meta);
    }
}
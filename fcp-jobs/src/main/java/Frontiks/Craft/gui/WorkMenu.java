package Frontiks.Craft.gui;

import Frontiks.Craft.work.WorkManager;
import Frontiks.Craft.utils.color;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Arrays;
import java.util.UUID;

public class WorkMenu {

    private static final String TITLE = "               &#777070&lШ&#716A6A&lа&#6B6564&lх&#645F5F&lт&#5E5A59&lе&#585453&lр";
    private static final int SIZE = 27;
    public static final String MENU_ID = "work_menu";

    private static final String WORK_SKULL_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGY5OWRjMzJhZDRlZGFkNTI5ODVlZmQ3ZTNhZWI0MmI0MzY1NDA3MTM3MzY3NDJmNmQ5YTNiOTFjMzVlZjg3MSJ9fX0=";

    public static void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, SIZE, color.color(TITLE));

        // Фоновое стекло
        ItemStack glass = createGlass();
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, glass);
        }

        // Кнопка "Задания шахтёра" (слот 12 — на месте старого магазина кирок)
        inv.setItem(12, createQuestButton());

        // Кнопка "Начать/Закончить работу" (слот 14)
        inv.setItem(14, createWorkButton(p));

        // Информация (слот 4)
        inv.setItem(4, createInfo(p));

        p.openInventory(inv);
        // Запоминаем после openInventory, чтобы закрытие предыдущего GUI не стирало MENU_ID
        MenuManager.setMenu(p.getUniqueId(), MENU_ID);
    }

    private static ItemStack createGlass() {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        meta.setDisplayName(" ");
        glass.setItemMeta(meta);
        return glass;
    }

    private static ItemStack createSkull(String texture, String displayName, java.util.List<String> lore) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();

        if (meta != null) {
            try {
                com.destroystokyo.paper.profile.PlayerProfile profile =
                        Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", texture));
                meta.setPlayerProfile(profile);
            } catch (Throwable ignored) {
            }

            meta.setDisplayName(color.color(displayName));
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore.stream().map(color::color).toList());
            }
            skull.setItemMeta(meta);
        }

        return skull;
    }

    private static ItemStack createWorkButton(Player p) {
        boolean isWorking = WorkManager.isWorking(p);

        String displayName = isWorking ? "&c&lЗАКОНЧИТЬ РАБОТУ" : "&a&lНАЧАТЬ РАБОТУ";
        java.util.List<String> lore;

        if (isWorking) {
            lore = Arrays.asList(
                    "&7Нажмите, чтобы закончить работу",
                    "&7Вы заработали: &f" + WorkManager.getEarned(p) + " &7монет"
            );
        } else {
            lore = Arrays.asList(
                    "&7Нажмите, чтобы начать работу"
            );
        }

        return createSkull(WORK_SKULL_TEXTURE, displayName, lore);
    }

    private static ItemStack createQuestButton() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color.color("&6&lЗадачи шахтёра"));
            meta.setLore(Arrays.asList(
                    color.color("&7Ежедневные и недельные задачи"),
                    color.color("&7с наградой в монетах"),
                    color.color(""),
                    color.color("&eНажмите, чтобы открыть")
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createInfo(Player p) {
        boolean isWorking = WorkManager.isWorking(p);
        int earned = WorkManager.getEarned(p);

        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();

        meta.setDisplayName(color.color("&6&lИнформация"));
        meta.setLore(Arrays.asList(
                color.color("&7Статус: " + (isWorking ? "&aРаботает" : "&cНе работает")),
                color.color("&7Заработано: &f" + earned + " &7монет")
        ));

        paper.setItemMeta(meta);
        return paper;
    }
}

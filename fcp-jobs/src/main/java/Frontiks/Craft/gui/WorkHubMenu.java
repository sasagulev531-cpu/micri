package Frontiks.Craft.gui;

import Frontiks.Craft.jobs.JobKind;
import Frontiks.Craft.jobs.JobRegistry;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.utils.color;
import Frontiks.Craft.work.WorkManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * GUI выбора работы (/work).
 * Сейчас отображает карточку «Шахтёр» (MINER); при добавлении новых работ в JobRegistry
 * они автоматически появятся здесь рядом.
 */
public final class WorkHubMenu {

    public static final String MENU_ID = "work_hub_menu";
    public static final int INV_SIZE = 27;
    private static final String TITLE = "               &#777070&lР&#716A6A&lа&#6B6564&lб&#645F5F&lо&#5E5A59&lт&#585453&lы";

    private static final int[] SINGLE_LAYOUT = {13};
    private static final int[] MULTI_LAYOUT = {11, 12, 13, 14, 15, 10, 16};

    private static final Map<UUID, Map<Integer, String>> SLOT_JOBS = new ConcurrentHashMap<>();

    private WorkHubMenu() {}

    public static String jobIdAt(UUID playerId, int slot) {
        Map<Integer, String> map = SLOT_JOBS.get(playerId);
        return map == null ? null : map.get(slot);
    }

    public static void clearState(UUID playerId) {
        SLOT_JOBS.remove(playerId);
    }

    public static void open(Player player, MiningSettings settings) {
        JobRegistry registry = settings != null ? settings.jobRegistry() : JobRegistry.withDefaultMiner();
        open(player, registry);
    }

    public static void open(Player player, JobRegistry registry) {
        Inventory inv = Bukkit.createInventory(null, INV_SIZE, color.color(TITLE));
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < INV_SIZE; i++) inv.setItem(i, glass);

        List<JobKind> jobs = new ArrayList<>((registry != null ? registry : JobRegistry.withDefaultMiner()).all());
        int[] layout = jobs.size() == 1 ? SINGLE_LAYOUT : MULTI_LAYOUT;
        Map<Integer, String> slotMap = new HashMap<>();

        for (int i = 0; i < Math.min(jobs.size(), layout.length); i++) {
            JobKind job = jobs.get(i);
            int slot = layout[i];
            slotMap.put(slot, job.id);
            inv.setItem(slot, buildJobCard(player, job));
        }

        inv.setItem(22, named(Material.OAK_DOOR, "&7Закрыть", List.of("&7Закрыть меню")));

        player.openInventory(inv);
        SLOT_JOBS.put(player.getUniqueId(), slotMap);
        MenuManager.setMenu(player.getUniqueId(), MENU_ID);
    }

    private static ItemStack buildJobCard(Player player, JobKind job) {
        boolean workingThisJob = WorkManager.isWorking(player)
                && job.id.equalsIgnoreCase(WorkManager.currentJobId(player));
        int earned = workingThisJob ? WorkManager.getEarned(player) : 0;

        Material iconMat = Material.matchMaterial(job.iconMaterial.toUpperCase(Locale.ROOT));
        if (iconMat == null || !iconMat.isItem()) iconMat = Material.IRON_PICKAXE;

        List<String> lore = new ArrayList<>();
        if (!job.description.isEmpty()) {
            lore.addAll(job.description);
        } else {
            lore.add("&7Выберите профессию и начните смену.");
        }
        lore.add("");
        if (workingThisJob) {
            lore.add("&7Статус: &aНа смене");
            lore.add("&7Заработано за смену: &f" + earned + " &7монет");
        } else {
            lore.add("&7Статус: &cНе на смене");
        }
        lore.add("");
        lore.add("&eНажмите ЛКМ, чтобы открыть работу!");

        return named(iconMat, "&6&l" + job.displayName, lore);
    }

    private static ItemStack named(Material m, String name, List<String> lore) {
        ItemStack stack = new ItemStack(m);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color.color(name));
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore.stream().map(color::color).collect(Collectors.toList()));
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}

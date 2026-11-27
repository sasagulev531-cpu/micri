package Frontiks.Craft.quests;

import Frontiks.Craft.gui.MenuManager;
import Frontiks.Craft.jobs.JobKind;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.quests.v2.QuestEngineV2.LifecycleState;
import Frontiks.Craft.quests.v2.QuestEngineV2.QuestView;
import Frontiks.Craft.utils.color;
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
 * GUI выбора раздела квестов по работам (открывается по команде /quests).
 * Сейчас отображает раздел «Шахтёр» (MINER); при добавлении новых работ в JobRegistry
 * они автоматически появятся здесь карточками разделов.
 */
public final class QuestsHubMenu {

    public static final String MENU_ID = "quests_hub_menu";
    public static final int INV_SIZE = 27;
    private static final int[] JOB_SLOTS_SINGLE = {13};
    private static final int[] JOB_SLOTS_MULTI = {11, 12, 13, 14, 15, 10, 16};

    private static final Map<UUID, Map<Integer, String>> SLOT_JOBS = new ConcurrentHashMap<>();

    private final QuestManager quests;
    private final MiningSettings settings;

    public QuestsHubMenu(QuestManager quests, MiningSettings settings) {
        this.quests = quests;
        this.settings = settings;
    }

    public static String jobIdAt(UUID playerId, int slot) {
        Map<Integer, String> m = SLOT_JOBS.get(playerId);
        return m == null ? null : m.get(slot);
    }

    public static void clearState(UUID playerId) {
        SLOT_JOBS.remove(playerId);
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, INV_SIZE, color.color("&8Квесты работ — Разделы"));
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < INV_SIZE; i++) inv.setItem(i, glass);

        List<JobKind> jobs = new ArrayList<>(settings.jobRegistry().all());
        List<QuestView> allViews = quests.viewsFor(player);

        Map<Integer, String> slotMap = new HashMap<>();
        int[] layout = jobs.size() == 1 ? JOB_SLOTS_SINGLE : JOB_SLOTS_MULTI;

        for (int i = 0; i < Math.min(jobs.size(), layout.length); i++) {
            JobKind job = jobs.get(i);
            int slot = layout[i];
            slotMap.put(slot, job.id);
            inv.setItem(slot, buildJobCard(job, allViews));
        }

        inv.setItem(22, named(Material.OAK_DOOR, "&7Закрыть", List.of("&7Закрыть меню")));

        player.openInventory(inv);
        SLOT_JOBS.put(player.getUniqueId(), slotMap);
        MenuManager.setMenu(player.getUniqueId(), MENU_ID);
    }

    private ItemStack buildJobCard(JobKind job, List<QuestView> allViews) {
        int total = 0;
        int completed = 0;
        for (QuestView v : allViews) {
            String reqJob = v.spec().requires().jobId();
            if (reqJob == null || reqJob.isBlank() || reqJob.equalsIgnoreCase(job.id)) {
                total++;
                if (v.lifecycle() == LifecycleState.COMPLETED) {
                    completed++;
                }
            }
        }

        Material iconMat = Material.matchMaterial(job.iconMaterial.toUpperCase(Locale.ROOT));
        if (iconMat == null || !iconMat.isItem()) iconMat = Material.IRON_PICKAXE;

        List<String> lore = new ArrayList<>();
        lore.add("&7Раздел заданий профессии &f" + job.displayName + "&7.");
        lore.add("");
        lore.add("&7Доступно задач: &f" + total);
        lore.add("&7Выполнено: &a" + completed + "&7/&f" + total);
        lore.add("");
        lore.add("&eНажмите ЛКМ, чтобы открыть квесты!");

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

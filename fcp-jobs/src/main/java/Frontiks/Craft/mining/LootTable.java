package Frontiks.Craft.mining;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Чистая логика броска лута узла шахты.
 * Правила:
 * 1) Из 1 камня выпадает РОВНО 1 вид ресурса (взвешенный выбор по полю chance).
 * 2) Рудные блоки (*_ORE, RAW_*_BLOCK) автоматически нормализуются в нормальные предметы 1.21.11
 *    (COAL, RAW_IRON, RAW_COPPER, RAW_GOLD, LAPIS_LAZULI, REDSTONE, DIAMOND, EMERALD и т.д.).
 */
public final class LootTable {
    private LootTable() {}

    public static Map<String, Integer> roll(List<LootEntry> loot, Random rng) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (loot == null || loot.isEmpty()) return out;

        List<LootEntry> valid = new ArrayList<>();
        double totalWeight = 0.0;
        for (LootEntry e : loot) {
            if (e == null || e.material == null || e.material.isBlank()) continue;
            if (e.chance <= 0.0 || Double.isNaN(e.chance)) continue;
            valid.add(e);
            totalWeight += e.chance;
        }
        if (valid.isEmpty() || totalWeight <= 0.0) return out;

        double r = rng.nextDouble() * totalWeight;
        LootEntry chosen = valid.get(valid.size() - 1);
        for (LootEntry e : valid) {
            r -= e.chance;
            if (r < 0.0) {
                chosen = e;
                break;
            }
        }

        int min = Math.max(1, chosen.min);
        int max = Math.max(min, chosen.max);
        int count = min == max ? min : min + rng.nextInt(max - min + 1);
        if (count > 0) {
            out.put(normalizeDropMaterial(chosen.material), count);
        }
        return out;
    }

    /**
     * Преобразует блоки руд и необработанных рудных блоков в нормальные предметы добычи 1.21.11,
     * чтобы уголь всегда падал углём (COAL), а металлы — красивой рудой (RAW_IRON, RAW_COPPER, RAW_GOLD).
     */
    public static String normalizeDropMaterial(String raw) {
        if (raw == null) return "";
        String m = raw.trim().toUpperCase(Locale.ROOT);
        return switch (m) {
            case "COAL_ORE", "DEEPSLATE_COAL_ORE" -> "COAL";
            case "COPPER_ORE", "DEEPSLATE_COPPER_ORE", "RAW_COPPER_BLOCK" -> "RAW_COPPER";
            case "IRON_ORE", "DEEPSLATE_IRON_ORE", "RAW_IRON_BLOCK" -> "RAW_IRON";
            case "GOLD_ORE", "DEEPSLATE_GOLD_ORE", "RAW_GOLD_BLOCK" -> "RAW_GOLD";
            case "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE" -> "LAPIS_LAZULI";
            case "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE" -> "REDSTONE";
            case "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE" -> "DIAMOND";
            case "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE" -> "EMERALD";
            case "NETHER_QUARTZ_ORE" -> "QUARTZ";
            case "NETHER_GOLD_ORE" -> "GOLD_NUGGET";
            case "ANCIENT_DEBRIS" -> "NETHERITE_SCRAP";
            default -> m;
        };
    }

    /** Сбалансированный пресет лута для всех узлов шахты под 1.21.11 (без рудных блоков). */
    public static List<LootEntry> defaultMinePreset121() {
        return List.of(
                new LootEntry("COAL", 1, 3, 0.32),
                new LootEntry("RAW_COPPER", 1, 3, 0.22),
                new LootEntry("RAW_IRON", 1, 2, 0.18),
                new LootEntry("REDSTONE", 2, 4, 0.10),
                new LootEntry("LAPIS_LAZULI", 2, 4, 0.08),
                new LootEntry("RAW_GOLD", 1, 2, 0.06),
                new LootEntry("DIAMOND", 1, 1, 0.03),
                new LootEntry("EMERALD", 1, 1, 0.01)
        );
    }
}

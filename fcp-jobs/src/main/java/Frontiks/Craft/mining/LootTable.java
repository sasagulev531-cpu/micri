package Frontiks.Craft.mining;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Чистая логика броска лута. Отделена от Bukkit для тестирования. */
public final class LootTable {
    private LootTable() {}

    /**
     * Бросает каждую запись независимо. Возвращает материал -> суммарное количество.
     * Чистый метод: результат зависит только от входных данных и rng.
     */
    public static Map<String, Integer> roll(List<LootEntry> loot, Random rng) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (loot == null) return out;
        for (LootEntry e : loot) {
            if (e == null || e.material == null || e.material.isBlank()) continue;
            if (e.chance <= 0.0) continue;
            if (e.chance < 1.0 && rng.nextDouble() >= e.chance) continue;
            int min = Math.max(1, e.min);
            int max = Math.max(min, e.max);
            int count = min == max ? min : min + rng.nextInt(max - min + 1);
            if (count > 0) out.merge(e.material, count, Integer::sum);
        }
        return out;
    }
}

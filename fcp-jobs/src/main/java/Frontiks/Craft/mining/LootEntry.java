package Frontiks.Craft.mining;

import java.util.Map;

/** Одна строка лута узла: материал, диапазон количества и вероятность. Чистая логика, без Bukkit. */
public final class LootEntry {
    public String material;
    public int min;
    public int max;
    public double chance;

    public LootEntry() {}

    public LootEntry(String material, int min, int max, double chance) {
        this.material = material;
        this.min = min;
        this.max = max;
        this.chance = chance;
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "material", material == null ? "" : material,
                "min", min,
                "max", max,
                "chance", chance
        );
    }

    public static LootEntry fromMap(Map<?, ?> raw) {
        LootEntry e = new LootEntry();
        Object m = raw.get("material");
        e.material = m == null ? "" : String.valueOf(m).trim().toUpperCase();
        e.min = number(raw.get("min"), 1);
        e.max = number(raw.get("max"), e.min);
        Object ch = raw.get("chance");
        e.chance = ch instanceof Number ? ((Number) ch).doubleValue() : parseDouble(String.valueOf(ch), 1.0);
        return e;
    }

    static int number(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { return fallback; }
    }

    static double parseDouble(String s, double fallback) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return fallback; }
    }
}

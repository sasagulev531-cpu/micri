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

    /** Точное отображение шанса без грубого округления (например 0.90 -> 90%, 0.006 -> 0.6%). */
    public static String formatPercent(double chance) {
        double pct = chance * 100.0;
        if (Math.abs(pct - Math.rint(pct)) < 1e-6) {
            return ((long) Math.rint(pct)) + "%";
        }
        String formatted = String.format(java.util.Locale.US, "%.3f", pct);
        while (formatted.contains(".") && (formatted.endsWith("0") || formatted.endsWith("."))) {
            if (formatted.endsWith(".")) {
                formatted = formatted.substring(0, formatted.length() - 1);
                break;
            }
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        return formatted + "%";
    }
}

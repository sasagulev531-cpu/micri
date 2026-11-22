package Frontiks.Craft.mining;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Состояние одного добываемого узла. Чистые данные без Bukkit-типов,
 * чтобы логику можно было покрыть unit-тестами.
 */
public final class MiningNode {
    public String id;
    public String worldUuid;
    public String worldName;
    public int x;
    public int y;
    public int z;

    /** Ломаемые стадии по порядку, например [TUFF, STONE, ANDESITE]. */
    public List<String> chain = new ArrayList<>();
    /** Вид блока в истощённом состоянии, например COBBLESTONE. */
    public String depletedMaterial = "COBBLESTONE";
    public List<LootEntry> loot = new ArrayList<>();
    public long cooldownMillis = 45_000L;

    /** Текущая стадия (0-based, действует пока !depleted). */
    public int stageIndex;
    /** Истощён ли узел (ожидает восстановления). */
    public boolean depleted;
    /** Момент (epoch ms), когда узел снова станет добываемым. */
    public long depletedUntilMs;
    /**
     * Десинхронизация: кто-то изменил блок после регистрации узла.
     * Узел отключается от механики и не трогает блок до разбора админом.
     */
    public boolean broken;
    public long registeredAtMs;

    public String blockKey() {
        return worldUuid + ":" + x + ":" + y + ":" + z;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world-uuid", worldUuid == null ? "" : worldUuid);
        m.put("world", worldName == null ? "" : worldName);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        m.put("chain", new ArrayList<>(chain));
        m.put("depleted-material", depletedMaterial == null ? "" : depletedMaterial);
        List<Map<String, Object>> lootMaps = new ArrayList<>();
        for (LootEntry e : loot) lootMaps.add(e.toMap());
        m.put("loot", lootMaps);
        m.put("cooldown-seconds", cooldownMillis / 1000L);
        m.put("stage-index", stageIndex);
        m.put("depleted", depleted);
        m.put("depleted-until", depletedUntilMs);
        m.put("broken", broken);
        m.put("registered-at", registeredAtMs);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static MiningNode fromMap(String id, Map<?, ?> raw) {
        MiningNode n = new MiningNode();
        n.id = id;
        n.worldUuid = string(raw.get("world-uuid"));
        n.worldName = string(raw.get("world"));
        n.x = number(raw.get("x"), 0);
        n.y = number(raw.get("y"), 0);
        n.z = number(raw.get("z"), 0);

        Object chain = raw.get("chain");
        if (chain instanceof List<?> list) {
            for (Object o : list) {
                String s = string(o).toUpperCase();
                if (!s.isBlank()) n.chain.add(s);
            }
        }
        n.depletedMaterial = string(raw.get("depleted-material")).toUpperCase();
        if (n.depletedMaterial.isBlank()) n.depletedMaterial = "COBBLESTONE";

        Object loot = raw.get("loot");
        if (loot instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> map) n.loot.add(LootEntry.fromMap(map));
            }
        }

        // Обратная совместимость: если админ задавал cooldown в секундах (как в mining.cooldown-seconds).
        long seconds = longNumber(raw.get("cooldown-seconds"), -1L);
        long millis = longNumber(raw.get("cooldown-millis"), -1L);
        if (millis >= 0) n.cooldownMillis = millis;
        else if (seconds >= 0) n.cooldownMillis = seconds * 1000L;
        else n.cooldownMillis = 45_000L;

        n.stageIndex = number(raw.get("stage-index"), 0);
        n.depleted = bool(raw.get("depleted"), false);
        n.depletedUntilMs = longNumber(raw.get("depleted-until"), 0L);
        n.broken = bool(raw.get("broken"), false);
        n.registeredAtMs = longNumber(raw.get("registered-at"), 0L);
        return n;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final MiningNode n = new MiningNode();
        public Builder world(String uuid, String name) { n.worldUuid = uuid; n.worldName = name; return this; }
        public Builder pos(int x, int y, int z) { n.x = x; n.y = y; n.z = z; return this; }
        public Builder chain(List<String> chain) { n.chain = new ArrayList<>(chain); return this; }
        public Builder depletedMaterial(String m) { n.depletedMaterial = m; return this; }
        public Builder loot(List<LootEntry> loot) { n.loot = new ArrayList<>(loot); return this; }
        public Builder cooldownMillis(long ms) { n.cooldownMillis = ms; return this; }
        public MiningNode build() {
            if (n.id == null) n.id = UUID.randomUUID().toString();
            if (n.registeredAtMs == 0L) n.registeredAtMs = System.currentTimeMillis();
            n.chain = new ArrayList<>(n.chain);
            return n;
        }
    }

    static String string(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    static int number(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(string(v)); } catch (Exception e) { return fallback; }
    }

    static long longNumber(Object v, long fallback) {
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(string(v)); } catch (Exception e) { return fallback; }
    }

    static boolean bool(Object v, boolean fallback) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }
}

package Frontiks.Craft.quests;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Описание одного задания из конфига. Чистая логика. */
public final class QuestDef {

    /** Максимальное количество цели задания: 2^16 = 65536. */
    public static final int MAX_AMOUNT = 65536;

    public enum Type {
        /** Сломать блок в шахте (конкретный блок или ANY — любой блок узла). */
        BREAK_BLOCK,
        /** Добыть предмет из лута узлов шахты (конкретный предмет или ANY). */
        LOOT_AMOUNT,
        /** Получить предмет (из лута шахты или выплавкой в печи; конкретный предмет или ANY). */
        OBTAIN_ITEM,
        /** Полностью разработать N узлов шахты (все стадии). */
        NODES_COMPLETED
    }

    public final String id;
    public final Type type;
    /** Для BREAK_BLOCK / LOOT_AMOUNT / OBTAIN_ITEM — материал (или "ANY"), для NODES_COMPLETED — null. */
    public final String material;
    public final int amount;
    public final String title;
    public final String description;
    public final int reward;

    public QuestDef(String id, Type type, String material, int amount,
                    String title, String description, int reward) {
        this.id = id;
        this.type = type;
        this.material = material;
        this.amount = amount;
        this.title = title;
        this.description = description;
        this.reward = reward;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("title", title);
        map.put("description", description == null ? "" : description);
        map.put("type", type.name());
        if (material != null && !material.isBlank()) {
            map.put("material", material);
        }
        map.put("amount", amount);
        map.put("reward", reward);
        return map;
    }

    /**
     * Парсит задание из конфига. Бросает IllegalArgumentException с понятным сообщением,
     * если запись невалидна (вызывающая сторона логирует и пропускает задание).
     */
    public static QuestDef fromRaw(Map<?, ?> raw, String source) {
        String where = source + ": ";
        String id = str(raw.get("id"));
        if (id.isBlank()) throw new IllegalArgumentException(where + "пустой id задания");

        String typeRaw = str(raw.get("type")).toUpperCase(Locale.ROOT);
        Type type = switch (typeRaw) {
            case "BREAK_BLOCK", "BREAK", "СЛОМАТЬ" -> Type.BREAK_BLOCK;
            case "LOOT_AMOUNT", "COLLECT_LOOT", "MINE", "ДОБЫТЬ" -> Type.LOOT_AMOUNT;
            case "OBTAIN_ITEM", "OBTAIN", "GET", "ПОЛУЧИТЬ" -> Type.OBTAIN_ITEM;
            case "NODES_COMPLETED", "COMPLETE_NODES", "NODES" -> Type.NODES_COMPLETED;
            default -> throw new IllegalArgumentException(
                    where + "задание '" + id + "': неизвестный type='" + typeRaw
                            + "' (допустимо: BREAK_BLOCK, LOOT_AMOUNT/COLLECT_LOOT, OBTAIN_ITEM, NODES_COMPLETED/COMPLETE_NODES)");
        };

        String material = str(raw.get("material")).toUpperCase(Locale.ROOT);
        if (type != Type.NODES_COMPLETED && material.isBlank()) {
            throw new IllegalArgumentException(where + "задание '" + id + "': для " + type + " нужен material (или ANY)");
        }

        int amount = num(raw.get("amount"), -1);
        if (amount < 1 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException(where + "задание '" + id + "': amount должен быть от 1 до " + MAX_AMOUNT);
        }

        int reward = num(raw.get("reward"), -1);
        if (reward < 0) throw new IllegalArgumentException(where + "задание '" + id + "': reward должен быть >= 0");

        String title = str(raw.get("title"));
        if (title.isBlank()) title = id;
        String description = str(raw.get("description"));

        return new QuestDef(id, type, type == Type.NODES_COMPLETED || material.isBlank() ? null : material,
                amount, title, description, reward);
    }

    public String actionLabelRu() {
        return switch (type) {
            case BREAK_BLOCK -> "Сломать";
            case LOOT_AMOUNT -> "Добыть";
            case OBTAIN_ITEM -> "Получить";
            case NODES_COMPLETED -> "Разработать узлов";
        };
    }

    static String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    static int num(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(str(v)); } catch (Exception e) { return fallback; }
    }
}

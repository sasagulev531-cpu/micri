package Frontiks.Craft.quests;

import java.util.Locale;
import java.util.Map;

/** Описание одного задания из конфига. Чистая логика. */
public final class QuestDef {

    public enum Type { LOOT_AMOUNT, NODES_COMPLETED }

    public final String id;
    public final Type type;
    /** Для LOOT_AMOUNT — материал, иначе null. */
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
            case "LOOT_AMOUNT", "COLLECT_LOOT" -> Type.LOOT_AMOUNT;
            case "NODES_COMPLETED", "COMPLETE_NODES" -> Type.NODES_COMPLETED;
            default -> throw new IllegalArgumentException(
                    where + "задание '" + id + "': неизвестный type='" + typeRaw
                            + "' (допустимо: COLLECT_LOOT/LOOT_AMOUNT или COMPLETE_NODES/NODES_COMPLETED)");
        };

        String material = str(raw.get("material")).toUpperCase(Locale.ROOT);
        if (type == Type.LOOT_AMOUNT && material.isBlank()) {
            throw new IllegalArgumentException(where + "задание '" + id + "': для LOOT_AMOUNT нужен material");
        }

        int amount = num(raw.get("amount"), -1);
        if (amount <= 0) throw new IllegalArgumentException(where + "задание '" + id + "': amount должен быть > 0");

        int reward = num(raw.get("reward"), -1);
        if (reward < 0) throw new IllegalArgumentException(where + "задание '" + id + "': reward должен быть >= 0");

        String title = str(raw.get("title"));
        if (title.isBlank()) title = id;
        String description = str(raw.get("description"));

        return new QuestDef(id, type, material.isBlank() ? null : material, amount, title, description, reward);
    }

    static String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    static int num(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(str(v)); } catch (Exception e) { return fallback; }
    }
}

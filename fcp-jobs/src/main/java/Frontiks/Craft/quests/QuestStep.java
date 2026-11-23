package Frontiks.Craft.quests;

import java.util.Locale;
import java.util.Map;

/** Один этап (шаг) внутри задания или цепочки заданий. Чистая логика без Bukkit. */
public final class QuestStep {

    public final QuestDef.Type type;
    /** Материал (например RAW_IRON, IRON_INGOT, STONE, ANY) или null для COMPLETE_NODES / EARN_COINS. */
    public final String material;
    /** Целевое количество от 1 до 65536 (2^16). */
    public final int amount;
    /** Требуется ли активная смена шахтёра (/work в зоне шахты) для зачёта этого шага. */
    public final boolean requireWorking;
    /** Отображаемое описание этапа в меню и в чате. */
    public final String title;

    public QuestStep(QuestDef.Type type, String material, int amount, boolean requireWorking, String title) {
        this.type = type;
        this.material = (material == null || material.isBlank()) ? null : material.trim().toUpperCase(Locale.ROOT);
        this.amount = amount;
        this.requireWorking = requireWorking;
        this.title = (title == null || title.isBlank()) ? defaultTitle(type, this.material, amount) : title.trim();
    }

    public static QuestStep fromRaw(Map<?, ?> raw, String where) {
        String typeRaw = QuestDef.str(raw.get("type")).toUpperCase(Locale.ROOT);
        QuestDef.Type type = QuestDef.parseType(typeRaw, where);

        String material = QuestDef.str(raw.get("material")).toUpperCase(Locale.ROOT);
        boolean needsMaterial = type != QuestDef.Type.NODES_COMPLETED && type != QuestDef.Type.EARN_COINS;
        if (needsMaterial && material.isBlank()) {
            throw new IllegalArgumentException(where + ": для type=" + type + " укажите material (например RAW_IRON или ANY)");
        }

        int amount = QuestDef.num(raw.get("amount"), -1);
        if (amount < 1 || amount > QuestDef.MAX_AMOUNT) {
            throw new IllegalArgumentException(where + ": amount должен быть от 1 до " + QuestDef.MAX_AMOUNT);
        }

        boolean defaultWorking = switch (type) {
            case LOOT_AMOUNT, BREAK_BLOCK, NODES_COMPLETED, EARN_COINS -> true;
            case SMELT_ITEM, CRAFT_ITEM, OBTAIN_ITEM -> false;
        };
        Object rw = raw.get("require-working");
        boolean requireWorking = rw == null ? defaultWorking : QuestAssignment.bool(rw);

        String stepTitle = QuestDef.str(raw.get("title"));
        return new QuestStep(type, needsMaterial ? material : null, amount, requireWorking, stepTitle);
    }

    public static String defaultTitle(QuestDef.Type type, String material, int amount) {
        String mat = (material == null || "ANY".equalsIgnoreCase(material)) ? "любой" : material;
        return switch (type) {
            case BREAK_BLOCK -> "ANY".equalsIgnoreCase(material)
                    ? "Сломать блоки в шахте: " + amount
                    : "Сломать " + mat + " x" + amount;
            case LOOT_AMOUNT -> "ANY".equalsIgnoreCase(material)
                    ? "Добыть ресурсы в шахте: " + amount
                    : "Добыть " + mat + " x" + amount;
            case SMELT_ITEM -> "ANY".equalsIgnoreCase(material)
                    ? "Переплавить предметы: " + amount
                    : "Переплавить " + mat + " x" + amount;
            case CRAFT_ITEM -> "ANY".equalsIgnoreCase(material)
                    ? "Скрафтить предметы: " + amount
                    : "Скрафтить " + mat + " x" + amount;
            case OBTAIN_ITEM -> "ANY".equalsIgnoreCase(material)
                    ? "Получить предметы: " + amount
                    : "Получить " + mat + " x" + amount;
            case NODES_COMPLETED -> "Разработать узлов: " + amount;
            case EARN_COINS -> "Заработать монет в шахте: " + amount;
        };
    }
}

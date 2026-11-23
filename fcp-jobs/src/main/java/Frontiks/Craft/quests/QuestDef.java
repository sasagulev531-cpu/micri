package Frontiks.Craft.quests;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Описание задания (одиночного или многоэтапной цепочки) из quests.yml.
 * Чистая логика без зависимости от Bukkit.
 */
public final class QuestDef {

    /** Максимальное количество цели любого шага: 2^16 = 65536. */
    public static final int MAX_AMOUNT = 65536;

    public enum Type {
        /** Сломать блок/стадию в шахте (алиасы в конфиге: BREAK, BREAK_BLOCK). */
        BREAK_BLOCK,
        /** Добыть предмет с узла шахты (алиасы в конфиге: MINE, LOOT, COLLECT_LOOT, LOOT_AMOUNT). */
        LOOT_AMOUNT,
        /** Переплавить предмет в печи/плавильне (алиасы: SMELT, FURNACE, SMELT_ITEM). */
        SMELT_ITEM,
        /** Скрафтить предмет (алиасы: CRAFT, CRAFT_ITEM). */
        CRAFT_ITEM,
        /** Получить предмет любым путём: шахта, печь или крафт (алиасы: OBTAIN, GET, OBTAIN_ITEM). */
        OBTAIN_ITEM,
        /** Полностью пройти все стадии N узлов шахты (алиасы: COMPLETE_NODES, NODES, NODES_COMPLETED). */
        NODES_COMPLETED,
        /** Заработать N монет на смене в шахте (алиасы: EARN_COINS, COINS). */
        EARN_COINS
    }

    public final String id;
    public final String title;
    public final List<String> descriptionLines;
    public final String icon;
    /** Если true — пока игрок не нажмёт «Взять задание» в GUI, прогресс не засчитывается. */
    public final boolean requireAccept;
    /** Последовательные этапы (минимум 1 этап). */
    public final List<QuestStep> steps;
    public final int reward;
    /** Дополнительные предметы в награду, формат "MATERIAL AMOUNT" (например "DIAMOND 3"). */
    public final List<String> rewardItems;
    /** Консольные команды при выполнении (%player% заменяется на ник игрока). */
    public final List<String> rewardCommands;

    // Поля первого шага для обратной совместимости
    public final Type type;
    public final String material;
    public final int amount;
    public final String description;

    /** Конструктор одиночного задания (обратная совместимость и тесты). */
    public QuestDef(String id, Type type, String material, int amount,
                    String title, String description, int reward) {
        this(
                id,
                title,
                (description == null || description.isBlank()) ? List.of() : List.of(description),
                null,
                defaultRequireAcceptFor(List.of(new QuestStep(type, material, amount,
                        type == Type.LOOT_AMOUNT || type == Type.BREAK_BLOCK
                                || type == Type.NODES_COMPLETED || type == Type.EARN_COINS,
                        null)), false),
                List.of(new QuestStep(type, material, amount,
                        type == Type.LOOT_AMOUNT || type == Type.BREAK_BLOCK
                                || type == Type.NODES_COMPLETED || type == Type.EARN_COINS,
                        null)),
                reward,
                List.of(),
                List.of()
        );
    }

    public QuestDef(String id,
                    String title,
                    List<String> descriptionLines,
                    String icon,
                    boolean requireAccept,
                    List<QuestStep> steps,
                    int reward,
                    List<String> rewardItems,
                    List<String> rewardCommands) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Задание '" + id + "' должно содержать хотя бы 1 этап");
        }
        this.id = id;
        this.title = title;
        this.descriptionLines = descriptionLines == null ? List.of() : List.copyOf(descriptionLines);
        this.icon = (icon == null || icon.isBlank()) ? null : icon.trim().toUpperCase(Locale.ROOT);
        this.requireAccept = requireAccept;
        this.steps = List.copyOf(steps);
        this.reward = reward;
        this.rewardItems = rewardItems == null ? List.of() : List.copyOf(rewardItems);
        this.rewardCommands = rewardCommands == null ? List.of() : List.copyOf(rewardCommands);

        QuestStep first = this.steps.get(0);
        this.type = first.type;
        this.material = first.material;
        this.amount = first.amount;
        this.description = this.descriptionLines.isEmpty() ? "" : String.join(" ", this.descriptionLines);
    }

    public boolean isChain() {
        return steps.size() > 1;
    }

    public static QuestDef fromRaw(Map<?, ?> raw, String source) {
        return fromRaw(raw, source, false);
    }

    /**
     * Парсит задание из quests.yml / hrabota.yml.
     * Поддерживает как одиночный формат (type/material/amount), так и цепочку (steps: [...]).
     */
    public static QuestDef fromRaw(Map<?, ?> raw, String source, boolean weeklyPoolDefaultAccept) {
        String where = source + ": ";
        String id = str(raw.get("id"));
        if (id.isBlank()) throw new IllegalArgumentException(where + "пустой id задания");

        String title = str(raw.get("title"));
        if (title.isBlank()) title = id;

        List<String> descLines = parseStringOrList(raw.get("description"));
        String icon = str(raw.get("icon"));

        int reward = num(raw.get("reward"), num(raw.get("reward-coins"), 0));
        if (reward < 0) {
            throw new IllegalArgumentException(where + "задание '" + id + "': reward должен быть >= 0");
        }

        List<String> rewardItems = parseStringOrList(raw.get("reward-items"));
        List<String> rewardCommands = parseStringOrList(raw.get("reward-commands"));

        List<QuestStep> steps = new ArrayList<>();
        Object rawSteps = raw.get("steps");
        if (rawSteps instanceof List<?> list && !list.isEmpty()) {
            int idx = 1;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> stepMap)) {
                    throw new IllegalArgumentException(where + "задание '" + id + "', step #" + idx + ": ожидался объект");
                }
                steps.add(QuestStep.fromRaw(stepMap, where + "задание '" + id + "', step #" + idx));
                idx++;
            }
        } else {
            steps.add(QuestStep.fromRaw(raw, where + "задание '" + id + "'"));
        }

        Object raObj = raw.get("require-accept");
        boolean requireAccept = raObj != null
                ? QuestAssignment.bool(raObj)
                : defaultRequireAcceptFor(steps, weeklyPoolDefaultAccept);

        return new QuestDef(id, title, descLines, icon, requireAccept, steps, reward, rewardItems, rewardCommands);
    }

    private static boolean defaultRequireAcceptFor(List<QuestStep> steps, boolean weeklyDefault) {
        if (weeklyDefault || steps.size() > 1) return true;
        QuestStep only = steps.get(0);
        return switch (only.type) {
            case LOOT_AMOUNT, BREAK_BLOCK, NODES_COMPLETED, EARN_COINS -> false;
            case SMELT_ITEM, CRAFT_ITEM, OBTAIN_ITEM -> true;
        };
    }

    static Type parseType(String typeRaw, String where) {
        return switch (typeRaw) {
            case "BREAK", "BREAK_BLOCK" -> Type.BREAK_BLOCK;
            case "MINE", "LOOT", "COLLECT_LOOT", "LOOT_AMOUNT" -> Type.LOOT_AMOUNT;
            case "SMELT", "FURNACE", "SMELT_ITEM" -> Type.SMELT_ITEM;
            case "CRAFT", "CRAFT_ITEM" -> Type.CRAFT_ITEM;
            case "OBTAIN", "GET", "OBTAIN_ITEM" -> Type.OBTAIN_ITEM;
            case "COMPLETE_NODES", "NODES", "NODES_COMPLETED" -> Type.NODES_COMPLETED;
            case "EARN_COINS", "COINS", "EARN_MONEY" -> Type.EARN_COINS;
            default -> throw new IllegalArgumentException(
                    where + ": неизвестный type='" + typeRaw
                            + "' (допустимо: MINE, BREAK, COMPLETE_NODES, SMELT, CRAFT, OBTAIN, EARN_COINS)");
        };
    }

    private static List<String> parseStringOrList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null) out.add(String.valueOf(o));
            }
            return out;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? List.of() : List.of(s);
    }

    static String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    static int num(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(str(v)); } catch (Exception e) { return fallback; }
    }
}

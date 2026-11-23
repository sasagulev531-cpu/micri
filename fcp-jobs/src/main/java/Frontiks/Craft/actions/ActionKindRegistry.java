package Frontiks.Craft.actions;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Расширяемый реестр всех видов событий (ActionKind) для парсера и движка заданий v2.
 */
public final class ActionKindRegistry {

    public static final String MINE = "MINE";
    public static final String BREAK = "BREAK";
    public static final String COMPLETE_NODES = "COMPLETE_NODES";
    public static final String SMELT = "SMELT";
    public static final String CRAFT = "CRAFT";
    public static final String OBTAIN = "OBTAIN";
    public static final String EARN_COINS = "EARN_COINS";
    public static final String JOB_ACTION = "JOB_ACTION";
    public static final String TIME_AS_JOB = "TIME_AS_JOB";

    private final Map<String, ActionKind> byCanonical = new LinkedHashMap<>();
    private final Map<String, ActionKind> byLookup = new LinkedHashMap<>();

    public static ActionKindRegistry withBuiltins() {
        ActionKindRegistry reg = new ActionKindRegistry();
        reg.register(new ActionKind(
                MINE, Set.of("LOOT", "COLLECT_LOOT", "LOOT_AMOUNT"),
                true, true, "Добыть"));
        reg.register(new ActionKind(
                BREAK, Set.of("BREAK_BLOCK"),
                true, true, "Сломать"));
        reg.register(new ActionKind(
                COMPLETE_NODES, Set.of("NODES", "NODES_COMPLETED"),
                false, true, "Разработать узлов"));
        reg.register(new ActionKind(
                SMELT, Set.of("FURNACE", "SMELT_ITEM"),
                true, false, "Переплавить"));
        reg.register(new ActionKind(
                CRAFT, Set.of("CRAFT_ITEM"),
                true, false, "Скрафтить"));
        reg.register(new ActionKind(
                OBTAIN, Set.of("GET", "OBTAIN_ITEM"),
                true, false, "Получить",
                (action, targetMat) -> {
                    String ev = action.eventId();
                    if (!OBTAIN.equals(ev) && !MINE.equals(ev) && !SMELT.equals(ev) && !CRAFT.equals(ev)) {
                        return 0;
                    }
                    return ActionKind.materialMatches(action.material(), targetMat) ? action.amount() : 0;
                }));
        reg.register(new ActionKind(
                EARN_COINS, Set.of("COINS", "EARN_MONEY"),
                false, true, "Заработать монет"));
        reg.register(new ActionKind(
                JOB_ACTION, Set.of("WORK_ACTION"),
                false, true, "Выполнить действий смены"));
        reg.register(new ActionKind(
                TIME_AS_JOB, Set.of(),
                false, true, "Отработать минут"));
        return reg;
    }

    public synchronized void register(ActionKind kind) {
        byCanonical.put(kind.id, kind);
        byLookup.put(kind.id, kind);
        for (String alias : kind.aliases) {
            byLookup.put(alias.toUpperCase(Locale.ROOT), kind);
        }
    }

    public synchronized ActionKind resolve(String nameOrAlias) {
        if (nameOrAlias == null || nameOrAlias.isBlank()) return null;
        return byLookup.get(nameOrAlias.trim().toUpperCase(Locale.ROOT));
    }

    public synchronized Collection<ActionKind> allCanonical() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(byCanonical).values());
    }
}

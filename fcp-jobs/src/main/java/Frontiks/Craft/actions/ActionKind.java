package Frontiks.Craft.actions;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Дескриптор типа события в реестре ActionKindRegistry (R2).
 * Новый тип условия добавляется созданием экземпляра ActionKind без правки движка заданий.
 */
public final class ActionKind {

    @FunctionalInterface
    public interface Matcher {
        int matchAmount(JobAction action, String targetMaterial);
    }

    public final String id;
    public final Set<String> aliases;
    public final boolean requiresMaterial;
    public final boolean defaultRequireWorking;
    public final String defaultTitlePrefix;
    private final Matcher matcher;

    public ActionKind(String id,
                      Set<String> aliases,
                      boolean requiresMaterial,
                      boolean defaultRequireWorking,
                      String defaultTitlePrefix,
                      Matcher customMatcher) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ActionKind id пуст");
        }
        this.id = id.trim().toUpperCase(Locale.ROOT);
        this.aliases = aliases == null ? Set.of() : aliases.stream()
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.requiresMaterial = requiresMaterial;
        this.defaultRequireWorking = defaultRequireWorking;
        this.defaultTitlePrefix = defaultTitlePrefix == null ? this.id : defaultTitlePrefix;
        this.matcher = customMatcher != null ? customMatcher : this::defaultMatch;
    }

    public ActionKind(String id,
                      Set<String> aliases,
                      boolean requiresMaterial,
                      boolean defaultRequireWorking,
                      String defaultTitlePrefix) {
        this(id, aliases, requiresMaterial, defaultRequireWorking, defaultTitlePrefix, null);
    }

    public int match(JobAction action, String targetMaterial) {
        if (action == null || action.amount() <= 0) return 0;
        return matcher.matchAmount(action, targetMaterial);
    }

    private int defaultMatch(JobAction action, String targetMaterial) {
        if (!this.id.equals(action.eventId())) return 0;
        if (this.requiresMaterial && !materialMatches(action.material(), targetMaterial)) {
            return 0;
        }
        return action.amount();
    }

    public static boolean materialMatches(String actualMaterial, String targetMaterial) {
        if (targetMaterial == null || targetMaterial.isBlank()
                || "ANY".equalsIgnoreCase(targetMaterial)
                || "*".equals(targetMaterial)) {
            return actualMaterial != null && !actualMaterial.isBlank();
        }
        return actualMaterial != null && actualMaterial.equalsIgnoreCase(targetMaterial);
    }
}

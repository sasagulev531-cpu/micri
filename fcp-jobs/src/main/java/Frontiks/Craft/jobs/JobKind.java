package Frontiks.Craft.jobs;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Описание вида работы в шахте.
 * По решению владельца в игре сейчас активен только MINER («Шахтёр»),
 * но реестр остаётся открытым для регистрации будущих работ.
 */
public final class JobKind {

    public static final String MINER_ID = "MINER";

    public final String id;
    public final String displayName;
    public final String iconMaterial;
    public final List<String> description;
    public final Set<String> trackedEvents;
    public final boolean defaultJob;

    public JobKind(String id,
                   String displayName,
                   String iconMaterial,
                   List<String> description,
                   Set<String> trackedEvents,
                   boolean defaultJob) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("JobKind id пуст");
        }
        this.id = id.trim().toUpperCase(Locale.ROOT);
        this.displayName = displayName == null || displayName.isBlank() ? this.id : displayName;
        this.iconMaterial = iconMaterial == null || iconMaterial.isBlank()
                ? "IRON_PICKAXE" : iconMaterial.trim().toUpperCase(Locale.ROOT);
        this.description = description == null ? List.of() : List.copyOf(description);
        this.trackedEvents = trackedEvents == null ? Set.of() : Set.copyOf(trackedEvents);
        this.defaultJob = defaultJob;
    }

    public static JobKind miner() {
        return new JobKind(
                MINER_ID,
                "&eШахтёр",
                "IRON_PICKAXE",
                List.of("&7Добыча руды и разработка горных узлов в шахте."),
                Set.of("MINE", "BREAK", "COMPLETE_NODES", "EARN_COINS", "JOB_ACTION", "TIME_AS_JOB"),
                true
        );
    }
}

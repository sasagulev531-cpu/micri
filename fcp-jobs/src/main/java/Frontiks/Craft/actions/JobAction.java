package Frontiks.Craft.actions;

import java.util.Locale;

/**
 * Универсальное событие действия игрока (слой R2, чистый Java без Bukkit).
 */
public record JobAction(
        String eventId,
        String material,
        int amount,
        Integer blockY,
        boolean workingInMine,
        String jobId
) {
    public JobAction {
        eventId = eventId == null ? "" : eventId.trim().toUpperCase(Locale.ROOT);
        material = (material == null || material.isBlank()) ? null : material.trim().toUpperCase(Locale.ROOT);
        amount = Math.max(0, amount);
        jobId = (jobId == null || jobId.isBlank()) ? null : jobId.trim().toUpperCase(Locale.ROOT);
    }

    public static JobAction of(String eventId, String material, int amount,
                               Integer blockY, boolean workingInMine, String jobId) {
        return new JobAction(eventId, material, amount, blockY, workingInMine, jobId);
    }
}

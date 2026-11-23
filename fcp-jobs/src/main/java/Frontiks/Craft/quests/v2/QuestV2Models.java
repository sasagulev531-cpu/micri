package Frontiks.Craft.quests.v2;

import Frontiks.Craft.actions.ActionKind;

import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Модели декларативного конфига заданий v2 (§5 ТЗ, чистый Java без Bukkit).
 */
public final class QuestV2Models {

    public static final int MAX_AMOUNT = 65536;

    private QuestV2Models() {}

    public enum PeriodKind {
        DAILY, WEEKLY, INTERVAL, ONCE
    }

    public record PeriodTemplate(
            String name,
            PeriodKind kind,
            int days,
            int count
    ) {}

    public record RequiresSpec(
            String questId,
            String jobId,
            String permission
    ) {
        public static final RequiresSpec NONE = new RequiresSpec(null, null, null);

        public boolean isEmpty() {
            return (questId == null || questId.isBlank())
                    && (jobId == null || jobId.isBlank())
                    && (permission == null || permission.isBlank());
        }
    }

    public record ConditionSpec(
            ActionKind kind,
            String material,
            int amount,
            Integer minDepth,
            Integer maxDepth,
            String jobId,
            boolean requireWorking,
            String label
    ) {
        public String describe() {
            if (label != null && !label.isBlank()) return label;
            StringBuilder sb = new StringBuilder(kind.defaultTitlePrefix);
            if (material != null && !material.isBlank() && !"ANY".equalsIgnoreCase(material) && !"*".equals(material)) {
                sb.append(' ').append(material);
            } else if (kind.requiresMaterial) {
                sb.append(" (любой)");
            }
            if (amount > 0) {
                sb.append(" x").append(amount);
            }
            if (minDepth != null && maxDepth != null) {
                sb.append(" [Y: ").append(minDepth).append("..").append(maxDepth).append(']');
            } else if (minDepth != null) {
                sb.append(" [Y >= ").append(minDepth).append(']');
            } else if (maxDepth != null) {
                sb.append(" [Y <= ").append(maxDepth).append(']');
            }
            return sb.toString();
        }
    }

    public record TimeSpec(
            String asJobId,
            int minutes
    ) {
        public String describe() {
            return "Отработать на смене " + minutes + " мин.";
        }
    }

    public enum GroupMode {
        NONE, ALL, ANY
    }

    public record StepSpec(
            String name,
            GroupMode groupMode,
            List<ConditionSpec> conditions,
            int anyAmount,
            TimeSpec time
    ) {
        public StepSpec {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
    }

    public record RewardItemSpec(
            String material,
            int amount,
            double chance
    ) {}

    public record RewardsSpec(
            int coins,
            List<RewardItemSpec> items,
            List<String> commands,
            Map<String, Object> extra
    ) {
        public static final RewardsSpec EMPTY = new RewardsSpec(0, List.of(), List.of(), Map.of());

        public RewardsSpec {
            items = items == null ? List.of() : List.copyOf(items);
            commands = commands == null ? List.of() : List.copyOf(commands);
            extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
        }
    }

    public record QuestSpec(
            String id,
            String title,
            String icon,
            List<String> description,
            String category,
            boolean manualAccept,
            RequiresSpec requires,
            List<StepSpec> steps,
            RewardsSpec rewards,
            List<String> unlockQuestIds
    ) {
        public QuestSpec {
            description = description == null ? List.of() : List.copyOf(description);
            requires = requires == null ? RequiresSpec.NONE : requires;
            steps = steps == null ? List.of() : List.copyOf(steps);
            rewards = rewards == null ? RewardsSpec.EMPTY : rewards;
            unlockQuestIds = unlockQuestIds == null ? List.of() : List.copyOf(unlockQuestIds);
        }
    }

    public record QuestsConfig(
            boolean enabled,
            ZoneId timezone,
            boolean defaultManualAccept,
            boolean defaultRequireWorking,
            List<String> rewardCommandsWhitelist,
            Map<String, PeriodTemplate> periods,
            List<QuestSpec> quests,
            Map<String, QuestSpec> questsById
    ) {
        public QuestsConfig {
            rewardCommandsWhitelist = rewardCommandsWhitelist == null ? List.of() : List.copyOf(rewardCommandsWhitelist);
            periods = periods == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(periods));
            quests = quests == null ? List.of() : List.copyOf(quests);
            questsById = questsById == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(questsById));
        }

        public static QuestsConfig empty() {
            Map<String, PeriodTemplate> p = new LinkedHashMap<>();
            p.put("daily", new PeriodTemplate("daily", PeriodKind.DAILY, 1, 3));
            p.put("weekly", new PeriodTemplate("weekly", PeriodKind.WEEKLY, 7, 3));
            p.put("once", new PeriodTemplate("once", PeriodKind.ONCE, 0, Integer.MAX_VALUE));
            return new QuestsConfig(true, ZoneId.of("Europe/Moscow"), false, true, List.of(), p, List.of(), Map.of());
        }
    }

    public record ParseResult(
            QuestsConfig config,
            List<String> errors
    ) {}
}

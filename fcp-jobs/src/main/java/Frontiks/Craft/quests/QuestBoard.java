package Frontiks.Craft.quests;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Чистая логика заданий: выбор из пула, периоды, проверка «взято ли задание»,
 * пошаговое прохождение цепочек (steps) и однократная выплата награды.
 */
public final class QuestBoard {

    public enum Period { DAILY, WEEKLY }

    /** Мост к выдаче награды: возвращает true, если награда реально выдана. */
    public interface PayoutSink {
        boolean pay(String questId, int rewardCoins);
    }

    /** Информация о переходе на следующий этап цепочки. */
    public record StepAdvance(String questId, int completedStepNumber, int totalSteps, String nextStepTitle) {}

    /** Универсальное событие действий игрока. */
    public record QuestEvent(
            boolean workingInMine,
            Map<String, Integer> brokenBlocks,
            Map<String, Integer> minedLoot,
            Map<String, Integer> smeltedItems,
            Map<String, Integer> craftedItems,
            int nodesCompleted,
            int coinsEarned
    ) {
        public static QuestEvent mineCompletion(boolean workingInMine,
                                                String brokenBlock,
                                                Map<String, Integer> loot,
                                                int nodesCompleted,
                                                int coinsEarned) {
            Map<String, Integer> broken = (brokenBlock == null || brokenBlock.isBlank())
                    ? Map.of()
                    : Map.of(brokenBlock.toUpperCase(), 1);
            return new QuestEvent(workingInMine, broken, loot == null ? Map.of() : loot,
                    Map.of(), Map.of(), nodesCompleted, coinsEarned);
        }
    }

    public record ApplyResult(
            List<String> completedNow,
            List<String> paidNow,
            List<String> payFailed,
            List<String> inProgress,
            List<StepAdvance> stepAdvances
    ) {
        public ApplyResult(List<String> completedNow, List<String> paidNow,
                           List<String> payFailed, List<String> inProgress) {
            this(completedNow, paidNow, payFailed, inProgress, List.of());
        }

        public static ApplyResult empty() {
            return new ApplyResult(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private QuestBoard() {}

    public static ZoneId zoneOrDefault(String zoneId, ZoneId fallback) {
        try {
            return ZoneId.of(zoneId == null || zoneId.isBlank() ? fallback.getId() : zoneId.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String periodKey(Period period, ZoneId zone, long nowMs) {
        LocalDate date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        if (period == Period.DAILY) return date.toString();
        int weekYear = date.get(IsoFields.WEEK_BASED_YEAR);
        int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format("%04d-W%02d", weekYear, week);
    }

    public static long periodResetsAt(Period period, ZoneId zone, long nowMs) {
        java.time.ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        if (period == Period.DAILY) {
            return now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        }
        LocalDate date = now.toLocalDate();
        LocalDate monday = date.with(java.time.DayOfWeek.MONDAY);
        return monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    public static String formatInstant(long epochMs, ZoneId zone) {
        return Instant.ofEpochMilli(epochMs).atZone(zone)
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }

    /** Случайный выбор count заданий из пула без повторов. Шахтёрские без requireAccept сразу активны. */
    public static List<QuestAssignment> assign(List<QuestDef> pool, int count, Random rng) {
        if (pool == null || pool.isEmpty() || count <= 0) return Collections.emptyList();
        List<QuestDef> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, rng);
        return copy.stream().limit(Math.min(count, copy.size()))
                .map(d -> new QuestAssignment(d.id, !d.requireAccept))
                .collect(Collectors.toList());
    }

    /** Обратная совместимость для существующих тестов (считаем, что игрок на смене в шахте). */
    public static ApplyResult applyProgress(List<QuestAssignment> active, Map<String, QuestDef> defs,
                                            Map<String, Integer> lootCounts, int nodesCompleted,
                                            PayoutSink sink) {
        QuestEvent ev = new QuestEvent(true, Map.of(), lootCounts == null ? Map.of() : lootCounts,
                Map.of(), Map.of(), nodesCompleted, 0);
        return applyEvent(active, defs, ev, sink);
    }

    /**
     * Основной обработчик прогресса:
     * 1) Если задание требует взятия (requireAccept=true) и игрок его НЕ взял (accepted=false) — пропускается!
     * 2) Проверяется ТОЛЬКО текущий шаг цепочки (stepIndex).
     * 3) Если текущий шаг требует работы в шахте (requireWorking=true), а игрок не на смене — пропускается!
     * 4) При достижении цели текущего шага — переход к следующему шагу (или завершение всего задания на последнем шаге).
     */
    public static ApplyResult applyEvent(List<QuestAssignment> active,
                                         Map<String, QuestDef> defs,
                                         QuestEvent event,
                                         PayoutSink sink) {
        List<String> completedNow = new ArrayList<>();
        List<String> paidNow = new ArrayList<>();
        List<String> payFailed = new ArrayList<>();
        List<String> inProgress = new ArrayList<>();
        List<StepAdvance> stepAdvances = new ArrayList<>();
        if (active == null || defs == null || event == null) {
            return new ApplyResult(completedNow, paidNow, payFailed, inProgress, stepAdvances);
        }

        for (QuestAssignment a : active) {
            QuestDef def = defs.get(a.id);
            if (def == null) continue;

            if (a.completed) {
                if (!a.paid) tryPay(a, def, sink, paidNow, payFailed);
                continue;
            }

            // Если задание нужно сначала «взять», а игрок его не взял — прогресс НЕ идёт!
            if (def.requireAccept && !a.accepted) {
                continue;
            }

            int idx = Math.max(0, Math.min(a.stepIndex, def.steps.size() - 1));
            a.stepIndex = idx;
            QuestStep step = def.steps.get(idx);

            if (step.requireWorking && !event.workingInMine()) {
                continue;
            }

            int gain = computeGain(step, event);
            if (gain <= 0) continue;

            long nextProg = (long) a.progress + gain;
            a.progress = (int) Math.min(step.amount, nextProg);

            if (a.progress >= step.amount) {
                if (idx + 1 < def.steps.size()) {
                    // Переход к следующему этапу цепочки!
                    a.stepIndex = idx + 1;
                    a.progress = 0;
                    QuestStep nextStep = def.steps.get(a.stepIndex);
                    stepAdvances.add(new StepAdvance(def.id, idx + 1, def.steps.size(), nextStep.title));
                } else {
                    // Последний этап цепочки выполнен -> задание завершено!
                    a.completed = true;
                    completedNow.add(def.id);
                    tryPay(a, def, sink, paidNow, payFailed);
                }
            } else {
                inProgress.add(def.id + ": " + a.progress + "/" + step.amount);
            }
        }
        return new ApplyResult(completedNow, paidNow, payFailed, inProgress, stepAdvances);
    }

    private static int computeGain(QuestStep step, QuestEvent event) {
        return switch (step.type) {
            case BREAK_BLOCK -> countForMaterial(event.brokenBlocks(), step.material);
            case LOOT_AMOUNT -> countForMaterial(event.minedLoot(), step.material);
            case SMELT_ITEM -> countForMaterial(event.smeltedItems(), step.material);
            case CRAFT_ITEM -> countForMaterial(event.craftedItems(), step.material);
            case OBTAIN_ITEM -> countForMaterial(event.minedLoot(), step.material)
                    + countForMaterial(event.smeltedItems(), step.material)
                    + countForMaterial(event.craftedItems(), step.material);
            case NODES_COMPLETED -> Math.max(0, event.nodesCompleted());
            case EARN_COINS -> Math.max(0, event.coinsEarned());
        };
    }

    private static int countForMaterial(Map<String, Integer> counts, String targetMaterial) {
        if (counts == null || counts.isEmpty() || targetMaterial == null) return 0;
        if ("ANY".equalsIgnoreCase(targetMaterial) || "*".equals(targetMaterial)) {
            int total = 0;
            for (Integer v : counts.values()) {
                if (v != null && v > 0) total += v;
            }
            return total;
        }
        return Math.max(0, counts.getOrDefault(targetMaterial.toUpperCase(), 0));
    }

    public static ApplyResult retryPendingPayouts(List<QuestAssignment> active, Map<String, QuestDef> defs,
                                                  PayoutSink sink) {
        List<String> paidNow = new ArrayList<>();
        List<String> payFailed = new ArrayList<>();
        if (active != null) {
            for (QuestAssignment a : active) {
                if (a.completed && !a.paid) {
                    QuestDef def = defs.get(a.id);
                    if (def != null) tryPay(a, def, sink, paidNow, payFailed);
                }
            }
        }
        return new ApplyResult(List.of(), paidNow, payFailed, List.of(), List.of());
    }

    private static void tryPay(QuestAssignment a, QuestDef def, PayoutSink sink,
                               List<String> paidNow, List<String> payFailed) {
        boolean ok;
        try {
            ok = sink.pay(def.id, def.reward);
        } catch (Throwable t) {
            ok = false;
        }
        if (ok) {
            a.paid = true;
            paidNow.add(def.id);
        } else {
            a.paid = false;
            payFailed.add(def.id);
        }
    }
}

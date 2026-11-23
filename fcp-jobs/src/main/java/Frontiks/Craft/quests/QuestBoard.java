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
 * Чистая логика заданий: выбор из пула, ключи периодов, начисление прогресса и выплата.
 * Выплата происходит ровно один раз на задание: paid ставится только после успешного
 * возврата true от PayoutSink (или остаётся false, и выплата будет повторена позже).
 */
public final class QuestBoard {

    public enum Period { DAILY, WEEKLY }

    /** Мост к экономике: возвращает true, если награда реально начислена. */
    public interface PayoutSink {
        boolean pay(String questId, int rewardCoins);
    }

    public record ApplyResult(List<String> completedNow, List<String> paidNow,
                              List<String> payFailed, List<String> inProgress) {
        public static ApplyResult empty() { return new ApplyResult(List.of(), List.of(), List.of(), List.of()); }
    }

    private QuestBoard() {}

    /** Безопасный парсинг timezone из конфига с fallback. */
    public static ZoneId zoneOrDefault(String zoneId, ZoneId fallback) {
        try {
            return ZoneId.of(zoneId == null || zoneId.isBlank() ? fallback.getId() : zoneId.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Ключ периода: daily -> yyyy-MM-dd, weekly -> YYYY-Www (ISO-8601, неделя с понедельника). */
    public static String periodKey(Period period, ZoneId zone, long nowMs) {
        LocalDate date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        if (period == Period.DAILY) return date.toString();
        int weekYear = date.get(IsoFields.WEEK_BASED_YEAR);
        int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format("%04d-W%02d", weekYear, week);
    }

    /** Момент сброса текущего периода (для отображения дедлайна), epoch ms. */
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

    /** Случайный выбор count заданий из пула без повторов. Пул должен быть уже провалидирован. */
    public static List<QuestAssignment> assign(List<QuestDef> pool, int count, Random rng) {
        if (pool == null || pool.isEmpty() || count <= 0) return Collections.emptyList();
        List<QuestDef> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, rng);
        return copy.stream().limit(Math.min(count, copy.size()))
                .map(d -> new QuestAssignment(d.id))
                .collect(Collectors.toList());
    }

    /** Совместимая перегрузка: лут засчитывается и в LOOT_AMOUNT, и в OBTAIN_ITEM. */
    public static ApplyResult applyProgress(List<QuestAssignment> active, Map<String, QuestDef> defs,
                                            Map<String, Integer> lootCounts, int nodesCompleted,
                                            PayoutSink sink) {
        return applyProgress(active, defs, Map.of(), lootCounts, lootCounts, nodesCompleted, sink);
    }

    /**
     * Полное начисление прогресса по всем 4 типам задач:
     * - BREAK_BLOCK (сломать блок/стадию)
     * - LOOT_AMOUNT (добыть ресурс с узла)
     * - OBTAIN_ITEM (получить предмет с узла или из печи)
     * - NODES_COMPLETED (полностью завершить узел)
     */
    public static ApplyResult applyProgress(List<QuestAssignment> active, Map<String, QuestDef> defs,
                                            Map<String, Integer> brokenBlocks,
                                            Map<String, Integer> lootCounts,
                                            Map<String, Integer> obtainedItems,
                                            int nodesCompleted,
                                            PayoutSink sink) {
        List<String> completedNow = new ArrayList<>();
        List<String> paidNow = new ArrayList<>();
        List<String> payFailed = new ArrayList<>();
        List<String> inProgress = new ArrayList<>();
        if (active == null) return new ApplyResult(completedNow, paidNow, payFailed, inProgress);

        for (QuestAssignment a : active) {
            QuestDef def = defs.get(a.id);
            if (def == null) continue; // задание убрали из конфига — молча пропускаем

            if (a.completed) {
                if (!a.paid) tryPay(a, def, sink, paidNow, payFailed);
                continue;
            }

            int gain = switch (def.type) {
                case BREAK_BLOCK -> countForMaterial(brokenBlocks, def.material);
                case LOOT_AMOUNT -> countForMaterial(lootCounts, def.material);
                case OBTAIN_ITEM -> countForMaterial(obtainedItems, def.material);
                case NODES_COMPLETED -> Math.max(0, nodesCompleted);
            };
            if (gain <= 0) continue;

            long sum = (long) a.progress + gain;
            a.progress = (int) Math.min(def.amount, sum);
            if (a.progress >= def.amount && !a.completed) {
                a.completed = true;
                completedNow.add(def.id);
                tryPay(a, def, sink, paidNow, payFailed);
            } else {
                inProgress.add(def.id + ": " + a.progress + "/" + def.amount);
            }
        }
        return new ApplyResult(completedNow, paidNow, payFailed, inProgress);
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

    /** Ручной повторный запуск выплат за задания, которые уже выполнены, но не оплачены. */
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
        return new ApplyResult(List.of(), paidNow, payFailed, List.of());
    }

    private static void tryPay(QuestAssignment a, QuestDef def, PayoutSink sink,
                               List<String> paidNow, List<String> payFailed) {
        boolean ok = false;
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

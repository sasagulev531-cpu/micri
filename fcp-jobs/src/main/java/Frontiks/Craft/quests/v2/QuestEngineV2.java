package Frontiks.Craft.quests.v2;

import Frontiks.Craft.actions.ActionKindRegistry;
import Frontiks.Craft.actions.JobAction;
import Frontiks.Craft.quests.v2.PlayerQuestProfile.CategoryBucket;
import Frontiks.Craft.quests.v2.PlayerQuestProfile.QuestProgressState;
import Frontiks.Craft.quests.v2.QuestV2Models.*;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Чистая машина состояний заданий v2 (R4, §5.4–§5.6) — без единого импорта Bukkit.
 * Состояния: LOCKED -> AVAILABLE -> IN_PROGRESS(step i) -> COMPLETED(paid?).
 */
public final class QuestEngineV2 {

    /** Лимит автоматических фоновых повторов выплаты: 30 минут (R4). */
    public static final long MAX_AUTO_RETRY_MS = 30L * 60L * 1000L;
    private static final long DAY_MS = 86_400_000L;

    public enum LifecycleState {
        LOCKED,
        AVAILABLE,
        IN_PROGRESS,
        COMPLETED
    }

    @FunctionalInterface
    public interface PayoutSink {
        boolean pay(QuestSpec quest);
    }

    public record PlayerContext(
            String currentJobId,
            Predicate<String> permissionChecker
    ) {
        public static final PlayerContext DEFAULT_MINER = new PlayerContext("MINER", perm -> true);

        public boolean hasPermission(String node) {
            if (node == null || node.isBlank()) return true;
            return permissionChecker == null || permissionChecker.test(node);
        }
    }

    public record StepAdvanceInfo(
            String questId,
            int completedStepNumber,
            int totalSteps,
            String nextStepName
    ) {}

    public record EngineResult(
            List<String> completedNow,
            List<String> paidNow,
            List<String> payFailed,
            List<StepAdvanceInfo> stepAdvances,
            List<String> unlockedNow,
            boolean dirty
    ) {
        public static EngineResult empty() {
            return new EngineResult(List.of(), List.of(), List.of(), List.of(), List.of(), false);
        }
    }

    public record QuestView(
            QuestSpec spec,
            PeriodTemplate period,
            QuestProgressState state,
            LifecycleState lifecycle,
            List<String> lockReasons
    ) {}

    private QuestEngineV2() {}

    // ------------------------------------------------------------------ периоды

    public static String computePeriodKey(PeriodTemplate tpl, ZoneId zone, long nowMs, long anchorMs) {
        return switch (tpl.kind()) {
            case DAILY -> Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString();
            case WEEKLY -> {
                LocalDate d = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
                int wy = d.get(IsoFields.WEEK_BASED_YEAR);
                int w = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                yield String.format("%04d-W%02d", wy, w);
            }
            case INTERVAL -> {
                long effAnchor = anchorMs > 0L ? anchorMs : nowMs;
                long spanMs = Math.max(1L, tpl.days()) * DAY_MS;
                long idx = Math.max(0L, (nowMs - effAnchor) / spanMs);
                yield "INT-" + tpl.days() + "D-" + idx;
            }
            case ONCE -> "ONCE";
        };
    }

    public static long nextResetEpochMs(PeriodTemplate tpl, ZoneId zone, long nowMs, long anchorMs) {
        return switch (tpl.kind()) {
            case DAILY -> {
                ZonedDateTime zdt = Instant.ofEpochMilli(nowMs).atZone(zone);
                yield zdt.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            }
            case WEEKLY -> {
                ZonedDateTime zdt = Instant.ofEpochMilli(nowMs).atZone(zone);
                LocalDate monday = zdt.toLocalDate().with(DayOfWeek.MONDAY);
                if (!monday.isAfter(zdt.toLocalDate())) {
                    monday = monday.plusWeeks(1);
                }
                yield monday.atStartOfDay(zone).toInstant().toEpochMilli();
            }
            case INTERVAL -> {
                long effAnchor = anchorMs > 0L ? anchorMs : nowMs;
                long spanMs = Math.max(1L, tpl.days()) * DAY_MS;
                long idx = Math.max(0L, (nowMs - effAnchor) / spanMs);
                yield effAnchor + (idx + 1L) * spanMs;
            }
            case ONCE -> 0L;
        };
    }

    public static String formatReset(long epochMs, ZoneId zone) {
        if (epochMs <= 0L) return "Разовое (без сброса)";
        return Instant.ofEpochMilli(epochMs).atZone(zone)
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }

    // ------------------------------------------------------------------ синхронизация пулов (ленивая)

    public static boolean ensureSynced(PlayerQuestProfile profile,
                                       QuestsConfig config,
                                       long nowMs,
                                       Random rng) {
        if (!config.enabled()) return false;
        boolean changed = false;
        Random random = rng != null ? rng : new Random();
        Set<String> unlockTargets = collectAllUnlockTargets(config);

        for (PeriodTemplate tpl : config.periods().values()) {
            List<QuestSpec> pool = questsInCategory(config, tpl.name());
            CategoryBucket bucket = profile.bucket(tpl.name());

            if (tpl.kind() == PeriodKind.INTERVAL && bucket.intervalAnchorMs <= 0L && !pool.isEmpty()) {
                bucket.intervalAnchorMs = nowMs;
                changed = true;
            }

            if (tpl.kind() == PeriodKind.ONCE) {
                if (!"ONCE".equals(bucket.periodKey)) {
                    bucket.periodKey = "ONCE";
                    changed = true;
                }
                // Удаляем несуществующие или уже полностью завершённые и оплаченные once-задания
                if (bucket.quests.removeIf(q -> !config.questsById().containsKey(q.id)
                        || (q.completed && q.paid && profile.completedAndPaidIds.contains(q.id)))) {
                    changed = true;
                }
                Set<String> present = new HashSet<>();
                for (QuestProgressState q : bucket.quests) present.add(q.id);
                for (QuestSpec spec : pool) {
                    if (profile.completedAndPaidIds.contains(spec.id())) continue;
                    if (!present.contains(spec.id())) {
                        QuestProgressState st = new QuestProgressState(spec.id(), !spec.manualAccept());
                        st.ensureBufferSize(spec.steps().get(0));
                        bucket.quests.add(st);
                        changed = true;
                    }
                }
                continue;
            }

            String expectedKey = computePeriodKey(tpl, config.timezone(), nowMs, bucket.intervalAnchorMs);
            if (!expectedKey.equals(bucket.periodKey)) {
                // Сохраняем невыплаченные выполненные задания, чтобы игрок не потерял награду на границе суток!
                List<QuestProgressState> unpaidCarry = new ArrayList<>();
                for (QuestProgressState old : bucket.quests) {
                    if (old.completed && !old.paid && config.questsById().containsKey(old.id)) {
                        unpaidCarry.add(old);
                    }
                }
                bucket.periodKey = expectedKey;
                bucket.quests.clear();
                bucket.quests.addAll(unpaidCarry);

                Set<String> skipIds = new HashSet<>();
                for (QuestProgressState u : unpaidCarry) skipIds.add(u.id);

                List<QuestSpec> candidates = new ArrayList<>();
                for (QuestSpec s : pool) {
                    if (!skipIds.contains(s.id())) candidates.add(s);
                }
                Collections.shuffle(candidates, random);
                int pick = Math.min(Math.max(0, tpl.count() - unpaidCarry.size()), candidates.size());
                for (int i = 0; i < pick; i++) {
                    QuestSpec chosen = candidates.get(i);
                    QuestProgressState st = new QuestProgressState(chosen.id(), !chosen.manualAccept());
                    st.ensureBufferSize(chosen.steps().get(0));
                    bucket.quests.add(st);
                }
                changed = true;
            } else {
                // Синхронизация после /questsadmin reload: удаляем исчезнувшие и добираем недостающие
                if (bucket.quests.removeIf(q -> !config.questsById().containsKey(q.id)
                        || !tpl.name().equals(config.questsById().get(q.id).category()))) {
                    changed = true;
                }
                int want = Math.min(tpl.count(), pool.size());
                if (bucket.quests.size() < want) {
                    Set<String> existing = new HashSet<>();
                    for (QuestProgressState q : bucket.quests) existing.add(q.id);
                    List<QuestSpec> candidates = new ArrayList<>();
                    for (QuestSpec s : pool) {
                        if (!existing.contains(s.id())) candidates.add(s);
                    }
                    Collections.shuffle(candidates, random);
                    int need = Math.min(want - bucket.quests.size(), candidates.size());
                    for (int i = 0; i < need; i++) {
                        QuestSpec chosen = candidates.get(i);
                        QuestProgressState st = new QuestProgressState(chosen.id(), !chosen.manualAccept());
                        st.ensureBufferSize(chosen.steps().get(0));
                        bucket.quests.add(st);
                    }
                    changed = true;
                }
            }

            // Выравниваем размер буфера прогресса под текущий шаг
            for (QuestProgressState st : bucket.quests) {
                QuestSpec spec = config.questsById().get(st.id);
                if (spec != null && !spec.steps().isEmpty()) {
                    if (!spec.manualAccept() && !st.accepted) {
                        st.accepted = true;
                        changed = true;
                    }
                    int idx = Math.max(0, Math.min(st.stepIndex, spec.steps().size() - 1));
                    st.stepIndex = idx;
                    st.ensureBufferSize(spec.steps().get(idx));
                }
            }
        }

        // Ленивая авто-разблокировка, если предыдущее задание уже было выполнено и оплачено
        for (QuestSpec spec : config.quests()) {
            if (profile.completedAndPaidIds.contains(spec.id())) {
                for (String ul : spec.unlockQuestIds()) {
                    if (profile.unlockedIds.add(ul)) {
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ вычисление состояния и пререквизитов

    public static LifecycleState evaluateLifecycle(QuestSpec spec,
                                                   QuestProgressState state,
                                                   PlayerQuestProfile profile,
                                                   QuestsConfig config,
                                                   PlayerContext ctx,
                                                   Set<String> allUnlockTargets,
                                                   List<String> outLockReasons) {
        if (state.completed) {
            return LifecycleState.COMPLETED;
        }

        RequiresSpec req = spec.requires();
        boolean locked = false;

        // 1) Проверка on-complete.unlock для once-заданий
        PeriodTemplate tpl = config.periods().get(spec.category());
        if (tpl != null && tpl.kind() == PeriodKind.ONCE
                && allUnlockTargets.contains(spec.id())
                && !profile.unlockedIds.contains(spec.id())) {
            locked = true;
            if (outLockReasons != null) {
                outLockReasons.add("Разблокируется по цепочке заданий");
            }
        }

        // 2) Проверка requires.quest
        if (req.questId() != null && !req.questId().isBlank()
                && !profile.isCompletedAndPaid(req.questId())) {
            locked = true;
            if (outLockReasons != null) {
                QuestSpec pre = config.questsById().get(req.questId());
                String preTitle = pre != null ? pre.title() : req.questId();
                outLockReasons.add("Требуется задание: " + preTitle);
            }
        }

        // 3) Проверка requires.job
        if (req.jobId() != null && !req.jobId().isBlank()) {
            String curJob = ctx != null ? ctx.currentJobId() : null;
            if (curJob == null || !req.jobId().equalsIgnoreCase(curJob)) {
                locked = true;
                if (outLockReasons != null) {
                    outLockReasons.add("Требуется работа: " + req.jobId());
                }
            }
        }

        // 4) Проверка requires.permission
        if (req.permission() != null && !req.permission().isBlank()) {
            if (ctx != null && !ctx.hasPermission(req.permission())) {
                locked = true;
                if (outLockReasons != null) {
                    outLockReasons.add("Требуется право: " + req.permission());
                }
            }
        }

        if (locked) {
            return LifecycleState.LOCKED;
        }
        if (spec.manualAccept() && !state.accepted) {
            return LifecycleState.AVAILABLE;
        }
        return LifecycleState.IN_PROGRESS;
    }

    public static List<QuestView> buildViews(PlayerQuestProfile profile,
                                             QuestsConfig config,
                                             PlayerContext ctx,
                                             long nowMs,
                                             Random rng) {
        ensureSynced(profile, config, nowMs, rng);
        Set<String> unlockTargets = collectAllUnlockTargets(config);
        List<QuestView> views = new ArrayList<>();

        for (PeriodTemplate tpl : config.periods().values()) {
            CategoryBucket bucket = profile.bucket(tpl.name());
            for (QuestProgressState st : bucket.quests) {
                QuestSpec spec = config.questsById().get(st.id);
                if (spec == null) continue;
                if (tpl.kind() == PeriodKind.ONCE && st.completed && st.paid) {
                    continue; // Разовое выполненное и оплаченное скрывается навсегда (§5.5)
                }
                List<String> reasons = new ArrayList<>();
                LifecycleState lc = evaluateLifecycle(spec, st, profile, config, ctx, unlockTargets, reasons);
                views.add(new QuestView(spec, tpl, st, lc, reasons));
            }
        }
        return views;
    }

    // ------------------------------------------------------------------ взятие задания (accept: manual)

    public static boolean acceptQuest(PlayerQuestProfile profile,
                                      QuestsConfig config,
                                      PlayerContext ctx,
                                      String questId,
                                      long nowMs,
                                      Random rng) {
        if (!config.enabled() || questId == null || questId.isBlank()) return false;
        ensureSynced(profile, config, nowMs, rng);
        Set<String> unlockTargets = collectAllUnlockTargets(config);

        for (CategoryBucket bucket : profile.categories.values()) {
            for (QuestProgressState st : bucket.quests) {
                if (!questId.equals(st.id)) continue;
                QuestSpec spec = config.questsById().get(st.id);
                if (spec == null || st.completed || st.accepted) return false;
                LifecycleState lc = evaluateLifecycle(spec, st, profile, config, ctx, unlockTargets, null);
                if (lc != LifecycleState.AVAILABLE) return false;
                st.accepted = true;
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ обработка событий действий (R2 -> R4)

    public static EngineResult applyAction(PlayerQuestProfile profile,
                                           QuestsConfig config,
                                           PlayerContext ctx,
                                           JobAction action,
                                           long nowMs,
                                           Random rng,
                                           PayoutSink payoutSink) {
        if (!config.enabled() || action == null || action.amount() <= 0) {
            return EngineResult.empty();
        }
        boolean dirty = ensureSynced(profile, config, nowMs, rng);
        Set<String> unlockTargets = collectAllUnlockTargets(config);

        List<String> completedNow = new ArrayList<>();
        List<String> paidNow = new ArrayList<>();
        List<String> payFailed = new ArrayList<>();
        List<StepAdvanceInfo> stepAdvances = new ArrayList<>();
        Set<String> unlockedNow = new LinkedHashSet<>();

        for (PeriodTemplate tpl : config.periods().values()) {
            CategoryBucket bucket = profile.bucket(tpl.name());
            List<QuestProgressState> snapshot = new ArrayList<>(bucket.quests);

            for (QuestProgressState st : snapshot) {
                QuestSpec spec = config.questsById().get(st.id);
                if (spec == null || st.completed) continue;

                LifecycleState lc = evaluateLifecycle(spec, st, profile, config, ctx, unlockTargets, null);
                if (lc != LifecycleState.IN_PROGRESS) {
                    continue; // LOCKED или AVAILABLE (не взято!) — прогресс НЕ идёт!
                }

                int idx = Math.max(0, Math.min(st.stepIndex, spec.steps().size() - 1));
                st.stepIndex = idx;
                StepSpec step = spec.steps().get(idx);
                st.ensureBufferSize(step);

                boolean progressedThisStep = false;

                // 1) Условие времени (time: { as-job, minutes })
                if (step.time() != null && ActionKindRegistry.TIME_AS_JOB.equals(action.eventId())) {
                    if (action.workingInMine()) {
                        String reqAsJob = step.time().asJobId();
                        if (reqAsJob == null || reqAsJob.isBlank() || reqAsJob.equalsIgnoreCase(action.jobId())) {
                            int before = st.timeMinutes;
                            st.timeMinutes = (int) Math.min(step.time().minutes(), (long) st.timeMinutes + action.amount());
                            if (st.timeMinutes > before) progressedThisStep = true;
                        }
                    }
                }

                // 2) Группа условий all / any
                if (!ActionKindRegistry.TIME_AS_JOB.equals(action.eventId())) {
                    if (step.groupMode() == GroupMode.ALL) {
                        List<ConditionSpec> conds = step.conditions();
                        for (int ci = 0; ci < conds.size(); ci++) {
                            ConditionSpec cond = conds.get(ci);
                            int gain = matchCondition(cond, action);
                            if (gain > 0) {
                                int before = st.conditionProgress[ci];
                                st.conditionProgress[ci] = (int) Math.min(cond.amount(), (long) before + gain);
                                if (st.conditionProgress[ci] > before) progressedThisStep = true;
                            }
                        }
                    } else if (step.groupMode() == GroupMode.ANY) {
                        int gainSum = 0;
                        for (ConditionSpec cond : step.conditions()) {
                            int g = matchCondition(cond, action);
                            if (g > 0) {
                                // Одно событие даёт максимум свой размер, без двойного счёта одинаковых условий в any
                                gainSum = Math.max(gainSum, g);
                            }
                        }
                        if (gainSum > 0) {
                            int before = st.conditionProgress[0];
                            st.conditionProgress[0] = (int) Math.min(step.anyAmount(), (long) before + gainSum);
                            if (st.conditionProgress[0] > before) progressedThisStep = true;
                        }
                    }
                }

                if (!progressedThisStep) continue;
                dirty = true;

                if (isStepComplete(step, st)) {
                    if (idx + 1 < spec.steps().size()) {
                        // Переход к следующему шагу БЕЗ переноса остатка (§5.4 / R4)
                        st.stepIndex = idx + 1;
                        StepSpec nextStep = spec.steps().get(st.stepIndex);
                        st.conditionProgress = new int[0];
                        st.ensureBufferSize(nextStep);
                        st.timeMinutes = 0;
                        stepAdvances.add(new StepAdvanceInfo(spec.id(), idx + 1, spec.steps().size(), nextStep.name()));
                    } else {
                        st.completed = true;
                        completedNow.add(spec.id());
                        attemptSinglePayout(profile, config, tpl, spec, st, nowMs, payoutSink, paidNow, payFailed, unlockedNow);
                    }
                }
            }

            // Удаляем оплаченные once-задания из активного списка
            if (tpl.kind() == PeriodKind.ONCE) {
                bucket.quests.removeIf(q -> q.completed && q.paid && profile.completedAndPaidIds.contains(q.id));
            }
        }

        return new EngineResult(completedNow, paidNow, payFailed, stepAdvances, new ArrayList<>(unlockedNow), dirty);
    }

    // ------------------------------------------------------------------ повтор выплат (ручной и фоновый 30с/30мин)

    public static EngineResult retryPendingPayouts(PlayerQuestProfile profile,
                                                   QuestsConfig config,
                                                   long nowMs,
                                                   boolean manualClick,
                                                   PayoutSink payoutSink,
                                                   Consumer<String> adminAlertLogger) {
        List<String> paidNow = new ArrayList<>();
        List<String> payFailed = new ArrayList<>();
        Set<String> unlockedNow = new LinkedHashSet<>();
        boolean dirty = false;

        for (Map.Entry<String, CategoryBucket> entry : profile.categories.entrySet()) {
            PeriodTemplate tpl = config.periods().get(entry.getKey());
            CategoryBucket bucket = entry.getValue();

            for (QuestProgressState st : bucket.quests) {
                if (!st.completed || st.paid) continue;
                QuestSpec spec = config.questsById().get(st.id);
                if (spec == null) continue;

                if (!manualClick) {
                    if (st.autoRetryExhausted) continue;
                    if (st.firstPayFailedAtMs > 0L && (nowMs - st.firstPayFailedAtMs) > MAX_AUTO_RETRY_MS) {
                        st.autoRetryExhausted = true;
                        dirty = true;
                        if (adminAlertLogger != null) {
                            adminAlertLogger.accept("Превышен 30-минутный лимит авто-повторов выплаты задания '"
                                    + spec.id() + "'. Требуется проверка экономики.");
                        }
                        continue;
                    }
                }

                dirty = true;
                attemptSinglePayout(profile, config, tpl, spec, st, nowMs, payoutSink, paidNow, payFailed, unlockedNow);
            }

            if (tpl != null && tpl.kind() == PeriodKind.ONCE) {
                bucket.quests.removeIf(q -> q.completed && q.paid && profile.completedAndPaidIds.contains(q.id));
            }
        }

        return new EngineResult(List.of(), paidNow, payFailed, List.of(), new ArrayList<>(unlockedNow), dirty);
    }

    // ------------------------------------------------------------------ приватная логика

    private static void attemptSinglePayout(PlayerQuestProfile profile,
                                            QuestsConfig config,
                                            PeriodTemplate tpl,
                                            QuestSpec spec,
                                            QuestProgressState st,
                                            long nowMs,
                                            PayoutSink sink,
                                            List<String> paidNow,
                                            List<String> payFailed,
                                            Set<String> unlockedNow) {
        if (st.paid) return; // Жёсткая защита: флаг paid никогда не обнуляется и не выплачивается дважды!
        boolean ok;
        try {
            ok = sink != null && sink.pay(spec);
        } catch (Throwable t) {
            ok = false;
        }
        if (ok) {
            st.paid = true;
            st.firstPayFailedAtMs = 0L;
            st.autoRetryExhausted = false;
            paidNow.add(spec.id());
            profile.completedAndPaidIds.add(spec.id());

            // Разблокировка зависимых once-заданий ТОЛЬКО после выполнения И выплаты (R9.7)
            for (String unlockId : spec.unlockQuestIds()) {
                QuestSpec target = config.questsById().get(unlockId);
                if (target != null) {
                    PeriodTemplate targetTpl = config.periods().get(target.category());
                    if (targetTpl != null && targetTpl.kind() == PeriodKind.ONCE) {
                        if (profile.unlockedIds.add(unlockId)) {
                            unlockedNow.add(unlockId);
                        }
                    }
                }
            }
        } else {
            st.paid = false;
            if (st.firstPayFailedAtMs <= 0L) {
                st.firstPayFailedAtMs = nowMs;
            }
            payFailed.add(spec.id());
        }
    }

    private static boolean isStepComplete(StepSpec step, QuestProgressState st) {
        if (step.time() != null && st.timeMinutes < step.time().minutes()) {
            return false;
        }
        return switch (step.groupMode()) {
            case NONE -> true;
            case ANY -> st.conditionProgress.length > 0 && st.conditionProgress[0] >= step.anyAmount();
            case ALL -> {
                List<ConditionSpec> conds = step.conditions();
                for (int i = 0; i < conds.size(); i++) {
                    if (i >= st.conditionProgress.length || st.conditionProgress[i] < conds.get(i).amount()) {
                        yield false;
                    }
                }
                yield true;
            }
        };
    }

    private static int matchCondition(ConditionSpec cond, JobAction action) {
        if (cond.requireWorking() && !action.workingInMine()) {
            return 0;
        }
        if (cond.jobId() != null && !cond.jobId().isBlank()) {
            if (action.jobId() == null || !cond.jobId().equalsIgnoreCase(action.jobId())) {
                return 0;
            }
        }
        if (cond.minDepth() != null) {
            if (action.blockY() == null || action.blockY() < cond.minDepth()) {
                return 0;
            }
        }
        if (cond.maxDepth() != null) {
            if (action.blockY() == null || action.blockY() > cond.maxDepth()) {
                return 0;
            }
        }
        return cond.kind().match(action, cond.material());
    }

    private static List<QuestSpec> questsInCategory(QuestsConfig config, String category) {
        List<QuestSpec> out = new ArrayList<>();
        for (QuestSpec q : config.quests()) {
            if (category.equals(q.category())) out.add(q);
        }
        return out;
    }

    public static Set<String> collectAllUnlockTargets(QuestsConfig config) {
        Set<String> out = new HashSet<>();
        for (QuestSpec q : config.quests()) {
            out.addAll(q.unlockQuestIds());
        }
        return out;
    }
}

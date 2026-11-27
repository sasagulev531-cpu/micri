package Frontiks.Craft.quests.v2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Персистентное состояние заданий одного игрока (совместимо со старыми записями mining-quests.yml).
 */
public final class PlayerQuestProfile {

    public static final class QuestProgressState {
        public String id = "";
        public boolean accepted = false;
        public int stepIndex = 0;
        public int[] conditionProgress = new int[0];
        public int timeMinutes = 0;
        public boolean completed = false;
        public boolean paid = false;
        public long firstPayFailedAtMs = 0L;
        public boolean autoRetryExhausted = false;

        public QuestProgressState() {}

        public QuestProgressState(String id, boolean accepted) {
            this.id = id;
            this.accepted = accepted;
        }

        public void ensureBufferSize(QuestV2Models.StepSpec step) {
            int needed = switch (step.groupMode()) {
                case ALL -> step.conditions().size();
                case ANY -> 1;
                case NONE -> 0;
            };
            if (conditionProgress == null || conditionProgress.length != needed) {
                int[] next = new int[needed];
                if (conditionProgress != null) {
                    System.arraycopy(conditionProgress, 0, next, 0, Math.min(conditionProgress.length, needed));
                }
                conditionProgress = next;
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("accepted", accepted);
            m.put("step", stepIndex);
            List<Integer> cp = new ArrayList<>(conditionProgress.length);
            for (int v : conditionProgress) cp.add(v);
            m.put("cond-progress", cp);
            if (conditionProgress.length > 0) {
                m.put("progress", conditionProgress[0]);
            } else {
                m.put("progress", 0);
            }
            m.put("time-minutes", timeMinutes);
            m.put("completed", completed);
            m.put("paid", paid);
            if (firstPayFailedAtMs > 0L) {
                m.put("first-pay-failed-ms", firstPayFailedAtMs);
            }
            if (autoRetryExhausted) {
                m.put("auto-retry-exhausted", true);
            }
            return m;
        }

        public static QuestProgressState fromMap(Map<?, ?> raw) {
            QuestProgressState s = new QuestProgressState();
            s.id = str(raw.get("id"));
            s.accepted = bool(raw.get("accepted"), false);
            s.stepIndex = Math.max(0, num(raw.get("step"), 0));
            Object cpObj = raw.get("cond-progress");
            if (cpObj instanceof List<?> list && !list.isEmpty()) {
                s.conditionProgress = new int[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    s.conditionProgress[i] = Math.max(0, num(list.get(i), 0));
                }
            } else if (raw.containsKey("progress")) {
                // Обратная совместимость со старыми записями mining-quests.yml v1
                int legacyProg = Math.max(0, num(raw.get("progress"), 0));
                s.conditionProgress = new int[]{legacyProg};
            }
            s.timeMinutes = Math.max(0, num(raw.get("time-minutes"), 0));
            s.completed = bool(raw.get("completed"), false);
            s.paid = bool(raw.get("paid"), false);
            s.firstPayFailedAtMs = Math.max(0L, longNum(raw.get("first-pay-failed-ms"), 0L));
            s.autoRetryExhausted = bool(raw.get("auto-retry-exhausted"), false);
            return s;
        }
    }

    public static final class CategoryBucket {
        public String periodKey = "";
        public long intervalAnchorMs = 0L;
        public final List<QuestProgressState> quests = new ArrayList<>();
    }

    public final Map<String, CategoryBucket> categories = new LinkedHashMap<>();
    /** Все когда-либо полностью выполненные и оплаченные задания (для requires.quest и скрытия once). */
    public final Set<String> completedAndPaidIds = new LinkedHashSet<>();
    /** Явно разблокированные задания (через on-complete.unlock). */
    public final Set<String> unlockedIds = new LinkedHashSet<>();

    public CategoryBucket bucket(String category) {
        return categories.computeIfAbsent(category, k -> new CategoryBucket());
    }

    public boolean isCompletedAndPaid(String questId) {
        if (questId == null || questId.isBlank()) return true;
        if (completedAndPaidIds.contains(questId)) return true;
        for (CategoryBucket b : categories.values()) {
            for (QuestProgressState q : b.quests) {
                if (questId.equals(q.id) && q.completed && q.paid) {
                    return true;
                }
            }
        }
        return false;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("completed-paid", new ArrayList<>(completedAndPaidIds));
        out.put("unlocked", new ArrayList<>(unlockedIds));
        Map<String, Object> cats = new LinkedHashMap<>();
        for (Map.Entry<String, CategoryBucket> e : categories.entrySet()) {
            CategoryBucket b = e.getValue();
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("period", b.periodKey);
            if (b.intervalAnchorMs > 0L) {
                bm.put("interval-anchor-ms", b.intervalAnchorMs);
            }
            List<Map<String, Object>> qList = new ArrayList<>();
            for (QuestProgressState q : b.quests) {
                qList.add(q.toMap());
            }
            bm.put("quests", qList);
            cats.put(e.getKey(), bm);
        }
        out.put("categories", cats);
        return out;
    }

    public static PlayerQuestProfile fromMap(Map<?, ?> raw) {
        PlayerQuestProfile p = new PlayerQuestProfile();
        if (raw == null || raw.isEmpty()) return p;

        if (raw.get("completed-paid") instanceof List<?> cp) {
            for (Object o : cp) {
                String s = str(o);
                if (!s.isEmpty()) p.completedAndPaidIds.add(s);
            }
        }
        if (raw.get("unlocked") instanceof List<?> ul) {
            for (Object o : ul) {
                String s = str(o);
                if (!s.isEmpty()) p.unlockedIds.add(s);
            }
        }

        if (raw.get("categories") instanceof Map<?, ?> cats) {
            for (Map.Entry<?, ?> e : cats.entrySet()) {
                String catName = str(e.getKey());
                if (catName.isEmpty() || !(e.getValue() instanceof Map<?, ?> bm)) continue;
                readBucketInto(p.bucket(catName), bm, p.completedAndPaidIds);
            }
        } else {
            // Чтение старого формата mining-quests.yml v1 (daily / weekly прямо под UUID)
            if (raw.get("daily") instanceof Map<?, ?> dm) {
                readBucketInto(p.bucket("daily"), dm, p.completedAndPaidIds);
            }
            if (raw.get("weekly") instanceof Map<?, ?> wm) {
                readBucketInto(p.bucket("weekly"), wm, p.completedAndPaidIds);
            }
        }
        return p;
    }

    private static void readBucketInto(CategoryBucket bucket, Map<?, ?> bm, Set<String> completedPaid) {
        bucket.periodKey = str(bm.get("period"));
        bucket.intervalAnchorMs = Math.max(0L, longNum(bm.get("interval-anchor-ms"), 0L));
        if (bm.get("quests") instanceof List<?> qList) {
            for (Object elem : qList) {
                if (elem instanceof Map<?, ?> qm) {
                    QuestProgressState st = QuestProgressState.fromMap(qm);
                    if (!st.id.isEmpty()) {
                        bucket.quests.add(st);
                        if (st.completed && st.paid) {
                            completedPaid.add(st.id);
                        }
                    }
                }
            }
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static boolean bool(Object o, boolean fb) {
        if (o instanceof Boolean b) return b;
        if (o == null) return fb;
        String s = String.valueOf(o).trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return fb;
    }

    private static int num(Object o, int fb) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(str(o)); } catch (Exception e) { return fb; }
    }

    private static long longNum(Object o, long fb) {
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(str(o)); } catch (Exception e) { return fb; }
    }

    @Override
    public String toString() {
        return "PlayerQuestProfile{cats=" + categories.keySet()
                + ", completedPaid=" + Arrays.toString(completedAndPaidIds.toArray()) + "}";
    }
}

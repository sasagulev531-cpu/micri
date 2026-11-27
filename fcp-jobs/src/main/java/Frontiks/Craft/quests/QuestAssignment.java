package Frontiks.Craft.quests;

import java.util.LinkedHashMap;
import java.util.Map;

/** Персональный прогресс игрока по заданию (включая статус взятия и текущий этап цепочки). */
public final class QuestAssignment {
    public String id;
    /** Взял ли игрок задание в меню. Для шахтёрских задач с requireAccept=false считается активным сразу. */
    public boolean accepted;
    /** Индекс текущего этапа в цепочке steps (0-based). */
    public int stepIndex;
    /** Прогресс внутри текущего этапа. */
    public int progress;
    public boolean completed;
    public boolean paid;

    public QuestAssignment() {}

    public QuestAssignment(String id) {
        this.id = id;
    }

    public QuestAssignment(String id, boolean accepted) {
        this.id = id;
        this.accepted = accepted;
    }

    /** Идёт ли зачёт по этому заданию прямо сейчас. */
    public boolean isTracking(QuestDef def) {
        return !completed && (accepted || !def.requireAccept);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id == null ? "" : id);
        m.put("accepted", accepted);
        m.put("step", stepIndex);
        m.put("progress", progress);
        m.put("completed", completed);
        m.put("paid", paid);
        return m;
    }

    public static QuestAssignment fromMap(Map<?, ?> raw) {
        QuestAssignment a = new QuestAssignment();
        Object id = raw.get("id");
        a.id = id == null ? "" : String.valueOf(id).trim();
        a.accepted = bool(raw.get("accepted"));
        a.stepIndex = Math.max(0, QuestDef.num(raw.get("step"), 0));
        a.progress = Math.max(0, QuestDef.num(raw.get("progress"), 0));
        a.completed = bool(raw.get("completed"));
        a.paid = bool(raw.get("paid"));
        return a;
    }

    static boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        return v instanceof String s && Boolean.parseBoolean(s.trim());
    }
}

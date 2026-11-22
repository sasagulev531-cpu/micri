package Frontiks.Craft.quests;

import java.util.LinkedHashMap;
import java.util.Map;

/** Персональный прогресс по назначенному заданию. Чистая логика. */
public final class QuestAssignment {
    public String id;
    public int progress;
    public boolean completed;
    public boolean paid;

    public QuestAssignment() {}

    public QuestAssignment(String id) { this.id = id; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id == null ? "" : id);
        m.put("progress", progress);
        m.put("completed", completed);
        m.put("paid", paid);
        return m;
    }

    public static QuestAssignment fromMap(Map<?, ?> raw) {
        QuestAssignment a = new QuestAssignment();
        Object id = raw.get("id");
        a.id = id == null ? "" : String.valueOf(id).trim();
        a.progress = QuestDef.num(raw.get("progress"), 0);
        a.completed = bool(raw.get("completed"));
        a.paid = bool(raw.get("paid"));
        return a;
    }

    static boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        return v instanceof String s && Boolean.parseBoolean(s);
    }
}

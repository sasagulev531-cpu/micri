package Frontiks.Craft.work;

import Frontiks.Craft.jobs.JobKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Чистое ядро персистентности смен и заработка игроков (R7, R9.9) — без зависимости от Bukkit.
 * Гарантирует отсутствие потери заработанных за смену монет при краше/рестарте сервера
 * или временной недоступности EconomyService.
 */
public final class WorkSessionTracker {

    public static final long MAX_AUTO_RETRY_MS = 30L * 60L * 1000L;

    public static final class SessionRecord {
        public boolean active = false;
        public String jobId = JobKind.MINER_ID;
        public long startedAtMs = 0L;
        public int earnedCoins = 0;
        public int pendingPayoutCoins = 0;
        public long firstPayFailedAtMs = 0L;
        public boolean autoRetryExhausted = false;
        public long activeSeconds = 0L;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("active", active);
            m.put("job", jobId);
            m.put("started-at-ms", startedAtMs);
            m.put("earned-coins", earnedCoins);
            m.put("pending-payout-coins", pendingPayoutCoins);
            if (firstPayFailedAtMs > 0L) {
                m.put("first-pay-failed-ms", firstPayFailedAtMs);
            }
            if (autoRetryExhausted) {
                m.put("auto-retry-exhausted", true);
            }
            if (activeSeconds > 0L) {
                m.put("active-seconds", activeSeconds);
            }
            return m;
        }

        public static SessionRecord fromMap(Map<?, ?> raw) {
            SessionRecord r = new SessionRecord();
            if (raw == null) return r;
            r.active = bool(raw.get("active"), false);
            String j = str(raw.get("job"));
            r.jobId = j.isEmpty() ? JobKind.MINER_ID : j.toUpperCase();
            r.startedAtMs = Math.max(0L, longNum(raw.get("started-at-ms"), 0L));
            r.earnedCoins = Math.max(0, num(raw.get("earned-coins"), 0));
            r.pendingPayoutCoins = Math.max(0, num(raw.get("pending-payout-coins"), 0));
            r.firstPayFailedAtMs = Math.max(0L, longNum(raw.get("first-pay-failed-ms"), 0L));
            r.autoRetryExhausted = bool(raw.get("auto-retry-exhausted"), false);
            r.activeSeconds = Math.max(0L, longNum(raw.get("active-seconds"), 0L));
            return r;
        }
    }

    public record StopOutcome(int totalEarnedThisShift, int paidCoinsNow, boolean paidOk) {}

    private final Map<UUID, SessionRecord> sessions = new LinkedHashMap<>();

    public synchronized void startShift(UUID playerId, String jobId, long nowMs) {
        SessionRecord rec = sessions.computeIfAbsent(playerId, k -> new SessionRecord());
        // Если с прошлой незакрытой смены оставались монеты — переносим в очередь выплаты
        if (rec.active && rec.earnedCoins > 0) {
            rec.pendingPayoutCoins += rec.earnedCoins;
        }
        rec.active = true;
        rec.jobId = (jobId == null || jobId.isBlank()) ? JobKind.MINER_ID : jobId.trim().toUpperCase();
        rec.startedAtMs = nowMs;
        rec.earnedCoins = 0;
        rec.activeSeconds = 0L;
    }

    public synchronized boolean isWorking(UUID playerId) {
        SessionRecord rec = sessions.get(playerId);
        return rec != null && rec.active;
    }

    public synchronized String currentJobId(UUID playerId) {
        SessionRecord rec = sessions.get(playerId);
        if (rec == null || !rec.active) return JobKind.MINER_ID;
        return rec.jobId;
    }

    public synchronized boolean switchJob(UUID playerId, String newJobId) {
        SessionRecord rec = sessions.get(playerId);
        if (rec == null || !rec.active || newJobId == null || newJobId.isBlank()) return false;
        rec.jobId = newJobId.trim().toUpperCase();
        return true;
    }

    public synchronized void addEarned(UUID playerId, int amount) {
        if (amount <= 0) return;
        SessionRecord rec = sessions.computeIfAbsent(playerId, k -> new SessionRecord());
        rec.earnedCoins = (int) Math.min(Integer.MAX_VALUE, (long) rec.earnedCoins + amount);
    }

    public synchronized int getEarned(UUID playerId) {
        SessionRecord rec = sessions.get(playerId);
        return rec == null ? 0 : rec.earnedCoins;
    }

    public synchronized int getPendingPayout(UUID playerId) {
        SessionRecord rec = sessions.get(playerId);
        return rec == null ? 0 : rec.pendingPayoutCoins;
    }

    /**
     * Добавляет секунды активной смены и возвращает количество полных накопленных минут (для TIME_AS_JOB).
     */
    public synchronized int addActiveSecondsAndDrainMinutes(UUID playerId, int deltaSeconds) {
        SessionRecord rec = sessions.get(playerId);
        if (rec == null || !rec.active || deltaSeconds <= 0) return 0;
        rec.activeSeconds += deltaSeconds;
        int fullMinutes = (int) (rec.activeSeconds / 60L);
        if (fullMinutes > 0) {
            rec.activeSeconds %= 60L;
        }
        return fullMinutes;
    }

    public synchronized StopOutcome stopShift(UUID playerId,
                                              long nowMs,
                                              BiPredicate<UUID, Integer> coinPayoutSink) {
        SessionRecord rec = sessions.get(playerId);
        if (rec == null) {
            return new StopOutcome(0, 0, true);
        }
        int shiftEarned = rec.earnedCoins;
        rec.active = false;
        rec.earnedCoins = 0;
        rec.activeSeconds = 0L;

        long totalDueLong = (long) rec.pendingPayoutCoins + shiftEarned;
        int totalDue = (int) Math.min(Integer.MAX_VALUE, totalDueLong);
        if (totalDue <= 0) {
            sessions.remove(playerId);
            return new StopOutcome(0, 0, true);
        }

        boolean paidOk;
        try {
            paidOk = coinPayoutSink != null && coinPayoutSink.test(playerId, totalDue);
        } catch (Throwable t) {
            paidOk = false;
        }

        if (paidOk) {
            rec.pendingPayoutCoins = 0;
            rec.firstPayFailedAtMs = 0L;
            rec.autoRetryExhausted = false;
            sessions.remove(playerId);
            return new StopOutcome(shiftEarned, totalDue, true);
        } else {
            rec.pendingPayoutCoins = totalDue;
            if (rec.firstPayFailedAtMs <= 0L) {
                rec.firstPayFailedAtMs = nowMs;
            }
            return new StopOutcome(shiftEarned, 0, false);
        }
    }

    /**
     * Вызывается при старте плагина после рестарта/краша сервера:
     * переводит все прерванные смены в очередь выплаты и сразу пытается выплатить монеты.
     */
    public synchronized int recoverAfterRestart(long nowMs, BiPredicate<UUID, Integer> coinPayoutSink) {
        for (SessionRecord rec : sessions.values()) {
            if (rec.active) {
                rec.pendingPayoutCoins = (int) Math.min(Integer.MAX_VALUE,
                        (long) rec.pendingPayoutCoins + rec.earnedCoins);
                rec.earnedCoins = 0;
                rec.active = false;
                rec.activeSeconds = 0L;
            }
        }
        return retryPendingPayouts(nowMs, false, coinPayoutSink, null);
    }

    /**
     * Повторная попытка выплатить невыплаченные зарплаты смены (30 с интервал, 30 мин лимит для авто-повтора).
     */
    public synchronized int retryPendingPayouts(long nowMs,
                                                boolean manual,
                                                BiPredicate<UUID, Integer> coinPayoutSink,
                                                Consumer<String> adminAlertLogger) {
        int paidPlayers = 0;
        List<UUID> toClean = new ArrayList<>();

        for (Map.Entry<UUID, SessionRecord> entry : sessions.entrySet()) {
            UUID id = entry.getKey();
            SessionRecord rec = entry.getValue();
            if (rec.pendingPayoutCoins <= 0) {
                if (!rec.active) toClean.add(id);
                continue;
            }

            if (!manual) {
                if (rec.autoRetryExhausted) continue;
                if (rec.firstPayFailedAtMs > 0L && (nowMs - rec.firstPayFailedAtMs) > MAX_AUTO_RETRY_MS) {
                    rec.autoRetryExhausted = true;
                    if (adminAlertLogger != null) {
                        adminAlertLogger.accept("Превышен 30-минутный лимит авто-выплаты зарплаты смены ("
                                + rec.pendingPayoutCoins + " монет) игроку " + id);
                    }
                    continue;
                }
            }

            int amount = rec.pendingPayoutCoins;
            boolean ok;
            try {
                ok = coinPayoutSink != null && coinPayoutSink.test(id, amount);
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) {
                rec.pendingPayoutCoins = 0;
                rec.firstPayFailedAtMs = 0L;
                rec.autoRetryExhausted = false;
                paidPlayers++;
                if (!rec.active) toClean.add(id);
            } else if (rec.firstPayFailedAtMs <= 0L) {
                rec.firstPayFailedAtMs = nowMs;
            }
        }

        for (UUID id : toClean) {
            sessions.remove(id);
        }
        return paidPlayers;
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, SessionRecord> e : sessions.entrySet()) {
            SessionRecord r = e.getValue();
            if (r.active || r.earnedCoins > 0 || r.pendingPayoutCoins > 0) {
                out.put(e.getKey().toString(), r.toMap());
            }
        }
        return out;
    }

    public synchronized void loadFromMap(Map<?, ?> raw) {
        sessions.clear();
        if (raw == null) return;
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            try {
                UUID id = UUID.fromString(String.valueOf(e.getKey()).trim());
                if (e.getValue() instanceof Map<?, ?> m) {
                    SessionRecord rec = SessionRecord.fromMap(m);
                    if (rec.active || rec.earnedCoins > 0 || rec.pendingPayoutCoins > 0) {
                        sessions.put(id, rec);
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static boolean bool(Object o, boolean fb) {
        if (o instanceof Boolean b) return b;
        if (o == null) return fb;
        String s = str(o);
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
}

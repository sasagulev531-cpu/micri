package Frontiks.Craft.jobs;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Расширяемый реестр видов работы.
 * По умолчанию содержит только MINER («Шахтёр»).
 */
public final class JobRegistry {

    private final Map<String, JobKind> jobs = new LinkedHashMap<>();
    private String defaultJobId = JobKind.MINER_ID;

    public static JobRegistry withDefaultMiner() {
        JobRegistry reg = new JobRegistry();
        reg.register(JobKind.miner());
        return reg;
    }

    public synchronized void register(JobKind kind) {
        jobs.put(kind.id, kind);
        if (kind.defaultJob || jobs.size() == 1) {
            defaultJobId = kind.id;
        }
    }

    public synchronized JobKind get(String id) {
        if (id == null || id.isBlank()) return null;
        return jobs.get(id.trim().toUpperCase(Locale.ROOT));
    }

    public synchronized boolean contains(String id) {
        return get(id) != null;
    }

    public synchronized JobKind defaultJob() {
        JobKind d = jobs.get(defaultJobId);
        if (d != null) return d;
        return jobs.values().stream().findFirst().orElseGet(JobKind::miner);
    }

    public synchronized Collection<JobKind> all() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(jobs).values());
    }
}

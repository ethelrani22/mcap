package nic.meg.mcap.events;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds in-memory progress state for eligibility recalculation jobs.
 * Keyed by "windowId:programmeId" so concurrent saves for different
 * programmes don't interfere with each other.
 *
 * State is intentionally transient — if the server restarts mid-job
 * the admin just saves again. No DB persistence needed.
 */
@Component
public class RecalcProgressTracker {

    public static class Progress {
        public final int total;
        public final AtomicInteger processed = new AtomicInteger(0);
        public volatile boolean done = false;

        public Progress(int total) {
            this.total = total;
        }
    }

    private final ConcurrentHashMap<String, Progress> jobs = new ConcurrentHashMap<>();

    public String key(Short windowId, Short programmeId) {
        return windowId + ":" + programmeId;
    }

    public void start(Short windowId, Short programmeId, int total) {
        jobs.put(key(windowId, programmeId), new Progress(total));
    }

    public void increment(Short windowId, Short programmeId) {
        Progress p = jobs.get(key(windowId, programmeId));
        if (p != null) p.processed.incrementAndGet();
    }

    public void finish(Short windowId, Short programmeId) {
        Progress p = jobs.get(key(windowId, programmeId));
        if (p != null) p.done = true;
    }

    /** Returns null if no job is registered for this key. */
    public Progress get(Short windowId, Short programmeId) {
        return jobs.get(key(windowId, programmeId));
    }

    /** Clean up after the UI has acknowledged completion. */
    public void clear(Short windowId, Short programmeId) {
        jobs.remove(key(windowId, programmeId));
    }
}
package dev.ua.ikeepcalm.wiic.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounded, thread-safe rate limiter for click-level audit refusal rows: at most one row per
 * key (typically player + reason) per interval. Keeps a double-clicking or spam-clicking
 * player from turning a GUI refusal into a flood of identical ledger rows.
 */
public final class AuditSampler {
    public static final long DEFAULT_INTERVAL_MS = 5_000L;
    private static final int DEFAULT_MAX_ENTRIES = 4_096;

    private final long intervalMs;
    private final int maxEntries;
    private final Map<Object, Long> lastEmittedAt = new HashMap<>();

    public AuditSampler() {
        this(DEFAULT_INTERVAL_MS, DEFAULT_MAX_ENTRIES);
    }

    public AuditSampler(long intervalMs, int maxEntries) {
        this.intervalMs = intervalMs;
        this.maxEntries = maxEntries;
    }

    /** True when a row for {@code key} may be emitted now; records the emission. */
    public boolean shouldEmit(Object key) {
        return shouldEmit(key, System.currentTimeMillis());
    }

    public synchronized void clear() {
        lastEmittedAt.clear();
    }

    synchronized boolean shouldEmit(Object key, long now) {
        Long previous = lastEmittedAt.get(key);
        if (previous != null && now - previous < intervalMs) return false;
        if (previous == null && lastEmittedAt.size() >= maxEntries) {
            lastEmittedAt.entrySet().removeIf(e -> now - e.getValue() >= intervalMs);
            if (lastEmittedAt.size() >= maxEntries) return false;
        }
        lastEmittedAt.put(key, now);
        return true;
    }
}

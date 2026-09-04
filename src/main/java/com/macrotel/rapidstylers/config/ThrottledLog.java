package com.macrotel.rapidstylers.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Logs at most one WARNING per {@code key} per rolling window, collapsing the
 * identical "Redis down, falling back" lines that otherwise fire once per
 * degraded call, account, or attempt. When a window closes with suppressed
 * occurrences behind it, the next emitted line carries the count so ongoing
 * degradation stays visible without the per-call flood.
 *
 * Suppression only ever affects the log line — callers keep their own
 * per-occurrence counters (e.g. {@code totalDegradations}, the
 * /admin/cache_stats aggregates), so observability of every failure survives
 * the throttle.
 *
 * Thread-safe, and state is deliberately process-wide: multiple Spring
 * contexts in one JVM (e.g. a test suite rebooting the app repeatedly with
 * Redis down) share one window instead of re-emitting the same condition per
 * context.
 */
public final class ThrottledLog {

    /** Default suppression window: one representative WARNING per key per minute. */
    public static final long DEFAULT_WINDOW_MS = 60_000L;

    private static final ConcurrentHashMap<String, State> STATES = new ConcurrentHashMap<>();

    private ThrottledLog() {
    }

    /** Emit {@code message} at WARNING unless {@code key} logged within the last {@link #DEFAULT_WINDOW_MS}. */
    public static void warnOncePerWindow(Logger logger, String key, String message) {
        warnOncePerWindow(logger, key, message, DEFAULT_WINDOW_MS);
    }

    /** Package-private overload so tests can shrink the window without sleeping. */
    static void warnOncePerWindow(Logger logger, String key, String message, long windowMs) {
        if (logger == null || key == null || key.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        State state = STATES.computeIfAbsent(key, k -> new State());
        synchronized (state) {
            if (state.lastLogMs == 0L || now - state.lastLogMs >= windowMs) {
                state.lastLogMs = now;
                long suppressed = state.suppressed;
                state.suppressed = 0L;
                if (suppressed > 0L) {
                    logger.warning(message + " (" + suppressed
                            + " similar occurrences suppressed since previous log)");
                } else {
                    logger.warning(message);
                }
            } else {
                state.suppressed++;
            }
        }
    }

    /** Test seam: drops all window state. */
    static void reset() {
        STATES.clear();
    }

    private static final class State {
        long lastLogMs;
        long suppressed;
    }
}

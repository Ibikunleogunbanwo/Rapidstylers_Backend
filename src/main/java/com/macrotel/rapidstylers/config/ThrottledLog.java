package com.macrotel.rapidstylers.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Logs at most one WARNING per {@code key} per rolling window, collapsing the
 * identical "Redis down, falling back" lines that otherwise fire once per
 * degraded call, account, or attempt. When a window closes with suppressed
 * occurrences behind it, the next emitted line carries the count so ongoing
 * degradation stays visible without the per-call flood.
 *
 * <p>Suppression only ever affects the log line — callers keep their own
 * per-occurrence counters (e.g. {@code totalDegradations}, the
 * /admin/cache_stats aggregates), and this class additionally exposes its own
 * suppressed/emitted counters as Micrometer gauges (see
 * {@link #attachMeterRegistry(MeterRegistry)}), so sustained degradation is
 * alertable through actuator metrics even though the log volume is throttled:
 * while Redis stays down and traffic continues, {@code suppressedTotal} keeps
 * climbing one line per window instead of one line per call.
 *
 * <p>Thread-safe, and state is deliberately process-wide: multiple Spring
 * contexts in one JVM (e.g. a test suite rebooting the app repeatedly with
 * Redis down) share one window instead of re-emitting the same condition per
 * context.
 */
public final class ThrottledLog {

    /** Default suppression window: one representative WARNING per key per minute. */
    public static final long DEFAULT_WINDOW_MS = 60_000L;

    private static final ConcurrentHashMap<String, State> STATES = new ConcurrentHashMap<>();

    /** Registry gauges are attached to; set by {@link ThrottledLogMetrics} at context start. */
    private static volatile MeterRegistry attachedRegistry;

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
        State state = stateFor(key);
        synchronized (state) {
            if (state.lastLogMs == 0L || now - state.lastLogMs >= windowMs) {
                state.lastLogMs = now;
                state.emittedTotal++;
                long pending = state.pendingSuppressed;
                state.pendingSuppressed = 0L;
                if (pending > 0L) {
                    logger.warning(message + " (" + pending
                            + " similar occurrences suppressed since previous log)");
                } else {
                    logger.warning(message);
                }
            } else {
                state.pendingSuppressed++;
                state.suppressedTotal++;
            }
        }
    }

    /**
     * Start exposing the throttling counters as actuator metrics:
     * <ul>
     *   <li>{@code throttledlog.suppressedTotal} — lifetime occurrences suppressed
     *       (monotonic while degradation persists; flat once healthy)</li>
     *   <li>{@code throttledlog.pendingSuppressed} — suppressed since the last
     *       emission, i.e. inside the current window</li>
     *   <li>{@code throttledlog.emittedTotal} — lifetime representative lines emitted</li>
     *   <li>{@code throttledlog.byKey.suppressedTotal} / {@code ...pendingSuppressed}
     *       with tag {@code key} — per-throttle-key breakdown, registered as each
     *       key first fires</li>
     * </ul>
     * Idempotent per registry; the aggregate gauges are live views over the shared
     * state, so they exist (at zero) before any degradation occurs and alerting on
     * their growth works from context start.
     */
    public static void attachMeterRegistry(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        synchronized (ThrottledLog.class) {
            if (registry == attachedRegistry) {
                return;
            }
            attachedRegistry = registry;
            registerAggregateGauges(registry);
            STATES.forEach((key, state) -> registerPerKeyGauges(registry, key, state));
        }
    }

    /** Test seam / context teardown: forget a registry this class attached to. */
    static void detachMeterRegistry(MeterRegistry registry) {
        synchronized (ThrottledLog.class) {
            if (attachedRegistry == registry) {
                attachedRegistry = null;
            }
        }
    }

    /** Test seam: drops all window state and counters. */
    static void reset() {
        STATES.clear();
    }

    private static State stateFor(String key) {
        return STATES.computeIfAbsent(key, k -> {
            State state = new State();
            MeterRegistry registry = attachedRegistry;
            if (registry != null) {
                registerPerKeyGauges(registry, k, state);
            }
            return state;
        });
    }

    private static void registerAggregateGauges(MeterRegistry registry) {
        Gauge.builder("throttledlog.suppressedTotal", STATES,
                        states -> (double) sumLong(states, s -> s.suppressedTotal))
                .description("Cumulative occurrences suppressed by ThrottledLog windows — grows while degradation persists")
                .register(registry);
        Gauge.builder("throttledlog.pendingSuppressed", STATES,
                        states -> (double) sumLong(states, s -> s.pendingSuppressed))
                .description("Occurrences suppressed since the key's last emission (current window)")
                .register(registry);
        Gauge.builder("throttledlog.emittedTotal", STATES,
                        states -> (double) sumLong(states, s -> s.emittedTotal))
                .description("Cumulative representative WARNING lines emitted by ThrottledLog")
                .register(registry);
    }

    private static void registerPerKeyGauges(MeterRegistry registry, String key, State state) {
        Tags tags = Tags.of("key", key);
        Gauge.builder("throttledlog.byKey.suppressedTotal", state, s -> (double) s.suppressedTotal)
                .tags(tags)
                .description("Cumulative suppressed occurrences for this throttle key")
                .register(registry);
        Gauge.builder("throttledlog.byKey.pendingSuppressed", state, s -> (double) s.pendingSuppressed)
                .tags(tags)
                .description("Suppressed occurrences since the last emission for this throttle key")
                .register(registry);
    }

    private static long sumLong(Map<String, State> states, java.util.function.ToLongFunction<State> extractor) {
        long total = 0L;
        for (State state : states.values()) {
            total += extractor.applyAsLong(state);
        }
        return total;
    }

    private static final class State {
        long lastLogMs;
        long pendingSuppressed;
        long suppressedTotal;
        long emittedTotal;
    }
}

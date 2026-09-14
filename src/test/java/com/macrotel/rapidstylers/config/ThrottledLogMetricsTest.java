package com.macrotel.rapidstylers.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrottledLogMetricsTest {

    private MeterRegistry registry;
    private Logger logger;
    private final AtomicLong uniqueKeys = new AtomicLong();

    @BeforeEach
    void setUp() {
        ThrottledLog.reset();
        registry = new SimpleMeterRegistry();
        ThrottledLog.attachMeterRegistry(registry);
        logger = Logger.getLogger("throttled-log-metrics-test-" + System.nanoTime());
        logger.setUseParentHandlers(false); // keep WARNING noise out of test output
    }

    @AfterEach
    void tearDown() {
        ThrottledLog.detachMeterRegistry(registry);
        ThrottledLog.reset();
    }

    /**
     * The throttle state is deliberately process-wide, and cached Spring contexts
     * can keep degrading in the background during a test run — so assertions use
     * unique per-test keys (fully isolated per-key meters) or deltas (aggregates),
     * never exact global sums.
     */
    private String uniqueKey() {
        return "unit/" + uniqueKeys.incrementAndGet() + "-" + System.nanoTime();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double byKeyGauge(String name, String key) {
        return registry.get(name).tags("key", key).gauge().value();
    }

    @Test
    void aggregateGaugesAreRegisteredBeforeAnyDegradation() {
        // Alertability needs the meters to exist (readable as healthy) even before
        // the first degraded call — the aggregate gauges are attached at startup.
        assertDoesNotThrow(() -> gauge("throttledlog.suppressedTotal"));
        assertDoesNotThrow(() -> gauge("throttledlog.emittedTotal"));
        assertDoesNotThrow(() -> gauge("throttledlog.pendingSuppressed"));
    }

    /**
     * A window far longer than any test pause, so a call is guaranteed to
     * suppress. A 5 ms window here once failed CI: a GC pause between calls
     * expired it, the "suppressed" call emitted instead, and the aggregate
     * grew by 1 instead of 2.
     */
    private static final long LONG_WINDOW_MS = 60_000L;

    @Test
    void perKeyCounterCountsSuppressedAndStaysMonotonicAcrossWindows() throws InterruptedException {
        String key = uniqueKey();
        ThrottledLog.warnOncePerWindow(logger, key, "boom", LONG_WINDOW_MS);
        ThrottledLog.warnOncePerWindow(logger, key, "boom", LONG_WINDOW_MS);
        ThrottledLog.warnOncePerWindow(logger, key, "boom", LONG_WINDOW_MS);

        // 1 emitted + 2 suppressed inside the first window.
        assertEquals(2.0, byKeyGauge("throttledlog.byKey.suppressedTotal", key));
        assertEquals(2.0, byKeyGauge("throttledlog.byKey.pendingSuppressed", key));

        Thread.sleep(80);
        ThrottledLog.warnOncePerWindow(logger, key, "boom", 5L);

        // New window emits again; the lifetime total must not double-count the two
        // suppressed occurrences when the window flips.
        assertEquals(2.0, byKeyGauge("throttledlog.byKey.suppressedTotal", key),
                "suppressed total is monotonic per key");
        assertEquals(0.0, byKeyGauge("throttledlog.byKey.pendingSuppressed", key),
                "window-local count resets at emission");
    }

    @Test
    void perKeyGaugesAreIndependentPerKey() {
        String quietKey = uniqueKey();
        String noisyKey = uniqueKey();
        ThrottledLog.warnOncePerWindow(logger, quietKey, "boom", LONG_WINDOW_MS); // 1 emitted, nothing suppressed
        ThrottledLog.warnOncePerWindow(logger, noisyKey, "boom", LONG_WINDOW_MS);
        ThrottledLog.warnOncePerWindow(logger, noisyKey, "boom", LONG_WINDOW_MS); // suppressed
        ThrottledLog.warnOncePerWindow(logger, noisyKey, "boom", LONG_WINDOW_MS); // suppressed

        assertEquals(0.0, byKeyGauge("throttledlog.byKey.suppressedTotal", quietKey));
        assertEquals(2.0, byKeyGauge("throttledlog.byKey.suppressedTotal", noisyKey));
        assertEquals(2.0, byKeyGauge("throttledlog.byKey.pendingSuppressed", noisyKey));
    }

    @Test
    void aggregateSuppressedTotalGrowsWithSuppressedOccurrences() {
        String key = uniqueKey();
        ThrottledLog.warnOncePerWindow(logger, key, "boom", LONG_WINDOW_MS);
        double emittedBefore = gauge("throttledlog.emittedTotal");
        double suppressedBefore = gauge("throttledlog.suppressedTotal");

        ThrottledLog.warnOncePerWindow(logger, key, "boom", LONG_WINDOW_MS); // suppressed
        ThrottledLog.warnOncePerWindow(logger, key, "boom", LONG_WINDOW_MS); // suppressed

        assertTrue(gauge("throttledlog.suppressedTotal") >= suppressedBefore + 2.0,
                "aggregate suppressed total must reflect the suppressed occurrences");
        assertTrue(gauge("throttledlog.emittedTotal") >= emittedBefore,
                "aggregate emitted total never shrinks");
    }
}

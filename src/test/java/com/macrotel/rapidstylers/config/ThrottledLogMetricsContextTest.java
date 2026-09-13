package com.macrotel.rapidstylers.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Guards the actuator wiring for the throttled-degradation counters: the
 * ThrottledLogMetrics component must attach the gauges to the running context's
 * MeterRegistry, so a future refactor that drops the component (or its
 * registration) silently removes the only numeric signal that sustained
 * degradation is still happening once log lines are throttled.
 */
@SpringBootTest
class ThrottledLogMetricsContextTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void throttledLogGaugesAreRegisteredInTheRunningContext() {
        assertDoesNotThrow(() -> meterRegistry.get("throttledlog.suppressedTotal").gauge(),
                "aggregate suppressedTotal gauge must be registered by ThrottledLogMetrics");
        assertDoesNotThrow(() -> meterRegistry.get("throttledlog.emittedTotal").gauge(),
                "aggregate emittedTotal gauge must be registered by ThrottledLogMetrics");
        assertDoesNotThrow(() -> meterRegistry.get("throttledlog.pendingSuppressed").gauge(),
                "aggregate pendingSuppressed gauge must be registered by ThrottledLogMetrics");
    }
}

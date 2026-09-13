package com.macrotel.rapidstylers.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Attaches {@link ThrottledLog}'s suppressed/emitted counters to the application
 * MeterRegistry so they surface under /actuator/metrics (exposed alongside
 * /actuator/health by RapidstylersApplication). Uses ObjectProvider so contexts
 * without a MeterRegistry (none today, but possible in exotic test slices) boot
 * without failing.
 */
@Component
public class ThrottledLogMetrics {

    public ThrottledLogMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            ThrottledLog.attachMeterRegistry(registry);
        }
    }
}

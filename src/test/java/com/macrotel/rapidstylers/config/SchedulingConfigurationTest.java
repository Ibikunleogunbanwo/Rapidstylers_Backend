package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.RapidstylersApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulingConfigurationTest {

    @Test
    void schedulingIsEnabledByAnOptOutDefaultPropertyInsteadOfUnconditionally() throws Exception {
        assertFalse(RapidstylersApplication.class.isAnnotationPresent(EnableScheduling.class),
                "Application must not enable provider-capable schedules unconditionally");

        Class<?> config = Class.forName("com.macrotel.rapidstylers.config.SchedulingConfiguration");
        assertTrue(config.isAnnotationPresent(EnableScheduling.class));
        ConditionalOnProperty condition = config.getAnnotation(ConditionalOnProperty.class);
        assertEquals("app.scheduling", condition.prefix());
        assertEquals("enabled", condition.name()[0]);
        assertTrue(condition.matchIfMissing(), "Scheduled jobs remain enabled by default");
    }
}

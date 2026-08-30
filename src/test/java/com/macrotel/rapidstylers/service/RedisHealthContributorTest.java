package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the runtime Redis liveness wiring: Docker's app healthcheck curls
 * /actuator/health and treats a non-2xx (i.e. a DOWN aggregate) as unhealthy.
 * That only works because Spring Boot auto-registers a Redis health contributor
 * from the RedisConnectionFactory. This test locks that contributor in place so a
 * future property change (e.g. management.health.redis.enabled=false) or a dropped
 * dependency can't silently remove Redis from the health check. Registration only —
 * it passes whether Redis is up or down.
 */
@SpringBootTest
class RedisHealthContributorTest {

    @Autowired
    private HealthContributorRegistry registry;

    @Test
    void redisHealthContributorIsRegistered() {
        HealthContributor contributor = registry.getContributor("redis");
        assertNotNull(contributor, "Expected a 'redis' actuator health contributor to be registered");
    }

    @Test
    void dbHealthContributorIsRegistered() {
        HealthContributor db = registry.getContributor("db");
        assertNotNull(db, "Expected a 'db' actuator health contributor to be registered");
        assertTrue(registry.getContributor("diskSpace") != null, "Expected diskSpace health contributor");
    }
}
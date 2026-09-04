package com.macrotel.rapidstylers.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logs the effective Redis connection settings (host/port/auth) and probes the
 * connection once at startup, so a silent connection failure — the app appearing
 * to work while every Redis-backed feature degrades to the database — is visible
 * in the first lines of the log instead of going unnoticed. Never fails startup:
 * if Redis is down, the app boots normally and the features degrade gracefully.
 */
@Component
public class RedisStartupMonitor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RedisStartupMonitor.class);

    /**
     * The failure diagnostic is emitted once per JVM: a test suite that boots the
     * same context repeatedly (or a multi-context deploy) would otherwise print
     * an identical wall of "Redis connection FAILED" lines. A single running app
     * still sees it exactly once — at startup, where it belongs.
     */
    private static final AtomicBoolean FAILURE_ALREADY_WARNED = new AtomicBoolean();

    private final RedisConnectionFactory connectionFactory;

    @Value("${spring.redis.host:localhost}")
    private String host;

    @Value("${spring.redis.port:6379}")
    private int port;

    @Value("${spring.redis.password:}")
    private String password;

    public RedisStartupMonitor(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean authConfigured = password != null && !password.isEmpty();
        String probe;
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String pong = connection.ping();
            probe = pong == null ? "no response" : "OK (" + pong + ")";
            log.info("Redis connected — host={} port={} auth={} probe={}",
                    safe(host), port, authConfigured ? "configured" : "none", probe);
        } catch (Exception ex) {
            // Best-effort diagnostic only; never block or fail startup.
            if (FAILURE_ALREADY_WARNED.compareAndSet(false, true)) {
                log.warn("Redis connection FAILED — host={} port={} auth={}. Redis-backed features "
                                + "(geo index, rate limiting, read cache, idempotency, notification dedup) "
                                + "will silently fall back to the database until Redis is reachable. Error: {}",
                        safe(host), port, authConfigured ? "configured" : "none", ex.getMessage());
            } else {
                log.debug("Redis still unreachable at startup (previous probe already warned): {}", ex.getMessage());
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "null" : value;
    }
}
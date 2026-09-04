package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live-Redis integration test for {@link RateLimiterService}, mirroring how the
 * app is wired: a plain {@link StringRedisTemplate} (string key AND value
 * serializers) executing the atomic INCR + EXPIRE Lua script.
 *
 * <p>This guards the regression where the script's TTL argument was serialized
 * JSON-quoted — Redis rejected {@code EXPIRE} and counters were left with
 * <b>no TTL</b>, permanently blocking a key once it reached the cap. Here we
 * assert that counters actually carry a TTL and expire at the window boundary.
 *
 * <p>The connection settings (host/port/password) are read from the repo-root
 * {@code .env} exactly as the running app uses them. When no Redis answers
 * (unreachable, wrong credentials, or no {@code .env}) the whole class is
 * skipped — never failed — so the suite stays green on machines without Redis.
 * Keys are unique per run and deleted afterwards, so the test never touches
 * counters the application or other tests rely on.
 */
class RateLimiterServiceRedisIntegrationTest {

    private static final String KEY_PREFIX = "it:ratelimit:" + UUID.randomUUID().toString().substring(0, 8) + ":";

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RateLimiterService rateLimiter;
    private static boolean connected;

    @BeforeAll
    static void connectToLiveRedis() {
        String host = env("REDIS_HOST", "localhost");
        int port = Integer.parseInt(env("REDIS_PORT", "6379"));
        String password = env("REDIS_PASSWORD", "");

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            config.setPassword(RedisPassword.of(password));
        }
        connectionFactory = new LettuceConnectionFactory(config);
        // Short command timeout so an unresponsive Redis skips fast instead of
        // holding the suite for Lettuce's default (60s).
        connectionFactory.setTimeout(2_000L); // milliseconds; Lettuce default is 60s
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        boolean reachable = ping(redisTemplate);
        // No destroy() on the skip path: @AfterAll runs even after a @BeforeAll
        // abort, so cleanup below must be the single place that tears the factory
        // down (a second destroy() throws IllegalStateException, which is exactly
        // what turned a clean skip into a failure on CI, where Redis is absent).
        if (!reachable) {
            assumeTrue(false, "Live Redis not reachable at " + host + ":" + port + " — skipping Redis integration test");
        }
        connected = true;
        rateLimiter = new RateLimiterService(redisTemplate);
    }

    @AfterAll
    static void cleanUp() {
        if (connected) {
            try {
                Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception ignored) {
                // Redis became unreachable mid-run; nothing left to clean.
            }
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private static boolean ping(StringRedisTemplate template) {
        try {
            String pong = template.execute((RedisCallback<String>) connection -> connection.ping());
            return pong != null && pong.equalsIgnoreCase("PONG");
        } catch (Exception e) {
            return false;
        }
    }

    // ── TTL regression: counters must carry the window TTL ─────────────────

    @Test
    void counterCarriesWindowTtlAfterRecord() {
        String key = key("ttl-900");

        rateLimiter.record(key, 900);

        assertEquals("1", redisTemplate.opsForValue().get(key), "first record must set the counter to 1");
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl, "counter must have a TTL");
        assertTrue(ttl > 800 && ttl <= 900,
                "TTL must be the full 900s window, not absent (bug) or truncated: got " + ttl);
    }

    @Test
    void shortWindowTtlMatchesRequestedSeconds() {
        String key = key("ttl-2");

        rateLimiter.record(key, 2);

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl, "counter must have a TTL");
        assertTrue(ttl > 0 && ttl <= 2, "TTL must be within the 2s window: got " + ttl);
    }

    // ── Expiry at the window boundary ──────────────────────────────────────

    @Test
    void blockedCounterExpiresAndWindowResetsAtBoundary() throws Exception {
        String key = key("expiry");

        for (int i = 0; i < 3; i++) {
            rateLimiter.record(key, 1);
        }
        assertEquals("3", redisTemplate.opsForValue().get(key), "counter must reach the cap");
        assertTrue(rateLimiter.isBlocked(key, 1, 3), "cap must block within the window");

        // Wait past the 1s window so Redis expires the key.
        Thread.sleep(1_400L);

        assertNull(redisTemplate.opsForValue().get(key), "key must be gone after the window elapses");
        assertFalse(rateLimiter.isBlocked(key, 1, 3), "expired counter must not keep blocking");

        // A fresh window starts at zero attempts.
        rateLimiter.record(key, 1);
        assertEquals("1", redisTemplate.opsForValue().get(key),
                "a record after expiry must start a fresh window at 1");
        assertFalse(rateLimiter.isBlocked(key, 1, 3), "fresh window must not be blocked yet");
    }

    @Test
    void clearRemovesLiveCounter() {
        String key = key("clear");

        rateLimiter.record(key, 900);
        assertNotNull(redisTemplate.opsForValue().get(key));

        rateLimiter.clear(key);

        assertNull(redisTemplate.opsForValue().get(key), "clear() must delete the Redis counter");
        assertFalse(rateLimiter.isBlocked(key, 900, 1));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String key(String name) {
        return KEY_PREFIX + name;
    }

    /** Reads a KEY=VALUE line from the repo-root .env (used by the app itself). */
    private static String env(String name, String defaultValue) {
        File dotEnv = new File(".env");
        if (!dotEnv.exists()) {
            return defaultValue;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(dotEnv))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.startsWith(name + "=")) {
                    continue;
                }
                String value = line.substring(name.length() + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        } catch (IOException ignored) {
            // Fall through to the default.
        }
        return defaultValue;
    }
}
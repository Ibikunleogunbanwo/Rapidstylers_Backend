package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live-Redis guard for the serializer rule that came out of the rate-limiter
 * post-mortem: keys holding plain strings (idempotency markers, session
 * activity timestamps, notification-dedup claims, geo members) must be stored
 * PLAIN on the wire. If any of these services is ever wired back to the
 * Jackson value serializer, values become JSON-quoted ("\"abc\"") and this
 * test fails — exactly the failure class that left rate-limit counters with no
 * TTL and permanent blocks.
 *
 * <p>The services under test are constructed exactly as production wires them:
 * each takes a {@link StringRedisTemplate}. Raw wire bytes are inspected via
 * the connection factory so the assertions are byte-exact, not dependent on a
 * particular template's reader.
 *
 * <p>Skips cleanly (whole class) when no Redis answers, like the rate-limiter
 * integration test. All keys are namespaced under a per-run id and deleted
 * afterwards; the geo index is only ever touched for the unique test member.
 */
class RedisPlainStringIntegrationTest {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String IDEM_SCOPE = "it";
    private static final String IDEM_ACTOR = "plain" + RUN;
    private static final String SESSION_ACCOUNT = "acc-itplain" + RUN;
    private static final String DEDUP_EVENT = "event-itplain" + RUN;
    private static final String GEO_MEMBER = "ITPLAIN" + RUN;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static boolean connected;
    private static final List<String> trackedKeys = new ArrayList<>();

    // Services under test, wired with the string-serialized template like production.
    private static IdempotencyService idempotency;
    private static SessionActivityService sessionActivity;
    private static NotificationDedupService notificationDedup;
    private static LocationCacheService locationCache;

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
            assumeTrue(false, "Live Redis not reachable at " + host + ":" + port + " — skipping plain-string integration test");
        }
        connected = true;

        idempotency = new IdempotencyService(redisTemplate);
        sessionActivity = new SessionActivityService(redisTemplate);
        notificationDedup = new NotificationDedupService(redisTemplate);
        locationCache = new LocationCacheService(redisTemplate);
        ReflectionTestUtils.setField(notificationDedup, "ttlHours", 24L);
        ReflectionTestUtils.setField(sessionActivity, "customerIdleMinutes", 60L);
        ReflectionTestUtils.setField(sessionActivity, "stylerIdleMinutes", 30L);
        ReflectionTestUtils.setField(sessionActivity, "adminIdleMinutes", 30L);
    }

    @AfterAll
    static void cleanUp() {
        if (connected) {
            // Remove the geo test member if still present, then drop every tracked key.
            try {
                redisTemplate.opsForZSet().remove("stylers:geo", GEO_MEMBER);
            } catch (Exception ignored) {
            }
            for (String key : trackedKeys) {
                try {
                    redisTemplate.delete(key);
                } catch (Exception ignored) {
                }
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

    // ── IdempotencyService ───────────────────────────────────────────

    @Test
    void idempotencyMarkerIsStoredPlainAndReleaseRemovesIt() {
        String idemKey = "idempotency:" + IDEM_SCOPE + ":" + IDEM_ACTOR + ":k1";
        track(idemKey);

        IdempotencyService.Claim claim = idempotency.claim(IDEM_SCOPE, IDEM_ACTOR, "k1", Duration.ofHours(1));
        assertTrue(claim.isAcquired());

        String raw = rawString(idemKey);
        assertNotNull(raw, "claim marker must exist");
        assertTrue(raw.matches("[0-9a-f-]{36}"),
                "marker must be a PLAIN unquoted UUID on the wire (JSON serializer would quote it): '" + raw + "'");

        // Held claim → duplicate, not a second acquire.
        assertTrue(idempotency.claim(IDEM_SCOPE, IDEM_ACTOR, "k1", Duration.ofHours(1)).isDuplicate());

        idempotency.release(claim);
        assertNull(rawString(idemKey), "release() must delete the claim (plain compare works only when stored plain)");
    }

    // ── SessionActivityService ───────────────────────────────────────

    @Test
    void sessionActivityTimestampIsStoredAsPlainNumber() {
        String key = "rapidstylers:session:activity:" + SESSION_ACCOUNT;
        track(key);

        sessionActivity.touch(SESSION_ACCOUNT, "CUSTOMER");

        String raw = rawString(key);
        assertNotNull(raw, "activity timestamp must exist");
        assertTrue(raw.matches("\\d{13}"),
                "timestamp must be a PLAIN 13-digit epoch millis on the wire (JSON serializer would quote it): '" + raw + "'");
        // The value must parse back to a sane recent timestamp.
        long epoch = Long.parseLong(raw);
        assertTrue(Math.abs(System.currentTimeMillis() - epoch) < 5_000L, "stored timestamp should be ~now");
    }

    // ── NotificationDedupService ─────────────────────────────────────

    @Test
    void notificationDedupClaimIsPlainAndReleasable() {
        String key = "notif:delivered:" + DEDUP_EVENT;
        track(key);

        assertTrue(notificationDedup.tryClaim(DEDUP_EVENT), "first delivery must claim");
        assertEquals("1", rawString(key), "claim must be the PLAIN string '1' (JSON serializer would store '\"1\"')");

        assertFalse(notificationDedup.tryClaim(DEDUP_EVENT), "redelivered event must be skipped while claimed");

        notificationDedup.release(DEDUP_EVENT);
        assertNull(rawString(key), "release() must remove the claim");
        assertTrue(notificationDedup.tryClaim(DEDUP_EVENT), "after release the event must be claimable again");
    }

    // ── LocationCacheService (geo) ───────────────────────────────────

    @Test
    void geoMembersArePlainAndRemovable() {
        locationCache.indexStyler(GEO_MEMBER, -79.38, 43.65);

        Set<String> members = redisTemplate.opsForZSet().range("stylers:geo", 0, -1);
        assertNotNull(members);
        assertTrue(members.contains(GEO_MEMBER),
                "geo member must be stored PLAIN (a JSON serializer would store '\"" + GEO_MEMBER + "\"' and this lookup would miss)");

        locationCache.removeStyler(GEO_MEMBER);

        Set<String> after = redisTemplate.opsForZSet().range("stylers:geo", 0, -1);
        assertNotNull(after);
        assertFalse(after.contains(GEO_MEMBER),
                "removeStyler must delete the member — plain ZREM only matches plain members");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Raw value bytes for a key, decoded as UTF-8 — the ground truth on the wire. */
    private static String rawString(String key) {
        byte[] bytes = connectionFactory.getConnection().get(key.getBytes(StandardCharsets.UTF_8));
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static void track(String key) {
        trackedKeys.add(key);
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

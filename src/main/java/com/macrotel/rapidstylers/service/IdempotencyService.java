package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.ThrottledLog;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Atomic, short-lived claims used to prevent duplicate mutation requests.
 * Also stores completed responses for replay on duplicate requests.
 *
 * Values are plain strings (UUID markers, response JSON), so this uses the
 * string-serialized template — storing them through a JSON value serializer
 * would quote every value on the wire and break any plain-string reader
 * (cross-serializer access is exactly what broke rate limiting before).
 */
@Service
public class IdempotencyService {

    private static final Logger LOG = Logger.getLogger(IdempotencyService.class.getName());

    private final StringRedisTemplate redisTemplate;

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Claim claim(String scope, String actorId, String idempotencyKey, Duration ttl) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return Claim.notProvided();
        }
        String normalized = idempotencyKey.trim();
        String redisKey = "idempotency:" + scope + ":" + actorId + ":" + normalized;
        String marker = UUID.randomUUID().toString();
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(redisKey, marker, ttl);
        return Boolean.TRUE.equals(claimed) ? Claim.acquired(redisKey, marker) : Claim.duplicate(redisKey);
    }

    /**
     * Store a completed response for replay on duplicate requests.
     * The response is stored with a shorter TTL (1 hour) since it's only
     * needed for network-timeout recovery.
     */
    public void storeResponse(String scope, String actorId, String idempotencyKey, String responseJson) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty() || responseJson == null) {
            return;
        }
        String responseKey = "idempotency:response:" + scope + ":" + actorId + ":" + idempotencyKey.trim();
        try {
            redisTemplate.opsForValue().set(responseKey, responseJson, Duration.ofHours(1));
        } catch (Exception ex) {
            ThrottledLog.warnOncePerWindow(LOG, "idempotency/store",
                    "Failed to store idempotency response: " + ex.getMessage());
        }
    }

    /**
     * Retrieve a previously stored response for replay.
     * Returns null if no stored response exists.
     */
    public String getStoredResponse(String scope, String actorId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return null;
        }
        String responseKey = "idempotency:response:" + scope + ":" + actorId + ":" + idempotencyKey.trim();
        try {
            Object stored = redisTemplate.opsForValue().get(responseKey);
            return stored != null ? String.valueOf(stored) : null;
        } catch (Exception ex) {
            ThrottledLog.warnOncePerWindow(LOG, "idempotency/retrieve",
                    "Failed to retrieve idempotency response: " + ex.getMessage());
            return null;
        }
    }

    public void release(Claim claim) {
        if (claim == null || !claim.acquired || claim.key == null) return;
        try {
            String current = redisTemplate.opsForValue().get(claim.key);
            if (claim.marker.equals(current)) {
                redisTemplate.delete(claim.key);
            }
        } catch (Exception ex) {
            // Best-effort cleanup — a claim that cannot be released only lingers
            // until its TTL expires; never throw on the request path.
            ThrottledLog.warnOncePerWindow(LOG, "idempotency/release",
                    "Failed to release idempotency claim: " + ex.getMessage());
        }
    }

    public static final class Claim {
        private final String key;
        private final String marker;
        private final boolean acquired;
        private final boolean provided;

        private Claim(String key, String marker, boolean acquired, boolean provided) {
            this.key = key;
            this.marker = marker;
            this.acquired = acquired;
            this.provided = provided;
        }

        static Claim notProvided() { return new Claim(null, null, false, false); }
        static Claim acquired(String key, String marker) { return new Claim(key, marker, true, true); }
        static Claim duplicate(String key) { return new Claim(key, null, false, true); }
        public boolean isAcquired() { return acquired; }
        public boolean isProvided() { return provided; }
        public boolean isDuplicate() { return provided && !acquired; }
    }
}

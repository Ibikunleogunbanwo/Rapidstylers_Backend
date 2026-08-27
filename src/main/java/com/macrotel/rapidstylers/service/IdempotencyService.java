package com.macrotel.rapidstylers.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/** Atomic, short-lived claims used to prevent duplicate mutation requests. */
@Service
public class IdempotencyService {
    private final RedisTemplate<String, Object> redisTemplate;

    public IdempotencyService(RedisTemplate<String, Object> redisTemplate) {
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

    public void release(Claim claim) {
        if (claim == null || !claim.acquired || claim.key == null) return;
        Object current = redisTemplate.opsForValue().get(claim.key);
        if (claim.marker.equals(current)) {
            redisTemplate.delete(claim.key);
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

package com.macrotel.rapidstylers.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Consumer-side idempotency for notification emails, keyed on the outbox
 * event id.
 *
 * Kafka delivers at-least-once: a consumer that crashes after sending an
 * email but before acknowledging causes the same event to be redelivered, and
 * a duplicate email reaches the customer. This service atomically claims an
 * event id before processing; a redelivered event finds its claim already
 * held and is skipped.
 *
 * The claim is released when processing fails so the retry path can
 * legitimately reprocess the event. Redis outages degrade safely: the claim
 * is treated as available (at-least-once delivery is preserved, only the
 * duplicate protection is lost).
 */
@Service
public class NotificationDedupService {

    private static final Logger LOG = Logger.getLogger(NotificationDedupService.class.getName());

    private static final String KEY_PREFIX = "notif:delivered:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final AtomicLong totalDegradations = new AtomicLong();

    @Value("${app.notifications.dedup-ttl-hours:24}")
    private long ttlHours;

    public NotificationDedupService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Returns true when the event should be processed, false when a previous
     * delivery already claimed it (duplicate — skip). Events without an id,
     * and Redis outages, always return true so delivery is never lost.
     */
    public boolean tryClaim(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        try {
            Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
                    KEY_PREFIX + eventId, "1", Duration.ofHours(ttlHours));
            return Boolean.TRUE.equals(claimed);
        } catch (Exception ex) {
            // Fail-open is deliberate (never lose an email), but it must be observable:
            // every outage is counted and logged so ops can detect duplicate-mail
            // risk instead of only noticing after customers receive doubles.
            totalDegradations.incrementAndGet();
            LOG.warning("Notification dedup unavailable — processing anyway (degradations="
                    + totalDegradations.get() + "): " + ex.getMessage());
            return true;
        }
    }

    /** Releases a claim after a failed delivery so retries can reprocess the event. */
    public void release(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + eventId);
        } catch (Exception ex) {
            totalDegradations.incrementAndGet();
            LOG.warning("Notification dedup release failed: " + ex.getMessage());
        }
    }

    /** Number of Redis failures while claiming/releasing since boot — ops signal. */
    public long degradations() {
        return totalDegradations.get();
    }
}

package com.macrotel.rapidstylers.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-side idle/session-activity tracking backed by Redis. Every authenticated
 * request "touches" a per-account last-activity timestamp (keyed so a Redis flush
 * cannot resurrect a stale idle state into a false "still active"). The refresh
 * endpoint reads it to decide whether a session has been idle too long and, if so,
 * bricks the whole account's refresh-token family so the sign-out is honoured on
 * every tab and device — and cannot be bypassed by silent access-token renewal
 * (that renewal crosses this check on every /auth/refresh).
 *
 * Fail-open: a Redis miss or error is treated as "not idle" rather than a mass
 * lockout, so an outage or cache flush never logs every user out. A real request
 * re-seeds the timestamp on the next authenticated call.
 *
 * Values are plain numeric strings, so this uses the string-serialized template
 * — a JSON value serializer would quote them on the wire and break parsing for
 * any plain-string reader (the rate-limiter class of bug).
 */
@Service
public class SessionActivityService {

    private static final Logger LOG = Logger.getLogger(SessionActivityService.class.getName());
    private static final String ACTIVITY_KEY_PREFIX = "rapidstylers:session:activity:";
    private static final String START_KEY_PREFIX = "rapidstylers:session:start:";

    // Keep the key around slightly longer than the longest idle window so the
    // refresh-time comparison can still read an old-but-expired value and will not
    // race the key's natural eviction right at the boundary.
    private static final long ACTIVITY_BUFFER_MINUTES = 10;

    private final StringRedisTemplate redisTemplate;

    @Value("${app.session.idle.customer-minutes:60}")
    private long customerIdleMinutes;

    @Value("${app.session.idle.styler-minutes:30}")
    private long stylerIdleMinutes;

    @Value("${app.session.idle.admin-minutes:30}")
    private long adminIdleMinutes;

    // Absolute session caps (0 = disabled). Only admin has a hard cap today; the
    // other roles rely on the rotating 7-day refresh token as their ceiling.
    @Value("${app.session.absolute.customer-hours:0}")
    private long customerAbsoluteHours;

    @Value("${app.session.absolute.styler-hours:0}")
    private long stylerAbsoluteHours;

    @Value("${app.session.absolute.admin-hours:8}")
    private long adminAbsoluteHours;

    public SessionActivityService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Role idle window in milliseconds. */
    public long idleMsFor(String role) {
        long minutes;
        if ("STYLER".equalsIgnoreCase(role)) {
            minutes = stylerIdleMinutes;
        } else if ("ADMIN".equalsIgnoreCase(role)) {
            minutes = adminIdleMinutes;
        } else {
            minutes = customerIdleMinutes;
        }
        return minutes * 60_000L;
    }

    /**
     * Record activity for an account now. Best-effort — never throws, never fails
     * the request it rides on.
     */
    public void touch(String accountId, String role) {
        try {
            long idleMs = idleMsFor(role);
            Duration ttl = Duration.ofMillis(idleMs + ACTIVITY_BUFFER_MINUTES * 60_000L);
            redisTemplate.opsForValue().set(
                    activityKey(accountId), String.valueOf(System.currentTimeMillis()), ttl);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Session activity touch failed for " + accountId + ": " + ex.getMessage());
        }
    }

    /**
     * True if the account has been idle longer than its role-specific window.
     * Read-only (never seeds a fresh timestamp) so that background refresh calls
     * cannot silently reset the idle clock. Fail-open on Redis miss/error.
     */
    public boolean isIdle(String accountId, String role) {
        try {
            String value = redisTemplate.opsForValue().get(activityKey(accountId));
            if (value == null) {
                return false; // no record yet / Redis was flushed — give the benefit of the doubt
            }
            value = value.trim();
            if (value.isEmpty()) {
                return false;
            }
            long lastActivity = Long.parseLong(value);
            return (System.currentTimeMillis() - lastActivity) >= idleMsFor(role);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Session activity read failed for " + accountId + ": " + ex.getMessage());
            return false;
        }
    }

    /**
     * Record the moment this session (refresh-token family) began, i.e. a fresh
     * login. Used as the anchor for an absolute-session cap: even an active admin
     * is forced to re-login once the hard cap elapses. Best-effort, never throws.
     */
    public void markLogin(String accountId, String role) {
        try {
            long hours = absoluteHoursFor(role);
            // Keep the anchor readable longer than the largest cap so the comparison
            // still sees an old-but-expired session instead of a vanished key.
            Duration ttl = Duration.ofHours(hours > 0 ? hours : 24L).plus(Duration.ofMinutes(ACTIVITY_BUFFER_MINUTES));
            redisTemplate.opsForValue().set(
                    startKey(accountId), String.valueOf(System.currentTimeMillis()), ttl);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Session start write failed for " + accountId + ": " + ex.getMessage());
        }
    }

    /**
     * True once a refresh-based session has outlived its absolute lifetime (role
     * cap hours). Read-only; fails open on a Redis miss or error, and is always
     * false for roles with no configured cap (absoluteHoursFor == 0).
     */
    public boolean absoluteExpired(String accountId, String role) {
        long hours = absoluteHoursFor(role);
        if (hours <= 0) {
            return false;
        }
        try {
            String raw = redisTemplate.opsForValue().get(startKey(accountId));
            if (raw == null) {
                return false;
            }
            long startedAt = Long.parseLong(raw.trim());
            return (System.currentTimeMillis() - startedAt) >= hours * 3600_000L;
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Session start read failed for " + accountId + ": " + ex.getMessage());
            return false;
        }
    }

    /** Drop the activity + start markers, e.g. on an explicit logout or sign-out. */
    public void clear(String accountId) {
        try {
            redisTemplate.delete(activityKey(accountId));
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Session activity clear failed for " + accountId + ": " + ex.getMessage());
        }
        try {
            redisTemplate.delete(startKey(accountId));
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Session start clear failed for " + accountId + ": " + ex.getMessage());
        }
    }

    private long absoluteHoursFor(String role) {
        if ("STYLER".equalsIgnoreCase(role)) {
            return stylerAbsoluteHours;
        }
        if ("ADMIN".equalsIgnoreCase(role)) {
            return adminAbsoluteHours;
        }
        return customerAbsoluteHours;
    }

    private static String activityKey(String accountId) {
        return ACTIVITY_KEY_PREFIX + accountId;
    }

    private static String startKey(String accountId) {
        return START_KEY_PREFIX + accountId;
    }
}
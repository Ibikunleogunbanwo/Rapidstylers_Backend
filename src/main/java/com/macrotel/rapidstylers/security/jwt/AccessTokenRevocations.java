package com.macrotel.rapidstylers.security.jwt;

import com.macrotel.rapidstylers.config.ThrottledLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.logging.Logger;

/**
 * Makes sign-out take effect at once for access tokens. A JWT is checked by its
 * signature alone, so without this a token copied before logout keeps working
 * until it expires (up to app.jwt.ttl-minutes).
 *
 * <p>Revoking an account stores a cut-off time in Redis. Any token for that
 * account issued before the cut-off is ignored by JwtAuthFilter, so the caller is
 * treated as signed out. Tokens issued from that moment on (a fresh sign-in, or
 * the new session handed out after a password change) still work. The key only
 * has to outlive the longest access token, so it expires after the token TTL.
 *
 * <p>Fail-open, like SessionActivityService: if Redis is down a token is not
 * rejected, so an outage cannot sign everyone out. The refresh token is still
 * revoked in the database, so the session ends at the next renewal anyway.
 */
@Component
public class AccessTokenRevocations {

    private static final Logger LOG = Logger.getLogger(AccessTokenRevocations.class.getName());
    private static final String KEY_PREFIX = "rapidstylers:jwt:revoked-before:";

    private final StringRedisTemplate redisTemplate;

    @Value("${app.jwt.ttl-minutes:15}")
    private long ttlMinutes;

    public AccessTokenRevocations(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Every access token this account holds right now stops working. Never throws. */
    public void revokeAll(String accountId, String role) {
        try {
            redisTemplate.opsForValue().set(key(accountId, role), String.valueOf(System.currentTimeMillis()),
                    Duration.ofMinutes(ttlMinutes + 1));
        } catch (Exception ex) {
            ThrottledLog.warnOncePerWindow(LOG, "jwt/revoke",
                    "Access token revoke failed for " + accountId + ": " + ex.getMessage());
        }
    }

    /** True when the token was issued before this account was last revoked. */
    public boolean isRevoked(String accountId, String role, Date issuedAt) {
        if (issuedAt == null) {
            return false;
        }
        try {
            String cutoff = redisTemplate.opsForValue().get(key(accountId, role));
            if (cutoff == null || cutoff.isBlank()) {
                return false;
            }
            return issuedAt.getTime() < Long.parseLong(cutoff.trim());
        } catch (Exception ex) {
            ThrottledLog.warnOncePerWindow(LOG, "jwt/revoke-read",
                    "Access token revoke check failed for " + accountId + ": " + ex.getMessage());
            return false;
        }
    }

    private static String key(String accountId, String role) {
        return KEY_PREFIX + role + ":" + accountId;
    }
}

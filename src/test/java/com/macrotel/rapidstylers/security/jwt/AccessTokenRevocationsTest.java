package com.macrotel.rapidstylers.security.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A logout stores a cut-off time; tokens issued before it are revoked, tokens
 * issued at that moment or later are not, and a Redis failure never revokes.
 */
class AccessTokenRevocationsTest {

    private static final String KEY = "rapidstylers:jwt:revoked-before:CUSTOMER:CUST1";

    private ValueOperations<String, String> values;
    private AccessTokenRevocations revocations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        revocations = new AccessTokenRevocations(redis);
        ReflectionTestUtils.setField(revocations, "ttlMinutes", 15L);
    }

    @Test
    void revokeStoresTheCutoffForAsLongAsATokenLives() {
        revocations.revokeAll("CUST1", "CUSTOMER");

        verify(values).set(eq(KEY), anyString(), eq(Duration.ofMinutes(16)));
    }

    @Test
    void tokenIssuedBeforeTheCutoffIsRevoked() {
        when(values.get(KEY)).thenReturn("1000000");

        assertTrue(revocations.isRevoked("CUST1", "CUSTOMER", new Date(999_999L)));
    }

    @Test
    void tokenIssuedAtTheCutoffOrLaterStillWorks() {
        when(values.get(KEY)).thenReturn("1000000");

        assertFalse(revocations.isRevoked("CUST1", "CUSTOMER", new Date(1_000_000L)),
                "the fresh session handed out right after a password change must work");
        assertFalse(revocations.isRevoked("CUST1", "CUSTOMER", new Date(1_000_001L)));
    }

    @Test
    void neverRevokedAccountIsNotRevoked() {
        assertFalse(revocations.isRevoked("CUST1", "CUSTOMER", new Date(999_999L)));
    }

    @Test
    void redisFailureDoesNotSignEveryoneOut() {
        when(values.get(KEY)).thenThrow(new IllegalStateException("redis down"));

        assertFalse(revocations.isRevoked("CUST1", "CUSTOMER", new Date(999_999L)));
    }
}

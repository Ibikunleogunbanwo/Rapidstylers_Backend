package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionActivityServiceTest {

    private static final String KEY = "rapidstylers:session:activity:acc1";
    private static final String START_KEY = "rapidstylers:session:start:acc1";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SessionActivityService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new SessionActivityService(redisTemplate);
        ReflectionTestUtils.setField(service, "customerIdleMinutes", 60L);
        ReflectionTestUtils.setField(service, "stylerIdleMinutes", 30L);
        ReflectionTestUtils.setField(service, "adminIdleMinutes", 30L);
        ReflectionTestUtils.setField(service, "customerAbsoluteHours", 0L);
        ReflectionTestUtils.setField(service, "stylerAbsoluteHours", 0L);
        ReflectionTestUtils.setField(service, "adminAbsoluteHours", 8L);
    }

    private void activityAt(long epochMillis) {
        when(valueOps.get(KEY)).thenReturn(String.valueOf(epochMillis));
    }

    @Test
    void touchWritesCurrentTimestampForTheAccount() {
        service.touch("acc1", "CUSTOMER");
        verify(valueOps).set(eq(KEY), anyString(), any(Duration.class));
    }

    @Test
    void freshActivityIsNeverIdle() {
        activityAt(System.currentTimeMillis() - 60_000L); // 1 min ago
        assertFalse(service.isIdle("acc1", "CUSTOMER"));
    }

    @Test
    void activityPastTheRoleWindowIsIdle() {
        activityAt(System.currentTimeMillis() - 61 * 60_000L); // 61 min ago
        assertTrue(service.isIdle("acc1", "CUSTOMER"));
    }

    @Test
    void roleWindowsDiffer() {
        // Inside every window: nobody is idle.
        activityAt(System.currentTimeMillis() - 20 * 60_000L);
        assertFalse(service.isIdle("acc1", "STYLER"));
        assertFalse(service.isIdle("acc1", "ADMIN"));
        assertFalse(service.isIdle("acc1", "CUSTOMER"));
        // Past the 30-min styler/admin windows but inside the 60-min customer one.
        activityAt(System.currentTimeMillis() - 45 * 60_000L);
        assertTrue(service.isIdle("acc1", "STYLER"));
        assertTrue(service.isIdle("acc1", "ADMIN"));
        assertFalse(service.isIdle("acc1", "CUSTOMER"));
    }

    @Test
    void missingKeyTreatsTheSessionAsActive() {
        when(valueOps.get(KEY)).thenReturn(null);
        assertFalse(service.isIdle("acc1", "CUSTOMER"));
    }

    @Test
    void nonNumericValueFailsOpen() {
        activityAt(0L);
        when(valueOps.get(KEY)).thenReturn("not-a-timestamp");
        assertFalse(service.isIdle("acc1", "CUSTOMER"));
    }

    @Test
    void redisErrorFailsOpenInsteadOfLockingOut() {
        when(valueOps.get(KEY)).thenThrow(new RuntimeException("redis down"));
        assertFalse(service.isIdle("acc1", "CUSTOMER"));
        // touch must swallow errors too and never throw on the request path.
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        service.touch("acc1", "CUSTOMER");
    }

    @Test
    void clearRemovesTheActivityKey() {
        service.clear("acc1");
        verify(redisTemplate).delete(KEY);
        verify(redisTemplate).delete(START_KEY);
    }

    // ---- Absolute (hard) session cap, enabled for admins ----

    @Test
    void markLoginWritesTheSessionStartAnchor() {
        service.markLogin("acc1", "ADMIN");
        verify(valueOps).set(eq(START_KEY), anyString(), any(Duration.class));
    }

    @Test
    void activeAdminIsNotPastTheAbsoluteCap() {
        when(valueOps.get(START_KEY)).thenReturn(String.valueOf(System.currentTimeMillis() - 3600_000L)); // 1h ago
        assertFalse(service.absoluteExpired("acc1", "ADMIN"));
    }

    @Test
    void adminSessionPastEightHoursIsExpired() {
        when(valueOps.get(START_KEY)).thenReturn(String.valueOf(System.currentTimeMillis() - 9 * 3600_000L)); // 9h ago
        assertTrue(service.absoluteExpired("acc1", "ADMIN"));
    }

    @Test
    void absoluteCapIsDisabledForRolesWithoutOne() {
        assertFalse(service.absoluteExpired("acc1", "CUSTOMER"));
        assertFalse(service.absoluteExpired("acc1", "STYLER"));
    }

    @Test
    void absoluteCapMissFailsOpen() {
        when(valueOps.get(START_KEY)).thenReturn(null);
        assertFalse(service.absoluteExpired("acc1", "ADMIN"));
    }
}
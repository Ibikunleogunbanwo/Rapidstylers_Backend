package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationDedupServiceTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> values;
    private NotificationDedupService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        service = new NotificationDedupService(redisTemplate);
        ReflectionTestUtils.setField(service, "ttlHours", 24L);
    }

    @Test
    void firstDeliveryClaimsAndProcesses() {
        when(values.setIfAbsent("notif:delivered:event-1", "1", Duration.ofHours(24))).thenReturn(true);
        assertTrue(service.tryClaim("event-1"));
    }

    @Test
    void redeliveredEventIsSkipped() {
        when(values.setIfAbsent("notif:delivered:event-1", "1", Duration.ofHours(24))).thenReturn(false);
        assertFalse(service.tryClaim("event-1"));
    }

    @Test
    void missingEventIdAlwaysProcesses() {
        assertTrue(service.tryClaim(null));
        assertTrue(service.tryClaim(" "));
        verifyNoInteractions(values);
    }

    @Test
    void releaseRemovesClaim() {
        service.release("event-1");
        verify(redisTemplate).delete("notif:delivered:event-1");
    }

    @Test
    void redisFailureDegradesToProcessAnyway() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        assertTrue(service.tryClaim("event-1"));
        service.release("event-1"); // must not throw
    }

    @Test
    void redisFailureIsCountedSoDegradationIsObservable() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertTrue(service.tryClaim("event-1"));
        assertTrue(service.tryClaim("event-2"));

        assertEquals(2L, service.degradations(),
                "every dedup outage must be counted so ops can see duplicate-mail risk");
    }
}

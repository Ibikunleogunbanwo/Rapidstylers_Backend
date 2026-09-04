package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        service = new IdempotencyService(redisTemplate);
    }

    @Test
    void missingKeyDoesNotClaim() {
        IdempotencyService.Claim claim = service.claim("booking", "USER1", " ", Duration.ofHours(1));
        assertFalse(claim.isProvided());
        verifyNoInteractions(values);
    }

    @Test
    void firstKeyClaimIsAcquired() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        IdempotencyService.Claim claim = service.claim("booking", "USER1", "KEY1", Duration.ofHours(1));
        assertTrue(claim.isAcquired());
        assertFalse(claim.isDuplicate());
    }

    @Test
    void existingKeyIsDuplicate() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        IdempotencyService.Claim claim = service.claim("booking", "USER1", "KEY1", Duration.ofHours(1));
        assertTrue(claim.isDuplicate());
        assertFalse(claim.isAcquired());
    }

    @Test
    void releaseOnlyDeletesOurMarker() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        IdempotencyService.Claim claim = service.claim("booking", "USER1", "KEY1", Duration.ofHours(1));
        when(values.get(anyString())).thenReturn("not-our-marker");
        service.release(claim);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void releaseSurvivesRedisErrors() {
        when(values.get(anyString())).thenThrow(new RuntimeException("redis down"));
        IdempotencyService.Claim claim = IdempotencyService.Claim.acquired("idempotency:x", "marker");
        service.release(claim); // must not throw
        verify(redisTemplate, never()).delete(anyString());
    }
}

package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> values;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
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
}

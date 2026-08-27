package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the Redis idempotency claim mechanism used to prevent
 * double-booking when the same client retries a booking request.
 *
 * Real concurrent slot protection is enforced by the DB unique constraint
 * on booking_slot_locks(styler_id, appointment_date, slot_start) combined
 * with the pessimistic SELECT FOR UPDATE lock on the stylist row.
 */
class ConcurrentBookingTest {

    private IdempotencyService idempotencyService;
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOps;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        idempotencyService = new IdempotencyService(redisTemplate);
    }

    @Test
    void firstClaimWins_secondClaimIsDuplicate() {
        String idempotencyKey = UUID.randomUUID().toString();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true)   // first claim
                .thenReturn(false); // second claim

        IdempotencyService.Claim first = idempotencyService.claim(
                "book-appointment", "CUST-A", idempotencyKey, Duration.ofHours(24));
        IdempotencyService.Claim second = idempotencyService.claim(
                "book-appointment", "CUST-A", idempotencyKey, Duration.ofHours(24));

        assertTrue(first.isAcquired());
        assertFalse(first.isDuplicate());
        assertFalse(second.isAcquired());
        assertTrue(second.isDuplicate());
    }

    @Test
    void differentCustomersCanBookSameSlot() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        IdempotencyService.Claim claimA = idempotencyService.claim(
                "book-appointment", "CUST-A", UUID.randomUUID().toString(), Duration.ofHours(24));
        IdempotencyService.Claim claimB = idempotencyService.claim(
                "book-appointment", "CUST-B", UUID.randomUUID().toString(), Duration.ofHours(24));

        assertTrue(claimA.isAcquired());
        assertTrue(claimB.isAcquired());
    }

    @Test
    void sameCustomerDifferentKeysAreBothValid() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        IdempotencyService.Claim claim1 = idempotencyService.claim(
                "book-appointment", "CUST-A", UUID.randomUUID().toString(), Duration.ofHours(24));
        IdempotencyService.Claim claim2 = idempotencyService.claim(
                "book-appointment", "CUST-A", UUID.randomUUID().toString(), Duration.ofHours(24));

        assertTrue(claim1.isAcquired());
        assertTrue(claim2.isAcquired());
    }

    @Test
    void emptyIdempotencyKeyReturnsNotProvided() {
        IdempotencyService.Claim claim = idempotencyService.claim(
                "book-appointment", "CUST-A", "", Duration.ofHours(24));

        assertFalse(claim.isProvided());
        assertFalse(claim.isAcquired());
        assertFalse(claim.isDuplicate());
    }

    @Test
    void responseReplay_isStoredAndRetrieved() {
        String key = "idempotency:book-appointment:CUST-A:" + UUID.randomUUID();
        String responseJson = "{\"statusCode\":\"200\",\"message\":\"Booked\"}";

        when(valueOps.setIfAbsent(eq(key), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(valueOps.get(key.replace("idempotency:", "idempotency:response:")))
                .thenReturn(responseJson);

        // Store response
        idempotencyService.storeResponse("book-appointment", "CUST-A",
                key.replace("idempotency:book-appointment:CUST-A:", ""), responseJson);

        // Retrieve response
        String stored = idempotencyService.getStoredResponse("book-appointment", "CUST-A",
                key.replace("idempotency:book-appointment:CUST-A:", ""));

        assertEquals(responseJson, stored);
    }
}

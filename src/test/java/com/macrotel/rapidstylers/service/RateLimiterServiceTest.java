package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimiterServiceTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOperations;
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        rateLimiterService = new RateLimiterService(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void recordIncrementsRedisCounterAndExpiresTheKeyForTheWindow() {
        when(valueOperations.increment("auth:user@example.com")).thenReturn(1L);

        rateLimiterService.record("auth:user@example.com", 900);

        verify(valueOperations).increment("auth:user@example.com");
        verify(redisTemplate).expire("auth:user@example.com", 900, TimeUnit.SECONDS);
    }

    @Test
    void isBlockedUsesRedisCounterValue() {
        when(valueOperations.get("auth:user@example.com")).thenReturn("5");

        assertTrue(rateLimiterService.isBlocked("auth:user@example.com", 900, 5));
    }

    @Test
    void missingRedisCounterIsNotBlocked() {
        when(valueOperations.get("auth:user@example.com")).thenReturn(null);

        assertFalse(rateLimiterService.isBlocked("auth:user@example.com", 900, 5));
    }

    @Test
    void clearDeletesRedisKey() {
        rateLimiterService.clear("auth:user@example.com");

        verify(redisTemplate).delete("auth:user@example.com");
    }

    // ---- In-memory fallback (Redis unavailable) -----------------------------

    private void redisDown() {
        when(valueOperations.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));
    }

    @Test
    void redisFailureFallsBackToInMemoryAndStillBlocks() {
        redisDown();

        for (int i = 0; i < 5; i++) {
            rateLimiterService.record("otp_verify:1.2.3.4", 900);
        }

        assertTrue(rateLimiterService.isBlocked("otp_verify:1.2.3.4", 900, 5),
                "Limits must be enforced from the in-memory fallback when Redis is down");
        assertFalse(rateLimiterService.isBlocked("otp_verify:1.2.3.4", 900, 6),
                "A count below the max must not block");

        rateLimiterService.clear("otp_verify:1.2.3.4");
        assertFalse(rateLimiterService.isBlocked("otp_verify:1.2.3.4", 900, 5),
                "clear() must forget in-memory attempts too");
    }

    @Test
    void inMemoryWindowExpiryResetsCounter() throws Exception {
        redisDown();

        for (int i = 0; i < 5; i++) {
            rateLimiterService.record("auth:someone@example.com", 1);
        }
        assertTrue(rateLimiterService.isBlocked("auth:someone@example.com", 1, 5));

        Thread.sleep(1_100L);
        assertFalse(rateLimiterService.isBlocked("auth:someone@example.com", 1, 5),
                "Expired in-memory window must not keep blocking");

        rateLimiterService.record("auth:someone@example.com", 1);
        assertFalse(rateLimiterService.isBlocked("auth:someone@example.com", 1, 5),
                "A fresh window starts at zero attempts");
    }

    // ---- Client IP resolution / trusted proxies -----------------------------

    private void setRequest(String remoteAddr, String xff) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader("X-Forwarded-For")).thenReturn(xff);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void untrustedPeerIgnoresSpoofedXForwardedFor() {
        rateLimiterService.trustedProxies = RateLimiterService.parseTrustedProxies("");

        setRequest("203.0.113.9", "6.6.6.6");
        assertEquals("203.0.113.9", rateLimiterService.clientIp(),
                "A direct client spoofing X-Forwarded-For must be keyed by socket address");
    }

    @Test
    void trustedProxyUsesRightmostUntrustedXffEntry() {
        rateLimiterService.trustedProxies = RateLimiterService.parseTrustedProxies("1.1.1.1, 10.0.0.0/8");

        setRequest("1.1.1.1", "203.0.113.9, 10.0.0.5");
        assertEquals("203.0.113.9", rateLimiterService.clientIp(),
                "Walk right-to-left, skipping trusted proxies, to find the real client");
    }

    @Test
    void chainOfOnlyTrustedProxiesFallsBackToPeer() {
        rateLimiterService.trustedProxies = RateLimiterService.parseTrustedProxies("1.1.1.1, 10.0.0.0/8");

        setRequest("1.1.1.1", "10.0.0.5");
        assertEquals("1.1.1.1", rateLimiterService.clientIp());
    }

    @Test
    void cidrRangeParsingMatchesV4AndV6() throws Exception {
        List<RateLimiterService.CidrRange> ranges = RateLimiterService.parseTrustedProxies("173.245.48.0/20, 10.0.0.1, 2a06:98c0::/29");

        assertTrue(ranges.size() == 3);

        // IPv4 /20: 173.245.48.0 - 173.245.63.255
        assertTrue(ranges.get(0).matches(InetAddress.getByName("173.245.50.1").getAddress()));
        assertFalse(ranges.get(0).matches(InetAddress.getByName("173.245.64.1").getAddress()));

        // Exact IPv4
        assertTrue(ranges.get(1).matches(InetAddress.getByName("10.0.0.1").getAddress()));
        assertFalse(ranges.get(1).matches(InetAddress.getByName("10.0.0.2").getAddress()));

        // IPv6 /29
        assertTrue(ranges.get(2).matches(InetAddress.getByName("2a06:98c0:0:1::1").getAddress()));
        assertFalse(ranges.get(2).matches(InetAddress.getByName("2a07::1").getAddress()));
    }
}

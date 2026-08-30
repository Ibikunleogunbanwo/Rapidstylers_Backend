package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the read-through cache mechanics: hit/miss/store, single-flight
 * coalescing, jittered TTL bounds, eviction, and graceful degradation when Redis
 * fails (the cache must never be a hard dependency).
 */
class ReadCacheServiceTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> ops;
    private ReadCacheService cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        cache = new ReadCacheService(redisTemplate);
    }

    @Test
    void hitReturnsCachedValueWithoutInvokingLoader() {
        when(ops.get("k")).thenReturn("cached");
        AtomicInteger loads = new AtomicInteger();

        Object result = cache.getOrLoad("k", Duration.ofMinutes(1), null, () -> {
            loads.incrementAndGet();
            return "loaded";
        });

        assertEquals("cached", result);
        assertEquals(0, loads.get());
        verify(ops, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void missLoadsOnceAndStoresWithJitteredTtlWithinBounds() {
        when(ops.get("k")).thenReturn(null);
        Duration ttl = Duration.ofMinutes(5);
        Duration jitter = Duration.ofMinutes(1);

        Object result = cache.getOrLoad("k", ttl, jitter, () -> "loaded");

        assertEquals("loaded", result);
        org.mockito.ArgumentCaptor<Duration> captor =
                org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("k"), eq("loaded"), captor.capture());
        Duration stored = captor.getValue();
        assertTrue(stored.compareTo(ttl) >= 0, "stored TTL must not be below base TTL");
        assertTrue(stored.compareTo(ttl.plus(jitter)) <= 0, "stored TTL must not exceed base + jitter");
    }

    @Test
    void nullLoaderResultIsNotCached() {
        when(ops.get("k")).thenReturn(null);

        assertNull(cache.getOrLoad("k", Duration.ofMinutes(1), null, () -> null));

        verify(ops, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void concurrentMissesShareSingleLoad() throws Exception {
        when(ops.get("k")).thenReturn(null);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Object> first = pool.submit(() ->
                    cache.getOrLoad("k", Duration.ofMinutes(1), null, () -> {
                        loads.incrementAndGet();
                        loaderEntered.countDown();
                        await(releaseLoader);
                        return "loaded";
                    }));
            assertTrue(loaderEntered.await(5, TimeUnit.SECONDS), "loader should have started");

            java.util.concurrent.Future<Object> second = pool.submit(() ->
                    cache.getOrLoad("k", Duration.ofMinutes(1), null, () -> {
                        loads.incrementAndGet();
                        return "loaded";
                    }));

            // Give the second caller time to arrive and join the in-flight future.
            Thread.sleep(300);
            releaseLoader.countDown();

            assertEquals("loaded", first.get(5, TimeUnit.SECONDS));
            assertEquals("loaded", second.get(5, TimeUnit.SECONDS));
            assertEquals(1, loads.get(), "single-flight must collapse concurrent misses to one load");
        } finally {
            releaseLoader.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void slowLoaderTimesOutButIsNotRunTwice() {
        when(ops.get("k")).thenReturn(null);
        ReflectionTestUtils.setField(cache, "singleFlightTimeoutSeconds", 1L);
        AtomicInteger loads = new AtomicInteger();
        long start = System.currentTimeMillis();

        // Loader takes 1.5s; the first single-flight wait times out at 1s. The
        // caller must keep waiting for the in-flight result instead of running
        // the loader a second time (which would double DB load on slow paths).
        Object result = cache.getOrLoad("k", Duration.ofMinutes(1), null, () -> {
            loads.incrementAndGet();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1500));
            return "loaded";
        });

        assertEquals("loaded", result);
        assertEquals(1, loads.get(), "a slow-but-successful loader must never run twice");
        assertTrue(System.currentTimeMillis() - start >= 1500,
                "the caller must wait for the in-flight result rather than reload");
    }

    @Test
    void redisFailureDegradesToDirectLoadWithoutThrowing() {
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));

        Object result = cache.getOrLoad("k", Duration.ofMinutes(1), null, () -> "from-db");

        assertEquals("from-db", result);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void evictDeletesEveryProvidedKey() {
        cache.evict("a", "b", null);

        verify((RedisTemplate) redisTemplate).delete("a");
        verify((RedisTemplate) redisTemplate).delete("b");
        verify((RedisTemplate) redisTemplate, atMostOnce()).delete(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void evictSwallowsRedisFailures() {
        when(((RedisTemplate) redisTemplate).delete(anyString())).thenThrow(new RuntimeException("redis down"));

        cache.evict("a"); // must not throw
    }

    @Test
    void statsReportHitsMissesLoadsPerKeyAndHitRate() {
        when(ops.get("hit")).thenReturn("cached");
        when(ops.get("miss")).thenReturn(null);

        assertEquals("cached", cache.getOrLoad("hit", Duration.ofMinutes(1), null, () -> "db"));
        assertEquals("loaded", cache.getOrLoad("miss", Duration.ofMinutes(1), null, () -> "loaded"));

        java.util.Map<String, Object> stats = cache.stats();
        assertEquals(1L, stats.get("hits"));
        assertEquals(1L, stats.get("misses"));
        assertEquals(1L, stats.get("loads"));
        assertEquals(0L, stats.get("evictions"));
        assertEquals(0L, stats.get("keysDroppedAtCap"));
        assertEquals(0.5, (Double) stats.get("overallHitRate"), 0.0001);
        assertEquals(2L, stats.get("keysTracked"));
        assertEquals(2, ((java.util.List<?>) stats.get("topKeys")).size());
    }

    @Test
    void statsCountEvictions() {
        cache.evict("a", "b");

        assertEquals(0L, cache.stats().get("hits"));
        assertEquals(2L, cache.stats().get("evictions"));
    }

    @Test
    void redisReadFailureIncrementsDegradationCounterPerOccurrence() {
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));

        // every degraded call must count, not just the first
        cache.getOrLoad("styler:dto:GS5816", Duration.ofMinutes(1), null, () -> "db1");
        cache.getOrLoad("styler:dto:GS5816", Duration.ofMinutes(1), null, () -> "db2");
        cache.getOrLoad("catalog:services", Duration.ofMinutes(1), null, () -> "db3");

        java.util.Map<String, Object> stats = cache.stats();
        assertEquals(3L, stats.get("degradations"), "each degraded call must be counted");
    }

    @Test
    void redisWriteFailureIncrementsDegradationCounter() {
        when(ops.get(anyString())).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("redis full")).when(ops).set(anyString(), any(), any(Duration.class));

        cache.getOrLoad("styler:availability:GS5816", Duration.ofMinutes(1), null, () -> "loaded");

        assertEquals(1L, cache.stats().get("degradations"));
    }

    @Test
    void redisEvictFailureIncrementsDegradationCounter() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis down")).when(((RedisTemplate) redisTemplate)).delete(anyString());

        cache.evict("styler:portfolio:GS5816");
        cache.evict("catalog:services");

        assertEquals(2L, cache.stats().get("degradations"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

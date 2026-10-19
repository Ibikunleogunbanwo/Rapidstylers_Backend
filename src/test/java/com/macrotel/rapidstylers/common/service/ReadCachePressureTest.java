package com.macrotel.rapidstylers.common.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadCachePressureTest {
    private RedisTemplate<String, Object> redis;
    private ReadCacheService cache;
    private final ExecutorService callers = Executors.newCachedThreadPool();
    private final Duration ttl = Duration.ofMinutes(1);
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(eq(ReadCacheService.GENERATION), anyList(), anyString())).thenReturn("g");
        cache = new ReadCacheService(redis);
    }
    @AfterEach void cleanup() { callers.shutdownNow(); cache.shutdown(); }

    @Test void redisOutageStillCoalescesConcurrentReadsForTheSameKey() throws Exception {
        CountDownLatch probed = new CountDownLatch(2), entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(redis.execute(eq(ReadCacheService.GENERATION), anyList(), anyString())).thenAnswer(call -> {
            probed.countDown(); throw new IllegalStateException("Redis unavailable");
        });
        AtomicInteger loads = new AtomicInteger();
        try {
            Future<String> first = callers.submit(() -> cache.getOrLoad("same", ttl, null, () -> {
                loads.incrementAndGet(); entered.countDown(); await(release); return "db";
            }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Future<String> second = callers.submit(() -> cache.getOrLoad("same", ttl, null, () -> { loads.incrementAndGet(); return "duplicate"; }));
            assertTrue(probed.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals("db", first.get(3, TimeUnit.SECONDS));
            assertEquals("db", second.get(3, TimeUnit.SECONDS));
            assertEquals(1, loads.get());
        } finally { release.countDown(); }
    }

    @Test void timedOutWaiterDoesNotLaunchAnotherDatabaseLoadOrRemoveTheFlight() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        try {
            Future<String> first = callers.submit(() -> cache.getOrLoad("slow", ttl, null, () -> {
                loads.incrementAndGet(); entered.countDown(); await(release); return "db";
            }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            ReflectionTestUtils.setField(cache, "singleFlightTimeoutSeconds", 0L);
            assertThrows(RuntimeException.class, () -> cache.getOrLoad("slow", ttl, null, () -> { loads.incrementAndGet(); return "duplicate"; }));
            assertEquals(1, loads.get());
            ReflectionTestUtils.setField(cache, "singleFlightTimeoutSeconds", 5L);
            release.countDown();
            assertEquals("db", first.get(3, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test void saturatedQueueRejectsWithoutLoadingOnTheRequestThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(8), release = new CountDownLatch(1);
        List<Future<String>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 72; i++) {
                final String key = "key-" + i;
                pending.add(callers.submit(() -> cache.getOrLoad(key, ttl, null, () -> {
                    entered.countDown(); await(release); return "db";
                })));
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            ThreadPoolExecutor workers = (ThreadPoolExecutor) ReflectionTestUtils.getField(cache, "loaders");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (workers.getQueue().size() < 64 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(64, workers.getQueue().size());
            ReflectionTestUtils.setField(cache, "singleFlightTimeoutSeconds", 0L);
            AtomicInteger bypasses = new AtomicInteger();
            assertThrows(RuntimeException.class, () -> cache.getOrLoad("overflow", ttl, null, () -> { bypasses.incrementAndGet(); return "bypass"; }));
            assertEquals(0, bypasses.get(), "overload must not bypass the worker limit");
            assertEquals(64, workers.getQueue().size(), "overflow must not grow the queue");
        } finally {
            release.countDown();
            for (Future<String> future : pending) future.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void transactionalReadsShareTheSameDatabaseConcurrencyBudget() throws Exception {
        CountDownLatch entered = new CountDownLatch(8), release = new CountDownLatch(1);
        List<Future<String>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                final String key = "tx-" + i;
                pending.add(callers.submit(() -> {
                    org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
                    try { return cache.getOrLoad(key, ttl, null, () -> { entered.countDown(); await(release); return "db"; }); }
                    finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
                }));
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            AtomicInteger bypasses = new AtomicInteger();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThrows(RuntimeException.class, () -> cache.getOrLoad("tx-overflow", ttl, null,
                        () -> { bypasses.incrementAndGet(); return "bypass"; }));
            } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
            assertEquals(0, bypasses.get());
        } finally {
            release.countDown();
            for (Future<String> future : pending) future.get(5, TimeUnit.SECONDS);
        }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
}

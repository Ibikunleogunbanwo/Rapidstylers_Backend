package com.macrotel.rapidstylers.common.service;
import com.macrotel.rapidstylers.config.RedisConfig;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Redis regression coverage; explicitly requires a dedicated test instance. */
class ReadCacheConsistencyTest {
    private LettuceConnectionFactory factory;
    private RedisTemplate<String, Object> redis;
    private ReadCacheService reader, writer;
    private final String prefix = "cache-consistency-test:" + UUID.randomUUID() + ":";
    private final Duration ttl = Duration.ofMinutes(1);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final TransactionTemplate tx = new TransactionTemplate(new AbstractPlatformTransactionManager() {
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) { }
        protected void doCommit(DefaultTransactionStatus status) { }
        protected void doRollback(DefaultTransactionStatus status) { }
    });
    @BeforeEach void setup() {
        String port = System.getenv("REDIS_CACHE_TEST_PORT");
        assumeTrue(port != null, "Set REDIS_CACHE_TEST_PORT to a dedicated test Redis");
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(port)); factory.afterPropertiesSet();
        redis = new RedisConfig().redisTemplate(factory);
        reader = new ReadCacheService(redis); writer = new ReadCacheService(redis);
    }
    @AfterEach void cleanup() {
        pool.shutdownNow();
        if (reader != null) reader.shutdown(); if (writer != null) writer.shutdown();
        if (redis != null) { java.util.Set<String> keys = redis.keys(prefix + "*"); if (!keys.isEmpty()) redis.delete(keys); }
        if (factory != null) factory.destroy();
    }
    @Test void exactEvictionWaitsForCommitAndRollbackKeepsCachedData() {
        String key = prefix + "profile"; redis.opsForValue().set(key, "old", ttl);
        tx.execute(status -> { writer.evict(key); assertEquals("old", redis.opsForValue().get(key), "must not evict before commit"); return null; });
        assertNull(redis.opsForValue().get(key)); redis.opsForValue().set(key, "unchanged", ttl);
        tx.execute(status -> { writer.evict(key); status.setRollbackOnly(); return null; });
        assertEquals("unchanged", redis.opsForValue().get(key));
    }
    @Test void prefixEvictionWaitsForCommit() {
        redis.opsForValue().set(prefix + "a", "old", ttl);
        tx.execute(status -> { writer.evictPrefix(prefix); assertEquals("old", redis.opsForValue().get(prefix + "a")); return null; });
        assertNull(redis.opsForValue().get(prefix + "a"));
    }
    @Test void oldLoadCannotRepopulateAfterAnotherInstanceEvicts() throws Exception { race(false); }
    @Test void prefixEvictionFencesLoadsForKeysNotYetCached() throws Exception { race(true); }
    private void race(boolean byPrefix) throws Exception {
        String key = prefix + "profile";
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<String> old = pool.submit(() -> reader.getOrLoad(key, ttl, null, () -> { entered.countDown(); await(release); return "old"; }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            if (byPrefix) writer.evictPrefix(prefix); else writer.evict(key);
            assertEquals("new", reader.getOrLoad(key, ttl, null, () -> "new"), "post-invalidation reader must not join the old flight");
            release.countDown(); assertEquals("old", old.get(3, TimeUnit.SECONDS));
            assertEquals("new", redis.opsForValue().get(key), "old load must not overwrite new cache value");
        } finally { release.countDown(); }
    }
    @Test void transactionReadsBypassCacheAndCannotPublishUncommittedData() {
        String key = prefix + "profile"; redis.opsForValue().set(key, "cached", ttl);
        tx.execute(status -> { assertEquals("database", reader.getOrLoad(key, ttl, null, () -> "database")); status.setRollbackOnly(); return null; });
        assertEquals("cached", redis.opsForValue().get(key));
    }
    @Test void transactionalMissDoesNotPublishUncommittedData() {
        String key = prefix + "transaction-miss";
        tx.execute(status -> {
            assertEquals("uncommitted", reader.getOrLoad(key, ttl, null, () -> "uncommitted"));
            status.setRollbackOnly();
            return null;
        });
        assertNull(redis.opsForValue().get(key));
        assertEquals("committed", reader.getOrLoad(key, ttl, null, () -> "committed"));
    }
    @Test void cachedValuesRetainTheirTypesAndExpiration() {
        String key = prefix + "typed";
        java.util.Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("name", "QA stylist");
        assertEquals(expected, reader.getOrLoad(key, ttl, Duration.ofSeconds(10), () -> expected));
        assertEquals(expected, redis.opsForValue().get(key));
        assertEquals(expected, reader.getOrLoad(key, ttl, null, () -> { fail("cache hit must not reload"); return null; }));
        Long remaining = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertNotNull(remaining);
        assertTrue(remaining > 0 && remaining <= 70_000L);
    }
    @Test void prefixEvictionDoesNotRunOnRollback() {
        String key = prefix + "rollback";
        redis.opsForValue().set(key, "unchanged", ttl);
        tx.execute(status -> { writer.evictPrefix(prefix); status.setRollbackOnly(); return null; });
        assertEquals("unchanged", redis.opsForValue().get(key));
    }
    @Test void missingGenerationCannotBeReusedByAnOlderLoader() throws Exception {
        String key = prefix + "generation-reset";
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<String> old = pool.submit(() -> reader.getOrLoad(key, ttl, null, () -> {
                entered.countDown(); await(release); return "old";
            }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            // Model Redis losing only the coordination key while a loader is running.
            redis.delete("rapidstylers:read-cache:generation");
            release.countDown();
            assertEquals("old", old.get(3, TimeUnit.SECONDS));
            assertNull(redis.opsForValue().get(key));
            assertEquals("new", writer.getOrLoad(key, ttl, null, () -> "new"));
            assertEquals("new", redis.opsForValue().get(key));
        } finally { release.countDown(); }
    }
    @Test void confirmedMissingResultIsSharedAcrossInstancesForAShortTime() {
        String key = prefix + "missing";
        assertNull(reader.getOrLoad(key, ttl, null, () -> null));
        assertNotNull(redis.opsForValue().get(key), "a missing result needs a Redis marker");
        assertNull(writer.getOrLoad(key, ttl, null, () -> { fail("negative hit must not query MySQL"); return "unexpected"; }));
        Long remaining = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertNotNull(remaining);
        assertTrue(remaining > 0 && remaining <= 35_000L, "negative cache must expire within 35 seconds");
        writer.evict(key);
        assertEquals("published", reader.getOrLoad(key, ttl, null, () -> "published"));
    }
    @Test void failedDatabaseLoadIsNotCachedAsMissing() {
        String key = prefix + "database-failure";
        assertThrows(IllegalStateException.class, () -> reader.getOrLoad(key, ttl, null,
                () -> { throw new IllegalStateException("database unavailable"); }));
        assertNull(redis.opsForValue().get(key));
        assertEquals("recovered", writer.getOrLoad(key, ttl, null, () -> "recovered"));
    }
    @Test void anExpiredMissingMarkerAllowsTheDatabaseToBeQueriedAgain() throws Exception {
        String key = prefix + "expired-missing";
        assertNull(reader.getOrLoad(key, ttl, null, () -> null));
        assertNotNull(redis.opsForValue().get(key));
        redis.expire(key, 1, TimeUnit.MILLISECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (redis.hasKey(key) && System.nanoTime() < deadline) Thread.yield();
        assertFalse(redis.hasKey(key));
        assertEquals("now-exists", writer.getOrLoad(key, ttl, null, () -> "now-exists"));
    }
    @Test void missingLoadCannotHideAPublicationAfterInvalidation() throws Exception {
        String key = prefix + "publishing";
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<String> missing = pool.submit(() -> reader.getOrLoad(key, ttl, null, () -> {
                entered.countDown(); await(release); return null;
            }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            writer.evict(key);
            assertEquals("published", writer.getOrLoad(key, ttl, null, () -> "published"));
            release.countDown();
            assertNull(missing.get(3, TimeUnit.SECONDS));
            assertEquals("published", redis.opsForValue().get(key));
        } finally { release.countDown(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
}

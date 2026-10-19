package com.macrotel.rapidstylers.common.service;

import com.macrotel.rapidstylers.config.ThrottledLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.annotation.PreDestroy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Read-through cache for public read paths.
 *
 * Design (cache-aside + stampede defence):
 *  - Redis hit  → return the cached value.
 *  - Redis miss → per-key single-flight: concurrent misses on the same key share
 *    exactly one loader invocation, then the result is stored with a TTL plus a
 *    random positive jitter so entries do not expire in a
 *    synchronized wave. A Redis generation fences loads across invalidation;
 *    the final comparison and cache write are one atomic Lua operation.
 *  - Writes → invalidate after transaction commit; rollback keeps cached data.
 *    Transactional reads bypass the cache to avoid publishing a DB snapshot.
 *  - Degradation → any Redis failure (connection, serialization, ...) falls back
 *    to running the loader directly; the cache is a speed-up, never a dependency.
 *
 * Key namespaces are exposed as constants so eviction call sites and tests can
 * target the same keys the read paths use.
 */
@Service
public class ReadCacheService {

    // A database-wide generation also fences prefix invalidation of absent keys.
    // UUIDs avoid reusing an old generation after a Redis flush/restart.
    private static final String GENERATION_KEY = "rapidstylers:read-cache:generation";
    static final DefaultRedisScript<String> GENERATION = new DefaultRedisScript<>(
            "local g = redis.call('GET', KEYS[1]); "
            + "if not g then g = ARGV[1]; redis.call('SET', KEYS[1], g); end; return g", String.class);
    static final DefaultRedisScript<Long> STORE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end; "
            + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]); return 1", Long.class);
    static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>(
            "redis.call('SET', KEYS[1], ARGV[1]); local n = 0; "
            + "for i = 2, #KEYS do n = n + redis.call('DEL', KEYS[i]); end; return n", Long.class);
    static final DefaultRedisScript<Long> INVALIDATE_PREFIX = new DefaultRedisScript<>(
            "redis.call('SET', KEYS[1], ARGV[1]); "
            + "local keys = redis.call('KEYS', cjson.decode(ARGV[2]) .. '*'); local n = 0; "
            + "for _, k in ipairs(keys) do if k ~= KEYS[1] then n = n + redis.call('DEL', k); end; end; return n", Long.class);

    private static final Logger LOG = Logger.getLogger(ReadCacheService.class.getName());

    /** Bounded wait for the shared in-flight load before falling back. Instance field (not final) so tests can shrink it. */
    private long singleFlightTimeoutSeconds = 5;

    // Key namespaces.
    public static final String KEY_STYLER_DTO = "styler:dto:";
    public static final String KEY_STYLER_SUBSERVICES = "styler:subservices:";
    public static final String KEY_STYLER_PORTFOLIO = "styler:portfolio:";
    public static final String KEY_STYLER_REVIEWS = "styler:reviews:";
    public static final String KEY_STYLER_AVAILABILITY = "styler:availability:";
    public static final String KEY_STYLER_APPOINTMENTS = "styler:appointments:";
    public static final String KEY_SEARCH_PROVINCE = "search:province:";
    public static final String KEY_SEARCH_CITY = "search:city:";
    public static final String KEY_SEARCH_SERVICE = "search:service:";
    public static final String KEY_CATALOG_SERVICES = "catalog:services";
    public static final String KEY_CATALOG_IDENTIFICATIONS = "catalog:identifications";
    public static final String KEY_CATALOG_BLOGS = "catalog:blogs";
    public static final String KEY_CATALOG_BLOG = "catalog:blog:";

    // TTLs and positive jitter windows spread cache expiration.
    private static final Duration NOT_FOUND_TTL = Duration.ofSeconds(30);
    private static final Duration NOT_FOUND_JITTER = Duration.ofSeconds(5);
    public static final Duration CATALOG_TTL = Duration.ofMinutes(60);
    public static final Duration BLOG_TTL = Duration.ofMinutes(30);
    public static final Duration CATALOG_JITTER = Duration.ofMinutes(10);
    public static final Duration BLOG_JITTER = Duration.ofMinutes(5);
    public static final Duration DTO_TTL = Duration.ofMinutes(10);
    public static final Duration DTO_JITTER = Duration.ofMinutes(2);
    public static final Duration SEARCH_LIST_TTL = Duration.ofMinutes(5);
    public static final Duration SEARCH_LIST_JITTER = Duration.ofMinutes(1);
    public static final Duration STYLER_PART_TTL = Duration.ofMinutes(10);
    public static final Duration STYLER_PART_JITTER = Duration.ofMinutes(2);
    public static final Duration AVAILABILITY_TTL = Duration.ofMinutes(5);
    public static final Duration AVAILABILITY_JITTER = Duration.ofMinutes(1);
    public static final Duration APPOINTMENTS_TTL = Duration.ofMinutes(2);
    public static final Duration APPOINTMENTS_JITTER = Duration.ofSeconds(30);

    /**
     * Cache misses load on their own small pool. They used to run on Java's
     * shared pool, which gets one thread per spare CPU core: on a two-core VPS
     * that is a single thread, so after a deploy every stylist's first load
     * queued behind the one before it.
     */
    static final int LOADER_THREADS = 8;
    private static final String LOADER_THREAD_PREFIX = "read-cache-load-";
    private final ExecutorService loaders = newLoaderPool();
    private final Semaphore loadPermits = new Semaphore(LOADER_THREADS);
    private final ThreadLocal<Boolean> loading = ThreadLocal.withInitial(() -> false);
    private final AtomicLong invalidationSequence = new AtomicLong();
    private final AtomicLong rejectedLoads = new AtomicLong();
    private final AtomicLong timedOutWaiters = new AtomicLong();

    private final RedisTemplate<String, Object> redisTemplate;
    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong totalDegradations = new AtomicLong();

    // Effectiveness counters (in-process, cumulative since boot). Per-key rows are
    // {hits, misses}; the map is capped so tracking cost never runs away.
    private final AtomicLong totalHits = new AtomicLong();
    private final AtomicLong totalMisses = new AtomicLong();
    private final AtomicLong totalLoads = new AtomicLong();
    private final AtomicLong totalEvictions = new AtomicLong();
    private final AtomicLong keysDroppedAtCap = new AtomicLong();
    private final Map<String, long[]> perKey = new ConcurrentHashMap<>();
    private static final int PER_KEY_CAP = 10000;

    public ReadCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static ExecutorService newLoaderPool() {
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(LOADER_THREADS, LOADER_THREADS, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), runnable -> {
                    Thread thread = new Thread(runnable, LOADER_THREAD_PREFIX + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @PreDestroy
    void shutdown() {
        loaders.shutdownNow();
    }

    /**
     * Cache-aside read with single-flight on the miss path.
     * Confirmed null results use a short-lived typed marker; loader exceptions are never cached.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String key, Duration ttl, Duration jitter, Supplier<T> loader) {
        // Keep transactions on their caller's thread and outside the shared cache,
        // but subject them to the same DB concurrency budget as ordinary misses.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return loadBounded(loader);
        }
        String generation = null;
        long localSequence = invalidationSequence.get();
        try {
            generation = redisTemplate.execute(GENERATION, Collections.singletonList(GENERATION_KEY),
                    UUID.randomUUID().toString());
            if (generation == null) throw new IllegalStateException("Redis returned no cache generation");
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                record(key, true);
                return cached instanceof NullValue ? null : (T) cached;
            }
        } catch (Exception ex) {
            logDegradation(cacheName(key), ex, "read");
            generation = null;
        }
        record(key, false);
        final String snapshot = generation;
        if (loading.get()) {
            // Nested cache parts load inline under the outer load's permit.
            // Enqueuing them could deadlock all workers waiting on their own pool.
            return loadAndStore(key, ttl, jitter, loader, snapshot);
        }
        String flightKey = (snapshot == null ? "unavailable:" + localSequence : snapshot) + "\0" + key;
        CompletableFuture<Object> created = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(flightKey, created);
        CompletableFuture<Object> future = existing == null ? created : existing;
        if (existing == null) {
            try {
                loaders.execute(() -> {
                    try {
                        created.complete(loadAndStore(key, ttl, jitter, loader, snapshot));
                    } catch (Throwable ex) {
                        created.completeExceptionally(ex);
                    } finally {
                        // Only the owner may remove this flight. Publish it before
                        // starting work so even an immediate completion cleans up.
                        inFlight.remove(flightKey, created);
                    }
                });
            } catch (RejectedExecutionException ex) {
                rejectedLoads.incrementAndGet();
                created.completeExceptionally(new CacheLoadUnavailableException());
                inFlight.remove(flightKey, created);
            }
        }
        try {
            return (T) future.get(singleFlightTimeoutSeconds * 2, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            // The loader keeps its permit and flight until it really finishes.
            // A timed-out caller must never start another database query.
            timedOutWaiters.incrementAndGet();
            throw new CacheLoadUnavailableException();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CacheLoadUnavailableException();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException) throw (RuntimeException) ex.getCause();
            throw new IllegalStateException("Cache load failed", ex.getCause());
        }
    }

    private <T> T loadAndStore(String key, Duration ttl, Duration jitter, Supplier<T> loader, String generation) {
        T value = loadBounded(loader);
        // An outage fallback never publishes a value without a Redis generation.
        if (generation != null) {
            if (value == null) {
                // Spring's typed marker is supported by the JSON serializer and
                // cannot be confused with legitimate strings, maps, or empty lists.
                store(key, NOT_FOUND_TTL, NOT_FOUND_JITTER, NullValue.INSTANCE, generation);
            } else {
                store(key, ttl, jitter, value, generation);
            }
        }
        return value;
    }

    private <T> T loadBounded(Supplier<T> loader) {
        if (loading.get()) return countLoad(loader).get();
        if (!loadPermits.tryAcquire()) {
            rejectedLoads.incrementAndGet();
            throw new CacheLoadUnavailableException();
        }
        loading.set(true);
        try {
            return countLoad(loader).get();
        } finally {
            loading.remove();
            loadPermits.release();
        }
    }

    /** Invalidate only once the database commit is visible; rollback leaves cache intact. */
    public void evict(String... keys) {
        if (keys == null) return;
        List<String> targets = new ArrayList<>();
        targets.add(GENERATION_KEY);
        for (String key : keys) if (key != null) targets.add(key);
        if (targets.size() == 1) return;
        afterCommit(() -> {
            invalidationSequence.incrementAndGet();
            try {
                redisTemplate.execute(INVALIDATE, targets, UUID.randomUUID().toString());
                totalEvictions.addAndGet(targets.size() - 1);
            } catch (Exception ex) {
                logDegradation("read-cache", ex, "evict");
            }
        });
    }

    /** Prefix eviction also fences loaders whose values are not yet in Redis. */
    public void evictPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return;
        afterCommit(() -> {
            invalidationSequence.incrementAndGet();
            try {
                Long removed = redisTemplate.execute(INVALIDATE_PREFIX,
                        Collections.singletonList(GENERATION_KEY), UUID.randomUUID().toString(), prefix);
                if (removed != null) totalEvictions.addAndGet(removed);
            } catch (Exception ex) {
                logDegradation(cacheName(prefix), ex, "evict");
            }
        });
    }

    private void afterCommit(Runnable invalidation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidation.run(); }
            });
        } else {
            invalidation.run();
        }
    }

    /** Cumulative cache-effectiveness snapshot for the admin cache_stats endpoint. */
    public Map<String, Object> stats() {
        long hits = totalHits.get();
        long misses = totalMisses.get();
        long denominator = hits + misses;
        double hitRate = denominator == 0 ? 0.0 : (double) hits / denominator;

        List<Map<String, Object>> all = new ArrayList<>();
        perKey.forEach((key, row) -> {
            if (row[0] == 0 && row[1] == 0) {
                return;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);
            entry.put("hits", row[0]);
            entry.put("misses", row[1]);
            entry.put("hitRate", row[0] + row[1] == 0 ? 0.0 : Math.round(((double) row[0] / (row[0] + row[1])) * 10000.0) / 10000.0);
            all.add(entry);
        });
        all.sort((a, b) -> {
            long aMiss = (Long) a.get("misses");
            long bMiss = (Long) b.get("misses");
            if (aMiss != bMiss) return Long.compare(bMiss, aMiss);
            return Long.compare((Long) b.get("hits"), (Long) a.get("hits"));
        });
        List<Map<String, Object>> topKeys = all.size() > 30 ? new ArrayList<>(all.subList(0, 30)) : all;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("hits", hits);
        stats.put("misses", misses);
        stats.put("loads", totalLoads.get());
        stats.put("evictions", totalEvictions.get());
        stats.put("degradations", totalDegradations.get());
        stats.put("rejectedLoads", rejectedLoads.get());
        stats.put("timedOutWaiters", timedOutWaiters.get());
        stats.put("activeLoads", (long) LOADER_THREADS - loadPermits.availablePermits());
        stats.put("queuedLoads", (long) ((ThreadPoolExecutor) loaders).getQueue().size());
        stats.put("overallHitRate", Math.round(hitRate * 10000.0) / 10000.0);
        stats.put("keysTracked", (long) perKey.size());
        stats.put("keysDroppedAtCap", keysDroppedAtCap.get());
        stats.put("topKeys", topKeys);
        return stats;
    }

    private void record(String key, boolean hit) {
        if (hit) {
            totalHits.incrementAndGet();
        } else {
            totalMisses.incrementAndGet();
        }
        long[] row = perKey.get(key);
        if (row == null) {
            if (perKey.size() >= PER_KEY_CAP) {
                keysDroppedAtCap.incrementAndGet();
                return;
            }
            long[] fresh = new long[]{0, 0};
            row = perKey.putIfAbsent(key, fresh);
            if (row == null) {
                row = fresh;
            }
        }
        synchronized (row) {
            if (hit) {
                row[0]++;
            } else {
                row[1]++;
            }
        }
    }

    private <T> Supplier<T> countLoad(Supplier<T> loader) {
        totalLoads.incrementAndGet();
        return loader;
    }

    private void store(String key, Duration ttl, Duration jitter, Object value, String generation) {
        try {
            Duration effective = ttl == null ? Duration.ofMinutes(5) : ttl;
            if (jitter != null && jitter.toMillis() > 0) {
                effective = effective.plusMillis(ThreadLocalRandom.current().nextLong(0, jitter.toMillis() + 1));
            }
            // The comparison and SET must be atomic across API instances. Arguments
            // use this template's JSON serializer: values keep their existing format,
            // UUIDs are JSON strings, and the TTL is a numeric Long (not a string).
            redisTemplate.execute(STORE, Arrays.asList(key, GENERATION_KEY),
                    generation, value, effective.toMillis());
        } catch (Exception ex) {
            logDegradation(cacheName(key), ex, "write");
        }
    }

    /**
     * Derives the cache namespace from a concrete key (e.g. "styler:dto:GS5816" →
     * "styler:dto") so degradation warnings are stable and groupable even though
     * individual keys vary per styler. Returns the full key unchanged when there is
     * no namespace separator.
     */
    private static String cacheName(String key) {
        if (key == null) {
            return "unknown";
        }
        int lastSep = key.lastIndexOf(':');
        if (lastSep > 0) {
            return key.substring(0, lastSep);
        }
        int firstSep = key.indexOf(':');
        return firstSep > 0 ? key.substring(0, firstSep) : key;
    }

    /**
     * One-line warning per operation per window so ongoing degradation stays
     * visible without flooding the log on every degraded call. The counters in
     * /admin/cache_stats still count every occurrence — only the log line is
     * throttled, keyed by operation (read/write/evict) since every namespace
     * degrades together when Redis is down.
     */
    private void logDegradation(String cacheName, Exception ex, String operation) {
        totalDegradations.incrementAndGet();
        ThrottledLog.warnOncePerWindow(LOG, "read-cache/" + operation,
                "Redis read cache degraded [cache=" + cacheName + " operation=" + operation
                        + "] falling back to direct load: " + ex.getMessage());
    }
}

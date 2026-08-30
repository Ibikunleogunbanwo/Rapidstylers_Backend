package com.macrotel.rapidstylers.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Read-through cache for public read paths.
 *
 * Design (cache-aside + stampede defence):
 *  - Redis hit  → return the cached value.
 *  - Redis miss → per-key single-flight: concurrent misses on the same key share
 *    exactly one loader invocation, then the result is stored with a TTL plus a
 *    random jitter (probabilistic early expiration) so entries never expire in a
 *    synchronized wave.
 *  - Degradation → any Redis failure (connection, serialization, ...) falls back
 *    to running the loader directly; the cache is a speed-up, never a dependency.
 *
 * Key namespaces are exposed as constants so eviction call sites and tests can
 * target the same keys the read paths use.
 */
@Service
public class ReadCacheService {

    private static final Logger LOG = Logger.getLogger(ReadCacheService.class.getName());
    private static final long SINGLE_FLIGHT_TIMEOUT_SECONDS = 5;

    // Key namespaces.
    public static final String KEY_STYLER_DTO = "styler:dto:";
    public static final String KEY_STYLER_SUBSERVICES = "styler:subservices:";
    public static final String KEY_STYLER_PORTFOLIO = "styler:portfolio:";
    public static final String KEY_STYLER_REVIEWS = "styler:reviews:";
    public static final String KEY_STYLER_AVAILABILITY = "styler:availability:";
    public static final String KEY_STYLER_APPOINTMENTS = "styler:appointments:";
    public static final String KEY_SEARCH_PROVINCE = "search:province:";
    public static final String KEY_SEARCH_SERVICE = "search:service:";
    public static final String KEY_CATALOG_SERVICES = "catalog:services";
    public static final String KEY_CATALOG_IDENTIFICATIONS = "catalog:identifications";
    public static final String KEY_CATALOG_BLOGS = "catalog:blogs";
    public static final String KEY_CATALOG_BLOG = "catalog:blog:";

    // TTLs and jitter windows (probabilistic early expiration).
    public static final Duration CATALOG_TTL = Duration.ofMinutes(60);
    public static final Duration BLOG_TTL = Duration.ofMinutes(30);
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

    /**
     * Cache-aside read with single-flight on the miss path.
     * Returns null only when the loader returns null (never cached).
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String key, Duration ttl, Duration jitter, Supplier<T> loader) {
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                record(key, true);
                return (T) cached;
            }
        } catch (Exception ex) {
            logDegradation(cacheName(key), ex, "read");
        }
        record(key, false);
        try {
            CompletableFuture<Object> future = inFlight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
                try {
                    T value = countLoad(loader).get();
                    if (value != null) {
                        store(key, ttl, jitter, value);
                    }
                    return value;
                } finally {
                    inFlight.remove(k);
                }
            }));
            return (T) future.get(SINGLE_FLIGHT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            // Shared future failed or timed out — never depend on it; load directly.
            inFlight.remove(key);
            T value = countLoad(loader).get();
            if (value != null) {
                store(key, ttl, jitter, value);
            }
            return value;
        }
    }

    /** Best-effort delete of cache keys (used by write paths). Never throws. */
    public void evict(String... keys) {
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            if (key != null) {
                try {
                    redisTemplate.delete(key);
                    totalEvictions.incrementAndGet();
                } catch (Exception ex) {
                    logDegradation(cacheName(key), ex, "evict");
                }
            }
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

    private void store(String key, Duration ttl, Duration jitter, Object value) {
        try {
            Duration effective = ttl == null ? Duration.ofMinutes(5) : ttl;
            if (jitter != null && jitter.toMillis() > 0) {
                effective = effective.plusMillis(ThreadLocalRandom.current().nextLong(0, jitter.toMillis() + 1));
            }
            redisTemplate.opsForValue().set(key, value, effective);
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
     * One-line, per-occurrence warning so ongoing degradation is visible without
     * digging into request logs. Keyed by cache name; never throttled away after
     * the first hit (the counters in /admin/cache_stats expose the aggregate).
     */
    private void logDegradation(String cacheName, Exception ex, String operation) {
        totalDegradations.incrementAndGet();
        LOG.warning("Redis read cache degraded [cache=" + cacheName + " operation=" + operation
                + "] falling back to direct load: " + ex.getMessage());
    }
}

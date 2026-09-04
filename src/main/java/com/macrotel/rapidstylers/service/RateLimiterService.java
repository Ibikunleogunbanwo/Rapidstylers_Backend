package com.macrotel.rapidstylers.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed auth and OTP rate limiter with a fail-closed in-memory fallback.
 *
 * Redis keeps lockout counters shared across app instances and restarts. When
 * Redis is unreachable the limiter MUST NOT fail open (that would silently
 * disable brute-force protection) — instead it falls back to a bounded
 * in-process store so limits keep being enforced, just per-JVM.
 *
 * Client IP resolution only trusts X-Forwarded-For when the immediate peer is
 * a configured trusted proxy (see {@code app.rate-limit.trusted-proxies}).
 * Direct clients — including attackers spoofing the header — are always keyed
 * by their socket address, so per-IP buckets cannot be rotated by the client.
 */
@Service
public class RateLimiterService {

    private static final int DEFAULT_WINDOW_SECONDS = 900;

    /** Upper bound on in-memory fallback buckets so attacker-generated keys cannot exhaust memory. */
    private static final int MAX_MEMORY_BUCKETS = 50_000;

    /** Minimum interval between opportunistic prunes of the in-memory store. */
    private static final long MEMORY_PRUNE_INTERVAL_MILLIS = 30_000L;

    /**
     * Atomic INCR + conditional EXPIRE in a single Redis round trip. The window
     * TTL is applied only when the counter starts at 1, and Redis guarantees the
     * script runs without interleaving — so the TTL can never be lost to a crash
     * between INCR and EXPIRE (which would leave a key without a TTL and block
     * forever) and concurrent requests cannot interfere with the window.
     */
    static final RedisScript<Long> INCR_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]); "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; "
                    + "return c",
            Long.class);

    /**
     * StringRedisTemplate (plain-string key AND value serializer) is essential
     * here: the Lua script increments counters and expires them with a numeric
     * TTL passed as ARGV. A JSON/Jackson value serializer would quote that
     * ARGV ("900") so Redis rejects EXPIRE and the counter is left with no
     * TTL — permanently blocking the key once it reaches the cap. Keys and
     * values for rate limiting are always plain strings.
     */
    private final StringRedisTemplate redisTemplate;

    @Value("${app.rate-limit.trusted-proxies:}")
    private String trustedProxiesConfig;

    /** Parsed trusted proxies; empty means X-Forwarded-For is never trusted. */
    volatile List<CidrRange> trustedProxies = List.of();

    /** In-process fallback store used when Redis is unavailable, so limits never fail open. */
    private final Map<String, MemoryBucket> memoryBuckets = new ConcurrentHashMap<>();
    private final AtomicLong memoryLastPruned = new AtomicLong(0L);

    public RateLimiterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    void init() {
        this.trustedProxies = parseTrustedProxies(trustedProxiesConfig);
    }

    /** Record an attempt using the default auth/OTP window. */
    public void record(String key) {
        record(key, DEFAULT_WINDOW_SECONDS);
    }

    /** Record an attempt and ensure the key expires at the window boundary. */
    public void record(String key, int windowSeconds) {
        try {
            redisTemplate.execute(INCR_EXPIRE_SCRIPT, Collections.singletonList(key),
                    String.valueOf(windowSeconds));
            return;
        } catch (Exception ignored) {
            // Redis unavailable — fall through to the in-memory fallback below.
        }
        recordMemory(key, windowSeconds);
    }

    /** True when the recorded attempts within the active TTL window reached max. */
    public boolean isBlocked(String key, int windowSeconds, int max) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            if (raw == null) {
                return false;
            }
            return Long.parseLong(raw) >= max;
        } catch (Exception ignored) {
            // Redis unavailable — fall through to the in-memory fallback below.
        }
        return isBlockedMemory(key, max);
    }

    /** Forget all recorded attempts for a key, usually after successful auth. */
    public void clear(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {
            // Cleanup is best-effort; the in-memory fallback is always cleared below.
        }
        memoryBuckets.remove(key);
    }

    /**
     * Best-effort client IP for the current request.
     *
     * X-Forwarded-For is only honoured when the immediate peer is a trusted
     * proxy; the chain is then walked right-to-left, skipping trusted proxies,
     * to find the first untrusted client. Otherwise the socket address is used,
     * which makes client-supplied spoofing ineffective.
     */
    public String clientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "unknown";
            }
            HttpServletRequest request = attrs.getRequest();
            String remote = request.getRemoteAddr();
            InetAddress peer = (remote == null || remote.isBlank()) ? null : InetAddress.getByName(remote);
            String forwarded = request.getHeader("X-Forwarded-For");
            if (peer != null && forwarded != null && !forwarded.isBlank() && isTrustedProxy(peer)) {
                String[] chain = forwarded.split(",");
                for (int i = chain.length - 1; i >= 0; i--) {
                    String candidate = chain[i].trim();
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    try {
                        if (!isTrustedProxy(InetAddress.getByName(candidate))) {
                            return candidate;
                        }
                    } catch (Exception ignored) {
                        // Malformed entry — skip it and keep walking the chain.
                    }
                }
                // Every entry was a trusted proxy; the peer itself is the client.
            }
            return remote == null || remote.isBlank() ? "unknown" : remote;
        } catch (Exception ex) {
            return "unknown";
        }
    }

    public static String userAgent() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "unknown";
            }
            String userAgent = attrs.getRequest().getHeader("User-Agent");
            return userAgent == null || userAgent.isBlank() ? "unknown" : userAgent;
        } catch (Exception ex) {
            return "unknown";
        }
    }

    // ---- In-memory fallback ------------------------------------------------

    private static final class MemoryBucket {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long windowEndsAt;
    }

    private void recordMemory(String key, int windowSeconds) {
        pruneMemoryIfNeeded();
        long now = System.currentTimeMillis();
        MemoryBucket bucket = memoryBuckets.computeIfAbsent(key, k -> {
            MemoryBucket created = new MemoryBucket();
            created.windowEndsAt = now + windowSeconds * 1000L;
            return created;
        });
        if (bucket.windowEndsAt <= now) {
            synchronized (bucket) {
                if (bucket.windowEndsAt <= now) {
                    bucket.count.set(0);
                    bucket.windowEndsAt = now + windowSeconds * 1000L;
                }
            }
        }
        bucket.count.incrementAndGet();
    }

    private boolean isBlockedMemory(String key, int max) {
        MemoryBucket bucket = memoryBuckets.get(key);
        if (bucket == null) {
            return false;
        }
        if (bucket.windowEndsAt <= System.currentTimeMillis()) {
            return false;
        }
        return bucket.count.get() >= max;
    }

    /** Bounds the fallback store: drops expired buckets and evicts oldest when over capacity. */
    private void pruneMemoryIfNeeded() {
        long now = System.currentTimeMillis();
        long last = memoryLastPruned.get();
        if (now - last < MEMORY_PRUNE_INTERVAL_MILLIS && memoryBuckets.size() < MAX_MEMORY_BUCKETS) {
            return;
        }
        if (!memoryLastPruned.compareAndSet(last, now)) {
            return;
        }
        memoryBuckets.entrySet().removeIf(e -> e.getValue().windowEndsAt <= now);
        if (memoryBuckets.size() >= MAX_MEMORY_BUCKETS) {
            List<Map.Entry<String, MemoryBucket>> sorted = new ArrayList<>(memoryBuckets.entrySet());
            sorted.sort(Comparator.comparingLong(e -> e.getValue().windowEndsAt));
            int excess = memoryBuckets.size() - MAX_MEMORY_BUCKETS + 1000;
            for (int i = 0; i < excess && i < sorted.size(); i++) {
                memoryBuckets.remove(sorted.get(i).getKey());
            }
        }
    }

    // ---- Trusted proxy resolution ------------------------------------------

    private boolean isTrustedProxy(InetAddress address) {
        for (CidrRange range : trustedProxies) {
            if (range.matches(address.getAddress())) {
                return true;
            }
        }
        return false;
    }

    /** Parses a comma-separated list of IPs or CIDR ranges ("173.245.48.0/20, 2a06:98c0::/29, 10.0.0.1"). */
    static List<CidrRange> parseTrustedProxies(String config) {
        List<CidrRange> ranges = new ArrayList<>();
        if (config == null || config.isBlank()) {
            return ranges;
        }
        for (String part : config.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            try {
                String addressPart = entry;
                int prefix = -1;
                int slash = entry.indexOf('/');
                if (slash > 0) {
                    addressPart = entry.substring(0, slash);
                    prefix = Integer.parseInt(entry.substring(slash + 1).trim());
                }
                byte[] address = InetAddress.getByName(addressPart).getAddress();
                int bits = prefix < 0 ? address.length * 8 : prefix;
                ranges.add(new CidrRange(address, bits));
            } catch (Exception ignored) {
                // Malformed proxy entry — ignore it; the remaining list still applies.
            }
        }
        return ranges;
    }

    /** IP or CIDR range matcher supporting both IPv4 and IPv6. */
    static final class CidrRange {
        final byte[] network;
        final int prefix;

        CidrRange(byte[] network, int prefix) {
            this.network = network;
            this.prefix = Math.max(0, Math.min(prefix, network.length * 8));
        }

        boolean matches(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefix % 8;
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                if ((address[fullBytes] & mask) != (network[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}

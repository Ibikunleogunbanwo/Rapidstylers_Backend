package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Circle;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Geo index must never take the request path down — every Redis failure is
 * swallowed gracefully AND counted so a silently-broken "nearby stylists"
 * feature is observable to ops instead of only showing a log line.
 */
class LocationCacheServiceTest {

    private RedisTemplate<String, Object> redisTemplate;
    private GeoOperations<String, Object> geoOps;
    private ZSetOperations<String, Object> zSetOps;
    private LocationCacheService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        geoOps = mock(GeoOperations.class);
        zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        service = new LocationCacheService(redisTemplate);
    }

    @Test
    void indexFailureIsCountedAndDoesNotThrow() {
        when(geoOps.add(anyString(), any(RedisGeoCommands.GeoLocation.class)))
                .thenThrow(new RuntimeException("redis down"));

        service.indexStyler("JS1234", 43.65, -79.38); // must not throw

        assertEquals(1L, service.failures(), "geo index failures must be counted for ops");
    }

    @Test
    void radiusSearchFailureCountsAndReturnsEmpty() {
        when(geoOps.radius(anyString(), any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertTrue(service.findNearby(43.65, -79.38, 10).isEmpty(),
                "a Redis failure must degrade to empty, not throw");
        assertEquals(1L, service.failures());
    }

    @Test
    void removeFailureIsCounted() {
        when(zSetOps.remove(anyString(), any())).thenThrow(new RuntimeException("redis down"));

        service.removeStyler("JS1234"); // must not throw

        assertEquals(1L, service.failures());
    }
}
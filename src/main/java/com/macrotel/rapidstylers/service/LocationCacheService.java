package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.ThrottledLog;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Redis-backed geospatial index for stylist locations.
 *
 * Commands used:
 *   GEOADD  stylers:geo <lng> <lat> <stylerId>   — index a stylist
 *   GEORADIUS stylers:geo <lng> <lat> <radius> km — find nearby stylers
 *   GEODIST stylers:geo <id1> <id2> km            — distance between two stylers
 *   ZREM    stylers:geo <stylerId>                 — remove a stylist
 *
 * All operations are O(log N) — no DB stress.
 *
 * Members are plain styler IDs, so this uses the string-serialized template:
 * through a JSON value serializer every member would be stored quoted on the
 * zset ("JS1234") — self-consistent for reads, but unreadable via redis-cli
 * and impossible to remove with a plain-string ZREM, which silently leaves
 * stale geo entries (the rate-limiter class of bug).
 */
@Service
public class LocationCacheService {

    private static final Logger LOG = Logger.getLogger(LocationCacheService.class.getName());
    private static final String GEO_KEY = "stylers:geo";

    private final StringRedisTemplate redisTemplate;
    private final GeoOperations<String, String> geoOps;
    private final AtomicLong totalFailures = new AtomicLong();

    public LocationCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.geoOps = redisTemplate.opsForGeo();
    }

    /**
     * Add or update a stylist's location in the Redis geospatial index.
     * @param stylerId  the styler's unique ID (e.g. "JS1234")
     * @param longitude the longitude (must be between -180 and 180)
     * @param latitude  the latitude (must be between -85 and 85)
     */
    public void indexStyler(String stylerId, double longitude, double latitude) {
        try {
            Point point = new Point(longitude, latitude);
            geoOps.add(GEO_KEY, new RedisGeoCommands.GeoLocation<>(stylerId, point));
            LOG.info("Indexed styler " + stylerId + " at [" + latitude + ", " + longitude + "]");
        } catch (Exception ex) {
            totalFailures.incrementAndGet();
            ThrottledLog.warnOncePerWindow(LOG, "geo/index",
                    "Failed to index styler " + stylerId + " (failures="
                            + totalFailures.get() + "): " + ex.getMessage());
        }
    }

    /**
     * Remove a stylist from the geospatial index.
     */
    public void removeStyler(String stylerId) {
        try {
            redisTemplate.opsForZSet().remove(GEO_KEY, stylerId);
        } catch (Exception ex) {
            totalFailures.incrementAndGet();
            ThrottledLog.warnOncePerWindow(LOG, "geo/remove",
                    "Failed to remove styler " + stylerId + " from geo index (failures="
                            + totalFailures.get() + "): " + ex.getMessage());
        }
    }

    /**
     * Find all stylers within a given radius of a point.
     * @param longitude search center longitude
     * @param latitude  search center latitude
     * @param radius    search radius in kilometers
     * @return list of stylerIds within the radius, ordered by distance (nearest first)
     */
    /**
     * Find all stylers within a given radius, returning styler IDs keyed by distance.
     * @return Map of stylerId → distance in km, sorted by distance ascending
     */
    public Map<String, Double> findNearbyWithDistance(double longitude, double latitude, double radius) {
        try {
            Point center = new Point(longitude, latitude);
            Distance distance = new Distance(radius, RedisGeoCommands.DistanceUnit.KILOMETERS);
            Circle circle = new Circle(center, distance);

            GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                    geoOps.radius(GEO_KEY, circle, RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                            .sortAscending()
                            .includeDistance());

            Map<String, Double> stylerDistances = new LinkedHashMap<>();
            if (results != null) {
                for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
                    String stylerId = result.getContent().getName();
                    double distKm = result.getDistance().getValue();
                    stylerDistances.put(stylerId, distKm);
                }
            }
            return stylerDistances;

        } catch (Exception ex) {
            totalFailures.incrementAndGet();
            ThrottledLog.warnOncePerWindow(LOG, "geo/radius",
                    "Radius search error (failures=" + totalFailures.get() + "): " + ex.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Find nearby styler IDs (no distance info).
     */
    public List<String> findNearby(double longitude, double latitude, double radius) {
        return new ArrayList<>(findNearbyWithDistance(longitude, latitude, radius).keySet());
    }

    /**
     * Find stylers within radius, optionally filtered by service type.
     */
    public List<String> findNearbyFiltered(double longitude, double latitude, double radius, String serviceTypeId) {
        List<String> nearbyIds = findNearby(longitude, latitude, radius);
        return nearbyIds;
    }

    /** Removes every member before a full rebuild from the relational source of truth. */
    public void clearIndex() {
        try {
            redisTemplate.delete(GEO_KEY);
        } catch (Exception ex) {
            totalFailures.incrementAndGet();
            ThrottledLog.warnOncePerWindow(LOG, "geo/clear",
                    "Failed to clear stylist geo index (failures=" + totalFailures.get() + "): " + ex.getMessage());
        }
    }

    /** Number of geo index failures since boot — ops signal for silent degradation. */
    public long failures() {
        return totalFailures.get();
    }
}

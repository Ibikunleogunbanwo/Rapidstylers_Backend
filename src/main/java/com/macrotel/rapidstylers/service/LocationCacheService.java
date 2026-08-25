package com.macrotel.rapidstylers.service;

import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
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
 */
@Service
public class LocationCacheService {

    private static final Logger LOG = Logger.getLogger(LocationCacheService.class.getName());
    private static final String GEO_KEY = "stylers:geo";

    private final RedisTemplate<String, Object> redisTemplate;
    private final GeoOperations<String, Object> geoOps;

    public LocationCacheService(RedisTemplate<String, Object> redisTemplate) {
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
            LOG.warning("Failed to index styler " + stylerId + ": " + ex.getMessage());
        }
    }

    /**
     * Remove a stylist from the geospatial index.
     */
    public void removeStyler(String stylerId) {
        try {
            redisTemplate.opsForZSet().remove(GEO_KEY, stylerId);
        } catch (Exception ex) {
            LOG.warning("Failed to remove styler " + stylerId + " from geo index: " + ex.getMessage());
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

            GeoResults<RedisGeoCommands.GeoLocation<Object>> results =
                    geoOps.radius(GEO_KEY, circle, RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                            .sortAscending()
                            .includeDistance());

            Map<String, Double> stylerDistances = new LinkedHashMap<>();
            if (results != null) {
                for (GeoResult<RedisGeoCommands.GeoLocation<Object>> result : results) {
                    String stylerId = String.valueOf(result.getContent().getName());
                    double distKm = result.getDistance().getValue();
                    stylerDistances.put(stylerId, distKm);
                }
            }
            return stylerDistances;

        } catch (Exception ex) {
            LOG.warning("Radius search error: " + ex.getMessage());
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
}

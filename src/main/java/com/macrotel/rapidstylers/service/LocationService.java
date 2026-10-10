package com.macrotel.rapidstylers.service;

import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * Detects the visitor's approximate location from the headers Cloudflare's
 * "Add visitor location headers" managed transform puts on every proxied
 * request (cf-iplatitude, cf-iplongitude, cf-ipcity, cf-region, cf-ipcountry).
 * Returns lat/lng + city + province so the frontend can use it as the default
 * search location.
 *
 * This replaces an ip-api.com lookup keyed on request.getRemoteAddr(), which
 * behind Nginx + Docker is the bridge gateway, so production always fell back
 * to Edmonton. No outbound call is made now. Without the headers (local dev,
 * or the transform turned off in the Cloudflare dashboard) the Edmonton
 * fallback is returned, as before.
 */
@Service
public class LocationService {

    static final String LATITUDE_HEADER = "cf-iplatitude";
    static final String LONGITUDE_HEADER = "cf-iplongitude";
    static final String CITY_HEADER = "cf-ipcity";
    static final String REGION_HEADER = "cf-region";
    static final String COUNTRY_HEADER = "cf-ipcountry";

    /**
     * Detect location from Cloudflare's visitor location headers.
     * Falls back to Edmonton, AB if they are missing or unusable.
     */
    public Map<String, Object> detectLocation(HttpServletRequest request) {
        Double latitude = coordinate(request.getHeader(LATITUDE_HEADER), 90);
        Double longitude = coordinate(request.getHeader(LONGITUDE_HEADER), 180);
        if (latitude == null || longitude == null) {
            return fallbackLocation();
        }

        Map<String, Object> location = new HashMap<>();
        location.put("latitude", latitude);
        location.put("longitude", longitude);
        location.put("city", text(request.getHeader(CITY_HEADER)));
        location.put("province", text(request.getHeader(REGION_HEADER)));
        location.put("country", countryName(request.getHeader(COUNTRY_HEADER)));
        return location;
    }

    private static Double coordinate(String value, double limit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return (Double.isFinite(parsed) && Math.abs(parsed) <= limit) ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** Cloudflare sends an ISO 3166-1 alpha-2 code; ip-api used to send the English name. */
    private static String countryName(String code) {
        if (code == null || code.isBlank()) {
            return "Canada";
        }
        String name = new Locale("", code.trim().toUpperCase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH);
        return name.isBlank() ? code.trim() : name;
    }

    private Map<String, Object> fallbackLocation() {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("latitude", 53.5461);
        fallback.put("longitude", -113.4857);
        fallback.put("city", "Edmonton");
        fallback.put("province", "Alberta");
        fallback.put("country", "Canada");
        return fallback;
    }
}

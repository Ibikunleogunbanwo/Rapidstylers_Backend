package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.logging.Logger;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Detects user location from their IP address using ip-api.com (free, no key).
 * Returns lat/lng + city + province so the frontend can use it as default
 * search location.
 */
@Service
public class LocationService {

    private static final Logger LOG = Logger.getLogger(LocationService.class.getName());
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Detect location from the request's remote IP address.
     * Falls back to Edmonton, AB if detection fails.
     */
    public Map<String, Object> detectLocation(HttpServletRequest request) {
        try {
            // In dev, the IP is 127.0.0.1 — use a public IP for testing
            String ip = request.getRemoteAddr();
            if ("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
                ip = "";  // ip-api.com uses the caller's IP when empty
            }

            String url = "http://ip-api.com/json/" + ip + "?fields=status,country,regionName,city,lat,lon";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            if (!"success".equals(root.path("status").asText())) {
                return fallbackLocation();
            }

            Map<String, Object> location = new HashMap<>();
            location.put("latitude", root.path("lat").asDouble());
            location.put("longitude", root.path("lon").asDouble());
            location.put("city", root.path("city").asText(""));
            location.put("province", root.path("regionName").asText(""));
            location.put("country", root.path("country").asText("Canada"));
            return location;

        } catch (Exception ex) {
            LOG.warning("IP geolocation error: " + ex.getMessage());
            return fallbackLocation();
        }
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

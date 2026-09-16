package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.logging.Logger;

/**
 * Derives an IANA time zone identifier from a lat/lng via the Google Time
 * Zone API. Used at signup so a vendor's weekly hours are read on the
 * vendor's own clock for the life of the record — a Toronto vendor gets
 * "America/Toronto", a Calgary vendor "America/Edmonton" — without per-request
 * zone guessing. Returns null on any failure (missing key, API error, zero
 * results); callers fall back to the province map, never fail the signup.
 */
@Service
public class GoogleTimezoneService {

    private static final Logger LOG = Logger.getLogger(GoogleTimezoneService.class.getName());
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.google.api-key:}")
    private String googleApiKey;

    /**
     * @return e.g. "America/Edmonton", or null when the zone can't be determined.
     */
    public String timeZoneId(double latitude, double longitude) {
        if (googleApiKey == null || googleApiKey.isBlank()) {
            return null;
        }
        try {
            // Digits, a sign and a dot need no escaping, and the comma must stay
            // raw: this string is a URI template that RestTemplate encodes itself,
            // so pre-encoding with URLEncoder turned the comma into %252C and
            // Google answered INVALID_REQUEST ("Invalid 'location' parameter") for
            // every single lookup — a bare lat/lng pair passes validation.
            String location = latitude + "," + longitude;
            // Timestamp only affects the zone's UTC offset (DST); the zone id
            // itself is the same year-round, so "now" is always fine.
            long timestamp = System.currentTimeMillis() / 1000;
            String url = "https://maps.googleapis.com/maps/api/timezone/json?location="
                    + location
                    + "&timestamp=" + timestamp
                    + "&key=" + googleApiKey;

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            String status = root.path("status").asText();
            if (!"OK".equals(status)) {
                LOG.warning("Time Zone API returned status: " + status);
                return null;
            }
            String zoneId = root.path("timeZoneId").asText(null);
            if (zoneId == null || zoneId.isBlank()) {
                return null;
            }
            // Validate against java.time so a bad or unknown payload can
            // never store a zone that later blows up ZonedDateTime.now(zone).
            // (Google returns canonical IANA ids; java.time has no legacy-
            // alias translation, so anything it can't parse is rejected.)
            return java.time.ZoneId.of(zoneId).getId();
        } catch (Exception ex) {
            LOG.warning("Time Zone lookup failed: " + ex.getMessage());
            return null;
        }
    }
}

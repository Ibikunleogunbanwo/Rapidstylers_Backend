package com.macrotel.rapidstylers.styler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Converts addresses to lat/lng using the Google Geocoding API.
 * Used on styler registration and address updates to populate Redis.
 */
@Service
public class GeocodingService {

    private static final Logger LOG = Logger.getLogger(GeocodingService.class.getName());
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.google.api-key:}")
    private String googleApiKey;

    /**
     * Geocode an address string to lat/lng.
     * @return { "latitude": 53.5461, "longitude": -113.4857, "formattedAddress": "..." }
     * or null if geocoding fails.
     */
    public Map<String, Object> geocode(String address) {
        if (googleApiKey == null || googleApiKey.isBlank()) {
            LOG.warning("Google API key not configured — geocoding disabled");
            return null;
        }

        try {
            String encoded = URLEncoder.encode(address, StandardCharsets.UTF_8);
            String url = "https://maps.googleapis.com/maps/api/geocode/json?address=" + encoded + "&key=" + googleApiKey;

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            String status = root.path("status").asText();
            if (!"OK".equals(status)) {
                LOG.warning("Geocoding returned status: " + status + " for address: " + address
                        + (root.has("error_message") ? " (" + root.path("error_message").asText() + ")" : ""));
                return null;
            }

            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return null;
            }

            JsonNode result = results.get(0);
            JsonNode geometry = result.path("geometry").path("location");
            double lat = geometry.path("lat").asDouble();
            double lng = geometry.path("lng").asDouble();
            String formatted = result.path("formatted_address").asText();

            Map<String, Object> geo = new HashMap<>();
            geo.put("latitude", lat);
            geo.put("longitude", lng);
            geo.put("formattedAddress", formatted);
            return geo;

        } catch (Exception ex) {
            LOG.warning("Geocoding error: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Geocode from a Google Place ID (returned by Places Autocomplete).
     */
    public Map<String, Object> geocodeByPlaceId(String placeId) {
        if (googleApiKey == null || googleApiKey.isBlank()) {
            return null;
        }

        try {
            String url = "https://maps.googleapis.com/maps/api/geocode/json?place_id=" + placeId + "&key=" + googleApiKey;
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            if (!"OK".equals(root.path("status").asText())) {
                return null;
            }

            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return null;
            }

            JsonNode result = results.get(0);
            JsonNode geometry = result.path("geometry").path("location");
            double lat = geometry.path("lat").asDouble();
            double lng = geometry.path("lng").asDouble();
            String formatted = result.path("formatted_address").asText();

            // Extract structured components
            JsonNode addressComponents = result.path("address_components");
            String street = "", city = "", province = "", postalCode = "", country = "";
            for (JsonNode comp : addressComponents) {
                String[] types = objectMapper.convertValue(comp.path("types"), String[].class);
                String longName = comp.path("long_name").asText();
                for (String type : types) {
                    if ("street_number".equals(type) || "route".equals(type)) {
                        street = street.isEmpty() ? longName : street + " " + longName;
                    } else if ("locality".equals(type)) {
                        city = longName;
                    } else if ("administrative_area_level_1".equals(type)) {
                        province = longName;
                    } else if ("postal_code".equals(type)) {
                        postalCode = longName;
                    } else if ("country".equals(type)) {
                        country = longName;
                    }
                }
            }

            Map<String, Object> geo = new HashMap<>();
            geo.put("latitude", lat);
            geo.put("longitude", lng);
            geo.put("formattedAddress", formatted);
            geo.put("streetAddress", street);
            geo.put("city", city);
            geo.put("province", province);
            geo.put("postalCode", postalCode);
            geo.put("country", country);
            return geo;

        } catch (Exception ex) {
            LOG.warning("Place ID geocoding error: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Address suggestions for the signup and booking forms, restricted to
     * Canadian addresses.
     *
     * <p>Tries Places API (New) first, then the legacy Places endpoint, so
     * suggestions work with whichever of the two is enabled for the key
     * (Google no longer lets new projects enable the legacy one).
     *
     * @return { "placeId", "description" } items, an empty list when Google
     * found nothing, or null when suggestions are unavailable (no key, or
     * Google refused or failed both calls). The reason is logged from
     * Google's own error message, which never contains the key.
     */
    public List<Map<String, Object>> placeAutocomplete(String input) {
        if (googleApiKey == null || googleApiKey.isBlank()) {
            LOG.warning("Google API key not configured — places autocomplete disabled");
            return null;
        }
        List<Map<String, Object>> suggestions = placeAutocompleteNew(input);
        return suggestions != null ? suggestions : placeAutocompleteLegacy(input);
    }

    private List<Map<String, Object>> placeAutocompleteNew(String input) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", googleApiKey);
            headers.set("X-Goog-FieldMask", "suggestions.placePrediction.placeId,suggestions.placePrediction.text.text");
            Map<String, Object> body = new HashMap<>();
            body.put("input", input);
            body.put("includedRegionCodes", List.of("ca"));
            body.put("includedPrimaryTypes", List.of("street_address", "premise", "subpremise", "route"));
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "https://places.googleapis.com/v1/places:autocomplete",
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            List<Map<String, Object>> suggestions = new ArrayList<>();
            for (JsonNode suggestion : root.path("suggestions")) {
                JsonNode prediction = suggestion.path("placePrediction");
                if (prediction.isMissingNode()) continue;
                Map<String, Object> item = new HashMap<>();
                item.put("placeId", prediction.path("placeId").asText());
                item.put("description", prediction.path("text").path("text").asText());
                suggestions.add(item);
            }
            return suggestions;
        } catch (org.springframework.web.client.HttpStatusCodeException ex) {
            LOG.warning("Places API (New) autocomplete refused (HTTP " + ex.getRawStatusCode() + "): "
                    + googleErrorMessage(ex.getResponseBodyAsString()));
            return null;
        } catch (Exception ex) {
            LOG.warning("Places API (New) autocomplete error: " + ex.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> placeAutocompleteLegacy(String input) {
        try {
            String encoded = URLEncoder.encode(input, StandardCharsets.UTF_8);
            String url = "https://maps.googleapis.com/maps/api/place/autocomplete/json?input=" + encoded
                    + "&components=country:CA&types=address&key=" + googleApiKey;

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            String status = root.path("status").asText();
            if ("ZERO_RESULTS".equals(status)) {
                return new ArrayList<>();
            }
            if (!"OK".equals(status)) {
                LOG.warning("Legacy Places autocomplete returned " + status + ": " + root.path("error_message").asText(""));
                return null;
            }

            List<Map<String, Object>> suggestions = new ArrayList<>();
            for (JsonNode prediction : root.path("predictions")) {
                Map<String, Object> item = new HashMap<>();
                item.put("placeId", prediction.path("place_id").asText());
                item.put("description", prediction.path("description").asText());
                suggestions.add(item);
            }
            return suggestions;

        } catch (Exception ex) {
            LOG.warning("Legacy Places autocomplete error: " + ex.getMessage());
            return null;
        }
    }

    /** Google's error text from a JSON error body ({"error":{"message":...}}), or the status alone. */
    private String googleErrorMessage(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            return error.path("status").asText("") + " " + error.path("message").asText("");
        } catch (Exception ex) {
            return "(no detail)";
        }
    }

    /**
     * Reverse-geocode lat/lng (e.g. from browser GPS) to a city + province.
     * @return { "latitude": ..., "longitude": ..., "city": "Calgary", "province": "Alberta",
     *          "postalCode": "...", "country": "Canada", "formattedAddress": "..." }
     * or null if the API key is missing or the call fails.
     */
    public Map<String, Object> reverseGeocode(double lat, double lng) {
        if (googleApiKey == null || googleApiKey.isBlank()) {
            LOG.warning("Google API key not configured — reverse geocoding disabled");
            return null;
        }

        try {
            String url = "https://maps.googleapis.com/maps/api/geocode/json?latlng=" + lat + "," + lng
                    + "&result_type=locality|administrative_area_level_1|postal_code&key=" + googleApiKey;
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            if (!"OK".equals(root.path("status").asText())) {
                LOG.warning("Reverse geocoding returned status: " + root.path("status").asText());
                return null;
            }

            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return null;
            }

            JsonNode result = results.get(0);
            JsonNode addressComponents = result.path("address_components");
            String city = "", province = "", postalCode = "", country = "";
            for (JsonNode comp : addressComponents) {
                String[] types = objectMapper.convertValue(comp.path("types"), String[].class);
                String longName = comp.path("long_name").asText();
                for (String type : types) {
                    if ("locality".equals(type) && city.isEmpty()) {
                        city = longName;
                    } else if ("administrative_area_level_1".equals(type) && province.isEmpty()) {
                        province = longName;
                    } else if ("postal_code".equals(type) && postalCode.isEmpty()) {
                        postalCode = longName;
                    } else if ("country".equals(type) && country.isEmpty()) {
                        country = longName;
                    }
                }
            }

            Map<String, Object> geo = new HashMap<>();
            geo.put("latitude", lat);
            geo.put("longitude", lng);
            geo.put("city", city);
            geo.put("province", province);
            geo.put("postalCode", postalCode);
            geo.put("country", country);
            geo.put("formattedAddress", result.path("formatted_address").asText());
            return geo;

        } catch (Exception ex) {
            LOG.warning("Reverse geocoding error: " + ex.getMessage());
            return null;
        }
    }
}

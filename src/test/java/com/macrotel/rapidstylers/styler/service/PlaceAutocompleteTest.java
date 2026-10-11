package com.macrotel.rapidstylers.styler.service;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Address suggestions: Places API (New) first, the legacy endpoint second.
 * "Google found nothing" is an empty list; "Google refused or no key" is
 * null, so the website can say suggestions are unavailable instead of
 * implying the address does not exist.
 */
class PlaceAutocompleteTest {

    private static final String NEW_URL = "https://places.googleapis.com/v1/places:autocomplete";

    private GeocodingService service;
    private MockRestServiceServer google;

    @BeforeEach
    void setUp() {
        service = new GeocodingService();
        ReflectionTestUtils.setField(service, "googleApiKey", "test-key");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        google = MockRestServiceServer.bindTo(restTemplate).build();
    }

    private void legacyAnswers(String json) {
        google.expect(ExpectedCount.once(), request -> {
                    String uri = request.getURI().toString();
                    assertTrue(uri.startsWith("https://maps.googleapis.com/maps/api/place/autocomplete/json"), uri);
                })
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void newApiSuggestionsAreReturnedWithoutCallingTheLegacyOne() {
        google.expect(requestTo(NEW_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andRespond(withSuccess("{\"suggestions\":[{\"placePrediction\":{\"placeId\":\"abc\","
                        + "\"text\":{\"text\":\"10020 101A Ave, Edmonton, AB, Canada\"}}}]}", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> result = service.placeAutocomplete("10020 101A");

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("abc", result.get(0).get("placeId"));
        assertEquals("10020 101A Ave, Edmonton, AB, Canada", result.get(0).get("description"));
        google.verify();
    }

    @Test
    void newApiWithNoMatchesIsAnEmptyListNotUnavailable() {
        google.expect(requestTo(NEW_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> result = service.placeAutocomplete("zzzz");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void newApiRefusedFallsBackToTheLegacyApi() {
        google.expect(requestTo(NEW_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Places API (New) has not been used\"}}"));
        legacyAnswers("{\"status\":\"OK\",\"predictions\":[{\"place_id\":\"xyz\",\"description\":\"1 Main St, Calgary, AB, Canada\"}]}");

        List<Map<String, Object>> result = service.placeAutocomplete("1 Main");

        assertNotNull(result);
        assertEquals("xyz", result.get(0).get("placeId"));
        google.verify();
    }

    @Test
    void bothApisRefusedIsUnavailable() {
        google.expect(requestTo(NEW_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        legacyAnswers("{\"status\":\"REQUEST_DENIED\",\"error_message\":\"This API project is not authorized\"}");

        assertNull(service.placeAutocomplete("1 Main"));
    }

    @Test
    void legacyZeroResultsIsAnEmptyList() {
        google.expect(requestTo(NEW_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        legacyAnswers("{\"status\":\"ZERO_RESULTS\",\"predictions\":[]}");

        List<Map<String, Object>> result = service.placeAutocomplete("zzzz");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void missingKeyIsUnavailableAndCallsNobody() {
        ReflectionTestUtils.setField(service, "googleApiKey", "");

        assertNull(service.placeAutocomplete("1 Main"));
        google.verify();
    }
}

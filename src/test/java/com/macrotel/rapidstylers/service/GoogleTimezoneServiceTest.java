package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The signup-time zone derivation: lat/lng in, IANA zone id out. The Google
 * Time Zone API is the source; any failure must yield null (callers fall back
 * to the province map), never an exception and never a bogus zone.
 */
class GoogleTimezoneServiceTest {

    private GoogleTimezoneService service;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() throws Exception {
        service = new GoogleTimezoneService();
        restTemplate = mock(RestTemplate.class);
        Field field = GoogleTimezoneService.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        field.set(service, restTemplate);
        setKey("test-key");
    }

    private void setKey(String value) throws Exception {
        Field field = GoogleTimezoneService.class.getDeclaredField("googleApiKey");
        field.setAccessible(true);
        field.set(service, value);
    }

    private void respond(String status, String timeZoneId) {
        String json = String.format(
                "{\"status\":\"%s\",\"timeZoneId\":\"%s\",\"rawOffset\":-21600,\"dstOffset\":0}",
                status, timeZoneId == null ? "" : timeZoneId);
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(json, HttpStatus.OK));
    }

    private String call(double lat, double lng) throws Exception {
        Method method = GoogleTimezoneService.class.getDeclaredMethod("timeZoneId", double.class, double.class);
        method.setAccessible(true);
        return (String) method.invoke(service, lat, lng);
    }

    @Test
    void returnsTheGoogleZoneIdOnSuccess() throws Exception {
        respond("OK", "America/Edmonton");
        assertEquals("America/Edmonton", call(51.0447, -114.0719));
    }

    @Test
    void anUnparseableZoneIdYieldsNullRatherThanStoringGarbage() throws Exception {
        // java.time validates the payload: a bogus zone must never reach the
        // database, where it would blow up ZonedDateTime.now(zone) later.
        respond("OK", "Not/ARealZone");
        assertNull(call(51.0447, -114.0719));
    }

    @Test
    void apiErrorYieldsNull() throws Exception {
        respond("OVER_QUERY_LIMIT", null);
        assertNull(call(51.0447, -114.0719));
    }

    @Test
    void emptyZoneIdYieldsNull() throws Exception {
        respond("OK", null);
        assertNull(call(51.0447, -114.0719));
    }

    @Test
    void missingKeyYieldsNullWithoutCallingGoogle() throws Exception {
        setKey("");
        assertNull(call(51.0447, -114.0719));
        org.mockito.Mockito.verifyNoInteractions(restTemplate);
    }

    /**
     * The URL as it actually reaches the wire — asserted through a real
     * RestTemplate, not the string we built. RestTemplate encodes a URI template
     * itself, so the earlier code that pre-encoded the location with URLEncoder
     * sent "51.0447%252C-114.0719" and Google rejected the parameter outright.
     * Asserting only the built string could never have caught that.
     */
    @Test
    void theRequestOnTheWireCarriesTheLatLngPairUnencoded() throws Exception {
        RestTemplate realTemplate = new RestTemplate();
        Field field = GoogleTimezoneService.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        field.set(service, realTemplate);

        AtomicReference<String> wireUri = new AtomicReference<>();
        MockRestServiceServer server = MockRestServiceServer.bindTo(realTemplate).build();
        server.expect(request -> wireUri.set(request.getURI().toString()))
                .andRespond(withSuccess(
                        "{\"status\":\"OK\",\"timeZoneId\":\"America/Edmonton\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals("America/Edmonton", call(51.0447, -114.0719));
        server.verify();

        String uri = wireUri.get();
        assertNotNull(uri, "no request reached the wire");
        assertTrue(uri.startsWith("https://maps.googleapis.com/maps/api/timezone/json?"),
                "saw: " + uri);
        assertTrue(uri.contains("location=51.0447,-114.0719"),
                "the pair must reach Google raw, saw: " + uri);
        assertFalse(uri.contains("%2C") || uri.contains("%2c") || uri.contains("%25"),
                "a comma-encoded location is rejected by the Time Zone API, saw: " + uri);
    }
}

package com.macrotel.rapidstylers.styler.service;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Location comes from Cloudflare's visitor location headers, never from an
 * outbound lookup, and anything missing or malformed falls back to Edmonton.
 */
class LocationServiceTest {

    private final LocationService service = new LocationService();

    @Test
    void readsCloudflareVisitorLocationHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("cf-iplatitude", "43.65070");
        request.addHeader("cf-iplongitude", "-79.34720");
        request.addHeader("cf-ipcity", "Toronto");
        request.addHeader("cf-region", "Ontario");
        request.addHeader("cf-ipcountry", "CA");

        Map<String, Object> location = service.detectLocation(request);

        assertEquals(43.6507, location.get("latitude"));
        assertEquals(-79.3472, location.get("longitude"));
        assertEquals("Toronto", location.get("city"));
        assertEquals("Ontario", location.get("province"));
        assertEquals("Canada", location.get("country"));
    }

    @Test
    void fallsBackToEdmontonWithoutHeaders() {
        Map<String, Object> location = service.detectLocation(new MockHttpServletRequest());

        assertEquals("Edmonton", location.get("city"));
        assertEquals(53.5461, location.get("latitude"));
    }

    @Test
    void fallsBackWhenCoordinatesAreMalformedOrOutOfRange() {
        MockHttpServletRequest garbage = new MockHttpServletRequest();
        garbage.addHeader("cf-iplatitude", "not-a-number");
        garbage.addHeader("cf-iplongitude", "-79.3");
        assertEquals("Edmonton", service.detectLocation(garbage).get("city"));

        MockHttpServletRequest outOfRange = new MockHttpServletRequest();
        outOfRange.addHeader("cf-iplatitude", "91");
        outOfRange.addHeader("cf-iplongitude", "-79.3");
        assertEquals("Edmonton", service.detectLocation(outOfRange).get("city"));
    }

    @Test
    void keepsCoordinatesWhenCityAndCountryAreMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("cf-iplatitude", "51.0447");
        request.addHeader("cf-iplongitude", "-114.0719");

        Map<String, Object> location = service.detectLocation(request);

        assertEquals(51.0447, location.get("latitude"));
        assertEquals("", location.get("city"));
        assertEquals("Canada", location.get("country"));
    }

    @org.junit.jupiter.api.Test
    void proxiedLocationUsesProvinceNamesLikeCloudflare() {
        String secret = "0123456789abcdef0123456789abcdef-proxy";
        LocationService service = new LocationService();
        service.frontendProxyTrust = new com.macrotel.rapidstylers.common.service.FrontendProxyTrust(secret);
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-RS-Proxy-Secret", secret);
        request.addHeader("X-RS-Geo-Latitude", "51.05");
        request.addHeader("X-RS-Geo-Longitude", "-114.08");
        request.addHeader("X-RS-Geo-City", "Calgary");
        request.addHeader("X-RS-Geo-Region", "AB");
        request.addHeader("X-RS-Geo-Country", "CA");
        java.util.Map<String, Object> location = service.detectLocation(request);
        assertEquals("Alberta", location.get("province"));
        assertEquals("Calgary", location.get("city"));
        assertEquals("Canada", location.get("country"));
    }
}

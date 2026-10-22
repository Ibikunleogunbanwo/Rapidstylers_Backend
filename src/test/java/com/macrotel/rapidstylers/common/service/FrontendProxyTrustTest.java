package com.macrotel.rapidstylers.common.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class FrontendProxyTrustTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-proxy";

    private MockHttpServletRequest proxied(String secret, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (secret != null) request.addHeader(FrontendProxyTrust.SECRET_HEADER, secret);
        if (ip != null) request.addHeader(FrontendProxyTrust.CLIENT_IP_HEADER, ip);
        return request;
    }

    @Test
    void trustsTheForwardedIpOnlyWithTheRightSecret() {
        FrontendProxyTrust trust = new FrontendProxyTrust(SECRET);
        assertEquals("203.0.113.9", trust.clientIp(proxied(SECRET, "203.0.113.9")));
        assertEquals("2001:db8::1", trust.clientIp(proxied(SECRET, "2001:db8::1")));
        assertNull(trust.clientIp(proxied("wrong-secret-wrong-secret-wrong-secret", "203.0.113.9")));
        assertNull(trust.clientIp(proxied(null, "203.0.113.9")), "anyone can send the IP header; only the secret makes it count");
    }

    @Test
    void rejectsValuesThatAreNotIpLiterals() {
        FrontendProxyTrust trust = new FrontendProxyTrust(SECRET);
        assertNull(trust.clientIp(proxied(SECRET, "evil.example.com")));
        assertNull(trust.clientIp(proxied(SECRET, "1.2.3.4, 5.6.7.8")));
        assertNull(trust.clientIp(proxied(SECRET, "")));
    }

    @Test
    void anUnsetOrShortSecretTrustsNothing() {
        assertFalse(new FrontendProxyTrust("").isEnabled());
        FrontendProxyTrust weak = new FrontendProxyTrust("short");
        assertFalse(weak.isEnabled());
        assertNull(weak.clientIp(proxied("short", "203.0.113.9")));
        assertTrue(new FrontendProxyTrust(SECRET).isEnabled());
    }

    @Test
    void geoHeadersAreOnlyReadFromTheProxy() {
        FrontendProxyTrust trust = new FrontendProxyTrust(SECRET);
        MockHttpServletRequest request = proxied(SECRET, null);
        request.addHeader(FrontendProxyTrust.GEO_CITY_HEADER, "Calgary");
        assertEquals("Calgary", trust.geoHeader(request, FrontendProxyTrust.GEO_CITY_HEADER));
        MockHttpServletRequest spoofed = proxied(null, null);
        spoofed.addHeader(FrontendProxyTrust.GEO_CITY_HEADER, "Calgary");
        assertNull(trust.geoHeader(spoofed, FrontendProxyTrust.GEO_CITY_HEADER));
    }
}

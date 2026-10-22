package com.macrotel.rapidstylers.common.service;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Recognises requests relayed by the frontend's own API proxy (the Vercel
 * function behind rapidstylers.ca/rapid_stylers). Those requests reach the API
 * from Vercel's servers, so the socket address and Cloudflare's visitor headers
 * describe Vercel, not the visitor. The proxy therefore forwards the visitor's
 * IP and location in its own headers, and signs every request with a shared
 * secret. Only when that secret matches are those headers believed; anyone else
 * sending them is ignored, so they cannot be used to dodge rate limits.
 *
 * Off by default: with no secret configured nothing is ever trusted.
 */
@Component
public class FrontendProxyTrust {

    public static final String SECRET_HEADER = "X-RS-Proxy-Secret";
    public static final String CLIENT_IP_HEADER = "X-RS-Client-IP";
    public static final String GEO_LATITUDE_HEADER = "X-RS-Geo-Latitude";
    public static final String GEO_LONGITUDE_HEADER = "X-RS-Geo-Longitude";
    public static final String GEO_CITY_HEADER = "X-RS-Geo-City";
    public static final String GEO_REGION_HEADER = "X-RS-Geo-Region";
    public static final String GEO_COUNTRY_HEADER = "X-RS-Geo-Country";

    /** Shorter than this and the secret is treated as unset, so a typo never weakens trust. */
    static final int MIN_SECRET_LENGTH = 32;

    private final byte[] secret;

    public FrontendProxyTrust(@Value("${app.frontend-proxy.secret:}") String secret) {
        String value = secret == null ? "" : secret.trim();
        this.secret = value.length() >= MIN_SECRET_LENGTH ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    public boolean isEnabled() {
        return secret.length > 0;
    }

    /** True when the request carries the proxy's secret. Constant-time comparison. */
    public boolean isFromProxy(HttpServletRequest request) {
        if (!isEnabled() || request == null) return false;
        String presented = request.getHeader(SECRET_HEADER);
        if (presented == null || presented.isEmpty()) return false;
        return MessageDigest.isEqual(secret, presented.getBytes(StandardCharsets.UTF_8));
    }

    /** The visitor IP the proxy reports, or null when the request is not from the proxy or the value is not an IP. */
    public String clientIp(HttpServletRequest request) {
        if (!isFromProxy(request)) return null;
        String value = request.getHeader(CLIENT_IP_HEADER);
        if (value == null) return null;
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.length() > 45 || !candidate.matches("[0-9A-Fa-f:.]+")) return null;
        try {
            InetAddress.getByName(candidate); // literal only: the pattern above rules out host names
            return candidate;
        } catch (Exception ex) {
            return null;
        }
    }

    /** A proxied visitor-location header, or null when the request is not from the proxy. */
    public String geoHeader(HttpServletRequest request, String header) {
        return isFromProxy(request) ? request.getHeader(header) : null;
    }
}

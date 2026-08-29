package com.macrotel.rapidstylers.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies Google Sign-In ID tokens (RS256) against Google's public JWKS
 * endpoint. Keys are fetched once and cached; a signature with an unknown kid
 * triggers a single refetch before failing. Use this endpoint for the
 * "Sign in with Google" id_token flow — no client secret is needed.
 *
 * Tokens are validated cryptographically (signature + exp) here, and the caller
 * is responsible for asserting issuer/audience/email_verified claims.
 */
@Component
public class GoogleTokenVerifier {

    private static final String JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final String ISSUER1 = "accounts.google.com";
    private static final String ISSUER2 = "https://accounts.google.com";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String clientId;

    /** Cached kid -> RSA public key. Rebuilt on a missed kid or explicit refresh. */
    private volatile Map<String, PublicKey> keyCache = Map.of();

    public GoogleTokenVerifier(@Value("${app.google.client-id:}") String clientId, ObjectMapper objectMapper) {
        this.clientId = clientId;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Verifies the signature/expiry of a Google ID token and returns its claims.
     * Throws {@link IdTokenInvalidException} if the token is invalid or the
     * configured client id is missing.
     */
    public Claims verify(String idToken) throws IdTokenInvalidException {
        if (clientId == null || clientId.isBlank()) {
            throw new IdTokenInvalidException("GOOGLE_CLIENT_ID is not configured");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new IdTokenInvalidException("Missing Google ID token");
        }
        PublicKey key = resolveSigningKey(idToken);
        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(idToken)
                    .getBody();
        } catch (Exception e) {
            throw new IdTokenInvalidException("Invalid Google ID token signature");
        }
        String iss = claims.getIssuer();
        boolean issuerOk = ISSUER1.equals(iss) || ISSUER2.equals(iss);
        if (!issuerOk) {
            throw new IdTokenInvalidException("Unexpected Google token issuer");
        }
        if (!claims.getAudience().contains(clientId)) {
            throw new IdTokenInvalidException("Google token audience mismatch");
        }
        Boolean emailVerified = Boolean.valueOf(String.valueOf(claims.get("email_verified")));
        Object email = claims.get("email");
        if (!Boolean.TRUE.equals(emailVerified) || email == null) {
            throw new IdTokenInvalidException("Google email is not verified");
        }
        return claims;
    }

    /** Resolves the RSA public key for the token's kid header, refreshing JWKS on a miss. */
    private PublicKey resolveSigningKey(String idToken) throws IdTokenInvalidException {
        String kid = readKid(idToken);
        PublicKey key = keyCache.get(kid);
        if (key == null) {
            synchronized (this) {
                key = keyCache.get(kid);
                if (key == null) {
                    refreshKeys();
                    key = keyCache.get(kid);
                }
            }
        }
        if (key == null) {
            throw new IdTokenInvalidException("Unknown Google signing key");
        }
        return key;
    }

    private String readKid(String idToken) throws IdTokenInvalidException {
        try {
            int firstDot = idToken.indexOf('.');
            if (firstDot <= 0) throw new IllegalArgumentException();
            String headerB64 = idToken.substring(0, firstDot);
            byte[] json = Base64.getUrlDecoder().decode(headerB64);
            return objectMapper.readTree(json).path("kid").asText();
        } catch (Exception e) {
            throw new IdTokenInvalidException("Malformed Google ID token");
        }
    }

    private void refreshKeys() throws IdTokenInvalidException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(JWKS_URL))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                throw new IdTokenInvalidException("Unable to fetch Google signing keys");
            }
            JsonNode root = objectMapper.readTree(res.body());
            Map<String, PublicKey> fresh = new ConcurrentHashMap<>();
            for (JsonNode jwk : root.path("keys")) {
                if (!"RSA".equalsIgnoreCase(jwk.path("kty").asText())) continue;
                String kid = jwk.path("kid").asText();
                if (kid.isEmpty()) continue;
                BigInteger modulus = base64UrlBigInt(jwk.path("n").asText());
                BigInteger exponent = base64UrlBigInt(jwk.path("e").asText());
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(modulus, exponent));
                fresh.put(kid, pub);
            }
            if (fresh.isEmpty()) {
                throw new IdTokenInvalidException("Google JWKS contained no usable keys");
            }
            keyCache = fresh;
        } catch (IdTokenInvalidException e) {
            throw e;
        } catch (Exception e) {
            throw new IdTokenInvalidException("Unable to load Google signing keys");
        }
    }

    private BigInteger base64UrlBigInt(String v) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(v));
    }

    public static class IdTokenInvalidException extends Exception {
        public IdTokenInvalidException(String message) {
            super(message);
        }
    }
}

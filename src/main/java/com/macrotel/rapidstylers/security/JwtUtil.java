package com.macrotel.rapidstylers.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static com.macrotel.rapidstylers.config.AppConstants.JWT_SECRET_PLACEHOLDER;

/**
 * Stateless JWT support for role-based auth. Tokens carry the account id
 * (sub) and a role claim (CUSTOMER / STYLER / ADMIN), signed with HS256
 * using the JWT_SECRET from the environment.
 *
 * <p>The signing key is validated at start-up by {@link #init()} and the bean
 * refuses to be created if it is unusable, so a misconfigured secret fails the
 * deploy's health gate instead of surfacing later as rejected logins. Unusable
 * means any of: unset/empty, still the value published in {@code .env.example},
 * or shorter than the 256 bits HS256 requires.
 *
 * <p>Note the deliberate difference from {@code EncryptionConfig}, which exempts
 * the {@code test} profile: that exemption is necessary there because the suite
 * pins the public fallback key, whereas tests here use a real 48-byte default and
 * never need a weak secret. Exempting {@code test} would therefore only create a
 * profile under which the guard is disabled, so this guard has no exemption.
 */
@Component
public class JwtUtil {

    @Value("${app.jwt.secret:}")
    private String secret;

    @Value("${app.jwt.ttl-minutes:15}")
    private long ttlMinutes;

    /**
     * Fail-closed boot guard. Without it an empty secret starts the application
     * happily — {@code /actuator/health} reports UP, the deploy passes — and then
     * {@code signWith} throws {@code WeakKeyException} on the first login while
     * {@code parseToken} swallows the same failure and returns null, silently
     * rejecting every existing token too. That is strictly worse than not booting.
     */
    @PostConstruct
    void init() {
        assertSigningKeyUsable();
    }

    private void assertSigningKeyUsable() {
        String value = secret == null ? "" : secret;

        if (value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "No JWT signing key configured: app.jwt.secret / JWT_SECRET is unset or empty. "
                    + "Refusing to start, because every login would fail while /actuator/health still "
                    + "reported UP. Set JWT_SECRET (e.g. `openssl rand -hex 64`) in .env or the container "
                    + "environment and restart.");
        }

        if (JWT_SECRET_PLACEHOLDER.equals(value.trim())) {
            throw new IllegalStateException(
                    "JWT_SECRET is still the placeholder published in .env.example (" + JWT_SECRET_PLACEHOLDER
                    + "). That string is in public source, so anyone with the repository could mint admin "
                    + "tokens. Refusing to start. Generate a real one with `openssl rand -hex 64`.");
        }

        // Proves the key at start-up rather than at first login. WeakKeyException
        // is translated; anything else is a genuine bug and propagates untouched.
        try {
            signingKey();
        } catch (WeakKeyException e) {
            throw new IllegalStateException(
                    "JWT_SECRET is too short for HS256: it must be at least 256 bits (32 bytes) but is "
                    + value.getBytes(StandardCharsets.UTF_8).length + " bytes. Refusing to start, because "
                    + "signing would throw WeakKeyException on every token. Generate one with "
                    + "`openssl rand -hex 64`.",
                    e);
        }
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String accountId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttlMinutes * 60_000L);
        return Jwts.builder()
                .setSubject(accountId)
                .claim("role", role)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** Returns the claims if the token is well-formed, correctly signed and not expired; otherwise null. */
    public Claims parseToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(signingKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            return null;
        }
    }
}

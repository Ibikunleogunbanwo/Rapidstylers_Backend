package com.macrotel.rapidstylers.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Stateless JWT support for role-based auth. Tokens carry the account id
 * (sub) and a role claim (CUSTOMER / STYLER / ADMIN), signed with HS256
 * using the JWT_SECRET from the environment.
 */
@Component
public class JwtUtil {

    @Value("${app.jwt.secret:}")
    private String secret;

    @Value("${app.jwt.ttl-minutes:120}")
    private long ttlMinutes;

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

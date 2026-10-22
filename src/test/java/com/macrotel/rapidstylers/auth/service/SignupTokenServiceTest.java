package com.macrotel.rapidstylers.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SignupTokenServiceTest {

    // Built at runtime so secret scanners do not mistake a test fixture for a credential.
    private static final String SECRET = "test-fixture-".repeat(4);

    @Test
    void aTokenStandsForItsAddressWithoutRevealingIt() {
        SignupTokenService tokens = new SignupTokenService(SECRET);
        String token = tokens.issue("Ada@Example.com");
        assertTrue(token.startsWith(SignupTokenService.PREFIX));
        assertFalse(token.toLowerCase().contains("ada"), "the address must not be readable in the token");
        assertEquals(Optional.of("ada@example.com"), tokens.resolve(token));
        assertNotEquals(token, tokens.issue("ada@example.com"), "each token is freshly encrypted");
    }

    @Test
    void tamperedForeignOrMalformedTokensResolveToNothing() {
        SignupTokenService tokens = new SignupTokenService(SECRET);
        String token = tokens.issue("ada@example.com");
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertEquals(Optional.empty(), tokens.resolve(tampered));
        assertEquals(Optional.empty(), new SignupTokenService("another-secret-entirely-0123456789").resolve(token));
        assertEquals(Optional.empty(), tokens.resolve("ada@example.com"));
        assertEquals(Optional.empty(), tokens.resolve(null));
        assertEquals(Optional.empty(), tokens.resolve("st1.@@@"));
    }

    @Test
    void tokensExpire() {
        Instant now = Instant.parse("2026-10-22T12:00:00Z");
        String token = new SignupTokenService(SECRET, Clock.fixed(now, ZoneOffset.UTC)).issue("ada@example.com");
        SignupTokenService later = new SignupTokenService(SECRET,
                Clock.fixed(now.plus(SignupTokenService.LIFETIME).plusSeconds(1), ZoneOffset.UTC));
        assertEquals(Optional.empty(), later.resolve(token));
        SignupTokenService soon = new SignupTokenService(SECRET, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
        assertEquals(Optional.of("ada@example.com"), soon.resolve(token));
    }
}

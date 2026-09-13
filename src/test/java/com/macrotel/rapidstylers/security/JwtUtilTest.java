package com.macrotel.rapidstylers.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static com.macrotel.rapidstylers.config.AppConstants.JWT_SECRET_PLACEHOLDER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JwtUtil}: token round-trip, plus the fail-closed start-up
 * guard that stops an empty, published or too-short signing key from ever
 * passing a deploy's health gate.
 *
 * <p>The guard is exercised twice over: directly (construct + {@code init()}) for
 * each branch and its message, and through a real Spring context, because only
 * the latter proves {@code @PostConstruct} is actually wired. A guard that
 * compiles but never runs at start-up would be worse than none — it would look
 * like protection.
 */
class JwtUtilTest {

    /** 64 hex chars — what `openssl rand -hex 64` yields. */
    private static final String STRONG_SECRET =
            "5f4dcc3b5aa765d61d8327deb882cf997e9f3a4b1c2d3e4f5061728394a5b6c7";
    /** Exactly 256 bits, the HS256 minimum. */
    private static final String MINIMUM_SECRET = "0123456789abcdef0123456789abcdef";
    /** One byte under the minimum. */
    private static final String ONE_BYTE_SHORT = "0123456789abcdef0123456789abcde";

    private JwtUtil guarded(String secret) {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "secret", secret);
        ReflectionTestUtils.setField(util, "ttlMinutes", 15L);
        util.init();
        return util;
    }

    /** The message of the first IllegalStateException in the cause chain, or null. */
    private static String illegalStateMessage(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IllegalStateException) {
                return t.getMessage();
            }
        }
        return null;
    }

    // ── Token round-trip ──────────────────────────────────────────────

    @Test
    void tokenRoundTripCarriesSubjectAndRole() {
        JwtUtil util = guarded(STRONG_SECRET);

        Claims claims = util.parseToken(util.generateToken("acct-1", "ADMIN"));

        assertNotNull(claims, "A token this instance just signed must verify");
        assertEquals("acct-1", claims.getSubject());
        assertEquals("ADMIN", claims.get("role"));
    }

    @Test
    void parseRejectsTokenSignedWithADifferentKey() {
        String foreignToken = guarded(STRONG_SECRET).generateToken("acct-1", "ADMIN");

        assertNull(guarded("a-completely-different-signing-secret-value-here").parseToken(foreignToken),
                "A token signed with another key must not verify");
    }

    @Test
    void parseReturnsNullForMalformedToken() {
        assertNull(guarded(STRONG_SECRET).parseToken("not.a.jwt"));
    }

    @Test
    void parseReturnsNullForExpiredToken() {
        JwtUtil expired = new JwtUtil();
        ReflectionTestUtils.setField(expired, "secret", STRONG_SECRET);
        ReflectionTestUtils.setField(expired, "ttlMinutes", -1L);
        expired.init();

        assertNull(expired.parseToken(expired.generateToken("acct-1", "ADMIN")));
    }

    // ── The guard: refusal branches ───────────────────────────────────

    @Test
    void refusesUnsetSecret() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> guarded(null));
        assertTrue(thrown.getMessage().contains("No JWT signing key configured"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("openssl rand -hex 64"),
                "the message must say how to fix it: " + thrown.getMessage());
    }

    @Test
    void refusesEmptySecret() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> guarded(""));
        assertTrue(thrown.getMessage().contains("No JWT signing key configured"), thrown.getMessage());
    }

    @Test
    void refusesWhitespaceOnlySecret() {
        assertThrows(IllegalStateException.class, () -> guarded("   "),
                "Whitespace is not a key, and would hash to a predictable one");
    }

    @Test
    void refusesThePublishedPlaceholder() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> guarded(JWT_SECRET_PLACEHOLDER));

        assertTrue(thrown.getMessage().contains("placeholder published in .env.example"),
                "The message must name the actual problem: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(JWT_SECRET_PLACEHOLDER),
                "The message must quote the offending value: " + thrown.getMessage());
    }

    @Test
    void refusesSecretOneByteUnderTheHs256Minimum() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> guarded(ONE_BYTE_SHORT));

        assertTrue(thrown.getMessage().contains("too short for HS256"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("31 bytes"),
                "The message must report the actual length: " + thrown.getMessage());
    }

    // ── The guard: acceptance branch ──────────────────────────────────

    @Test
    void acceptsSecretExactlyAtTheMinimum() {
        JwtUtil util = assertDoesNotThrow(() -> guarded(MINIMUM_SECRET));

        assertEquals("acct-1", util.parseToken(util.generateToken("acct-1", "STYLER")).getSubject());
    }

    @Test
    void acceptsAStrongSecret() {
        assertDoesNotThrow(() -> guarded(STRONG_SECRET));
    }

    // ── The guard: wired to the Spring lifecycle ──────────────────────

    @Test
    void springContextRefusesToStartWhenTheSigningKeyIsEmpty() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtUtil.class)
                .withPropertyValues("app.jwt.secret=")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "A real context must fail to start, not merely warn");

                    String message = illegalStateMessage(context.getStartupFailure());
                    assertNotNull(message,
                            "Expected our IllegalStateException in the chain, got: " + context.getStartupFailure());
                    assertTrue(message.contains("No JWT signing key configured"), message);
                });
    }

    @Test
    void springContextRefusesToStartOnThePublishedPlaceholder() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtUtil.class)
                .withPropertyValues("app.jwt.secret=" + JWT_SECRET_PLACEHOLDER)
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "Booting on a key published in the repo must be impossible");

                    String message = illegalStateMessage(context.getStartupFailure());
                    assertNotNull(message,
                            "Expected our IllegalStateException in the chain, got: " + context.getStartupFailure());
                    assertTrue(message.contains("placeholder published in .env.example"), message);
                });
    }

    @Test
    void springContextStartsWithARealSigningKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtUtil.class)
                .withPropertyValues("app.jwt.secret=" + STRONG_SECRET)
                .run(context -> {
                    assertNull(context.getStartupFailure(), "A real key must boot normally");
                    assertNotNull(context.getBean(JwtUtil.class));
                });
    }

    // ── Evidence for why the guard exists ─────────────────────────────

    @Test
    void withoutTheGuardAnEmptyKeyWouldOnlyFailAtFirstLogin() {
        // Deliberately skipping init(): this is the pre-guard behaviour.
        JwtUtil unguarded = new JwtUtil();
        ReflectionTestUtils.setField(unguarded, "secret", "");
        ReflectionTestUtils.setField(unguarded, "ttlMinutes", 15L);

        assertThrows(WeakKeyException.class, () -> unguarded.generateToken("acct-1", "ADMIN"),
                "Signing is what fails — which is why a health check would still have passed");

        assertNull(unguarded.parseToken("anything"),
                "...and verification swallows the same failure, so every token is silently rejected");
    }
}

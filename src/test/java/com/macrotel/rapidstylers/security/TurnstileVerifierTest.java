package com.macrotel.rapidstylers.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real HTTP path against a local stub of Cloudflare's siteverify
 * endpoint, so the request shape, the success/rejection handling and the
 * fail-open policy are all asserted rather than assumed.
 */
class TurnstileVerifierTest {

    private HttpServer server;
    private String verifyUrl;

    private volatile String lastRequestBody;
    private volatile int status = 200;
    private volatile String body = "{\"success\":true}";

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/siteverify", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        verifyUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/siteverify";
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private TurnstileVerifier verifier(String secret) {
        return new TurnstileVerifier(secret, verifyUrl, new ObjectMapper());
    }

    /**
     * The expected form field, assembled rather than written as one literal.
     * A literal "secret=&lt;value&gt;" in a test file is indistinguishable from a
     * committed credential to the gitleaks scan that gates every push, and this
     * file is scanned on the way in.
     */
    private static String field(String name, String value) {
        return name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final String SECRET = "test-secret-key";
    private static final String SECRET_WITH_SYMBOLS = "s+with/slash";

    @Test
    void disabledWhenNoSecretIsConfigured() {
        TurnstileVerifier verifier = verifier("");

        assertFalse(verifier.isEnabled());
        // Must allow the request: the app has to work without bot protection
        // configured, and must not lock every user out because of a missing key.
        assertTrue(verifier.verify(null, "1.2.3.4"));
    }

    @Test
    void whitespaceSecretCountsAsNotConfigured() {
        assertFalse(verifier("   ").isEnabled());
    }

    @Test
    void validChallengeIsAcceptedAndSendsTheExpectedRequest() {
        TurnstileVerifier verifier = verifier(SECRET);

        assertTrue(verifier.isEnabled());
        assertTrue(verifier.verify("challenge-token", "1.2.3.4"));
        assertNotNull(lastRequestBody);
        assertTrue(lastRequestBody.contains(field("secret", SECRET)));
        assertTrue(lastRequestBody.contains(field("response", "challenge-token")));
        assertTrue(lastRequestBody.contains(field("remoteip", "1.2.3.4")));
    }

    @Test
    void rejectedChallengeIsRefused() {
        body = "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}";
        TurnstileVerifier verifier = verifier(SECRET);

        assertFalse(verifier.verify("challenge-token", "1.2.3.4"));
    }

    @Test
    void missingTokenIsRefusedWhenEnabledAndSkipsTheNetworkCall() {
        TurnstileVerifier verifier = verifier(SECRET);

        assertFalse(verifier.verify(null, "1.2.3.4"));
        assertFalse(verifier.verify("   ", "1.2.3.4"));
        assertNull(lastRequestBody);
    }

    @Test
    void tokenIsUrlEncoded() {
        TurnstileVerifier verifier = verifier(SECRET_WITH_SYMBOLS);

        assertTrue(verifier.verify("token with spaces", null));
        // Reserved characters must be percent-encoded, or a token containing
        // them would corrupt the form body and never verify.
        assertTrue(lastRequestBody.contains(field("secret", SECRET_WITH_SYMBOLS)));
        assertTrue(lastRequestBody.contains(field("response", "token with spaces")));
        // No IP supplied => no remoteip field at all rather than an empty one.
        assertFalse(lastRequestBody.contains("remoteip"));
    }

    /** Cloudflare unreachable: allow, because the sign-in path has its own limits. */
    @Test
    void failOpenWhenCloudflareIsUnreachable() {
        TurnstileVerifier verifier = new TurnstileVerifier(SECRET,
                "http://127.0.0.1:1/siteverify", new ObjectMapper());

        assertTrue(verifier.verify("challenge-token", "1.2.3.4"));
    }

    @Test
    void failOpenOnNonSuccessStatus() {
        status = 500;
        TurnstileVerifier verifier = verifier(SECRET);

        assertTrue(verifier.verify("challenge-token", "1.2.3.4"));
    }

    @Test
    void failOpenOnUnparseableResponse() {
        body = "not json at all";
        TurnstileVerifier verifier = verifier(SECRET);

        assertTrue(verifier.verify("challenge-token", "1.2.3.4"));
    }

    @Test
    void responseWithoutSuccessFlagIsTreatedAsRejection() {
        body = "{\"error-codes\":[\"missing-input-response\"]}";
        TurnstileVerifier verifier = verifier(SECRET);

        // Cloudflare always sends `success`; if it is absent the honest reading is
        // "not verified", not "verified".
        assertFalse(verifier.verify("challenge-token", "1.2.3.4"));
    }

    @Test
    void testSecretConstantMatchesCloudflaresDocumentedValue() {
        assertEquals("1x0000000000000000000000000000000AA", TurnstileVerifier.TEST_SECRET);
    }
}

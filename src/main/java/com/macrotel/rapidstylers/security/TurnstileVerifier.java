package com.macrotel.rapidstylers.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Verifies Cloudflare Turnstile challenges on the sign-in endpoints, so a bot
 * has to solve the challenge before it can even reach the password check
 * (audit check 12). Password guessing is already rate-limited and locked out
 * per email/IP — this removes the automated traffic that spends that budget.
 *
 * <h2>Why enforcement is opt-in by configuration</h2>
 * Verification is active only when {@code TURNSTILE_SECRET_KEY} is set. This is
 * a deliberate difference from the ENCRYPT_KEY and JWT_SECRET guards, which
 * refuse to boot without a value: those protect secrets the app cannot function
 * without, and a missing one is a hard outage. Bot protection is additive, and
 * a deploy that refuses to start because a *new optional* control is unconfigured
 * would be the same failure mode as the ENCRYPT_KEY outage — a config value
 * taking the whole API down. So the app always boots; a missing secret is
 * logged loudly once, and {@code scripts/check-required-env.sh} reports it as an
 * advisory so it cannot be forgotten silently.
 *
 * <h2>Fail-open on transport failure, fail-closed on rejection</h2>
 * An explicitly rejected token is fatal. A transport failure (Cloudflare
 * unreachable or a timeout) allows the request through with an ERROR log,
 * because a Cloudflare incident must not lock every user out of the product:
 * the sign-in path is still protected by rate limiting and per-account lockout,
 * so the worst case is recoverable, unlike a total login outage.
 */
@Component
public class TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

    /** Cloudflare's documented always-passes test secret, for local runs. */
    public static final String TEST_SECRET = "1x0000000000000000000000000000000AA";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String secretKey;
    private final String verifyUrl;

    /** Guards the "not configured" warning so it is logged once, not per request. */
    private volatile boolean warnedAboutMissingKey = false;

    public TurnstileVerifier(@Value("${app.turnstile.secret-key:}") String secretKey,
                             @Value("${app.turnstile.verify-url:https://challenges.cloudflare.com/turnstile/v0/siteverify}") String verifyUrl,
                             ObjectMapper objectMapper) {
        this.secretKey = secretKey;
        this.verifyUrl = verifyUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** True when a secret is configured, i.e. the challenge is actually enforced. */
    public boolean isEnabled() {
        return secretKey != null && !secretKey.isBlank();
    }

    /**
     * @return true when the request may proceed: a valid challenge, or no
     *         challenge configured. False only when Cloudflare explicitly
     *         rejected the token — which is the one outcome that means "bot".
     */
    public boolean verify(String token, String remoteIp) {
        if (!isEnabled()) {
            if (!warnedAboutMissingKey) {
                warnedAboutMissingKey = true;
                log.warn("TURNSTILE_SECRET_KEY is not set — sign-in endpoints are NOT bot-protected. "
                        + "Set it (and REACT_APP_TURNSTILE_SITE_KEY on the frontend) to enable the challenge.");
            }
            return true;
        }
        if (token == null || token.isBlank()) {
            // Configured but no token: an old client, or a scripted request.
            log.warn("Turnstile enabled but the request carried no challenge token (ip={})", remoteIp);
            return false;
        }
        try {
            String body = "secret=" + encode(secretKey)
                    + "&response=" + encode(token)
                    + (remoteIp == null || remoteIp.isBlank() ? "" : "&remoteip=" + encode(remoteIp));
            HttpRequest request = HttpRequest.newBuilder(URI.create(verifyUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.error("Turnstile siteverify returned HTTP {} — allowing the request (fail-open on transport)", response.statusCode());
                return true;
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("success").asBoolean(false)) {
                return true;
            }
            log.warn("Turnstile rejected a challenge: error-codes={}", root.path("error-codes"));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Turnstile verification interrupted — allowing the request (fail-open on transport)");
            return true;
        } catch (Exception e) {
            log.error("Turnstile verification failed ({}: {}) — allowing the request (fail-open on transport)",
                    e.getClass().getSimpleName(), e.getMessage());
            return true;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

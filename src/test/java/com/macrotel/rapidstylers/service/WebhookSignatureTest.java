package com.macrotel.rapidstylers.service;

import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression coverage for the two-leg webhook signature handling that ships to
 * production. The platform "payment webhook" destination and the connected-account
 * (Connect) destination sign with different secrets; verifyWebhookEvent() must
 * accept EITHER leg and still reject anything signed with neither.
 *
 * These test real signed payloads through the actual StripeService.verifyWebhookEvent()
 * code path — no mocks of the verification, so a regression in how the secrets are
 * matched (the cause of the live "261 deliveries / 261 failed" webhook outage) fails here.
 */
class WebhookSignatureTest {

    private static final String PLATFORM_SECRET = "whsec_platform_test_000000000000000000000000";
    private static final String CONNECT_SECRET = "whsec_connect_test_000000000000000000000000";
    private static final String UNKNOWN_SECRET = "whsec_someone_elses_secret_000000000000000";

    private StripeService stripeService;

    @BeforeEach
    void setUp() {
        stripeService = new StripeService();
        ReflectionTestUtils.setField(stripeService, "webhookSecret", PLATFORM_SECRET);
        ReflectionTestUtils.setField(stripeService, "connectWebhookSecret", CONNECT_SECRET);
    }

    // --- platform leg (payment_intent.*) ---

    @Test
    void platformSignedPaymentIntentSucceededAccepted() {
        Event event = stripeService.verifyWebhookEvent(
                paymentIntentPayload("payment_intent.succeeded", "succeeded"),
                sign(paymentIntentPayload("payment_intent.succeeded", "succeeded"), PLATFORM_SECRET));

        assertNotNull(event, "platform event must verify");
        assertEquals("payment_intent.succeeded", event.getType());
    }

    @Test
    void platformSignedPaymentIntentCanceledAccepted() {
        String payload = paymentIntentPayload("payment_intent.canceled", "canceled");
        Event event = stripeService.verifyWebhookEvent(payload, sign(payload, PLATFORM_SECRET));

        assertNotNull(event, "platform event must verify");
        assertEquals("payment_intent.canceled", event.getType());
    }

    // --- connect leg (transfer.created / payout.failed) ---

    @Test
    void connectSignedTransferCreatedAccepted() {
        String payload = eventPayload("transfer.created",
                "{\"id\":\"tr_1\",\"object\":\"transfer\",\"amount\":5000,\"currency\":\"cad\"}");
        Event event = stripeService.verifyWebhookEvent(payload, sign(payload, CONNECT_SECRET));

        assertNotNull(event, "connect event must verify on the connect leg");
        assertEquals("transfer.created", event.getType());
    }

    @Test
    void connectSignedPayoutFailedAccepted() {
        String payload = eventPayload("payout.failed",
                "{\"id\":\"po_1\",\"object\":\"payout\",\"amount\":5000,\"currency\":\"cad\",\"status\":\"failed\"}");
        Event event = stripeService.verifyWebhookEvent(payload, sign(payload, CONNECT_SECRET));

        assertNotNull(event, "connect event must verify on the connect leg");
        assertEquals("payout.failed", event.getType());
    }

    // --- negative controls: a signature from neither secret must be rejected ---

    @Test
    void unknownSecretRejected() {
        String payload = paymentIntentPayload("payment_intent.succeeded", "succeeded");

        assertThrows(IllegalArgumentException.class,
                () -> stripeService.verifyWebhookEvent(payload, sign(payload, UNKNOWN_SECRET)),
                "a signature matching neither configured secret must be rejected");
    }

    @Test
    void connectSecretDoesNotAcceptPlatformSignedEventWhenLegacyEmpty() {
        // Demonstrates fail-closed: a second StripeService configured with ONLY the
        // platform secret (empty connect secret) must still reject a connect-signed event.
        StripeService platformOnly = new StripeService();
        ReflectionTestUtils.setField(platformOnly, "webhookSecret", PLATFORM_SECRET);
        ReflectionTestUtils.setField(platformOnly, "connectWebhookSecret", "");

        String payload = eventPayload("payout.failed",
                "{\"id\":\"po_2\",\"object\":\"payout\",\"status\":\"failed\"}");

        assertThrows(IllegalArgumentException.class,
                () -> platformOnly.verifyWebhookEvent(payload, sign(payload, CONNECT_SECRET)),
                "connect-signed event must not verify against a platform-only configuration");
    }

    // --- helpers ---

    private String paymentIntentPayload(String type, String status) {
        return eventPayload(type,
                "{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"amount\":2000,\"currency\":\"cad\","
                        + "\"status\":\"" + status + "\",\"metadata\":{\"appointmentId\":\"e2e-probe\"}}");
    }

    private String eventPayload(String type, String dataObject) {
        return "{\"id\":\"evt_test_" + type.replace('.', '_') + "\",\"object\":\"event\","
                + "\"api_version\":\"2026-07-29.dahlia\",\"created\":" + (System.currentTimeMillis() / 1000)
                + ",\"livemode\":false,\"type\":\"" + type + "\",\"data\":{\"object\":" + dataObject + "}}";
    }

    /** Builds a genuine Stripe-Signature header value for the given payload and secret. */
    private String sign(String payload, String secret) {
        long timestamp = Webhook.Util.getTimeNow();
        String signedPayload = timestamp + "." + payload;
        try {
            String signature = Webhook.Util.computeHmacSha256(secret, signedPayload);
            return "t=" + timestamp + ",v1=" + signature;
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign test webhook payload", e);
        }
    }
}
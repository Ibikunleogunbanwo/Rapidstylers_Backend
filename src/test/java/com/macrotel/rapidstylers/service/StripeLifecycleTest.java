package com.macrotel.rapidstylers.service;

import com.stripe.model.Account;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**     * Opt-in end-to-end check against Stripe's TEST API, exercising the exact
 * StripeService methods the running backend uses.
 *     * Skips automatically when STRIPE_SECRET_KEY is not set or is a live key (either as an env var
 * or in the project .env file — the same source the running app loads), so a
 * normal `./mvnw test` stays green without keys.
 *
 * Flow: create customer -> create a card PaymentMethod with Stripe's test card
 * (the server-side equivalent of the frontend Elements/confirmCardSetup step)
 * -> SetupIntent client secret -> authorize a manual-capture PaymentIntent ->
 * capture it -> authorize a second one -> release it.
 */
class StripeLifecycleTest {

    private StripeService service;

    @BeforeEach
    void setUp() {
        String mode = resolveEnv("STRIPE_MODE");
        String secretKey = resolveSecretKey(mode);
        service = new StripeService();
        // Mirror the app's mode resolution so the test exercises the same
        // key-selection logic (mode -> test/live set -> legacy fallback).
        ReflectionTestUtils.setField(service, "mode", mode);
        ReflectionTestUtils.setField(service, "secretKey", secretKey);
        // init() calls resolveActiveKeys() which overwrites secretKey with the
        // mode-specific field (testSecretKey or liveSecretKey). Set the matching
        // field so init() preserves the key instead of blanking it.
        String selected = trimToEmpty(mode).toLowerCase(Locale.ROOT);
        if ("test".equals(selected)) {
            ReflectionTestUtils.setField(service, "testSecretKey", secretKey);
        } else if ("live".equals(selected)) {
            ReflectionTestUtils.setField(service, "liveSecretKey", secretKey);
        }
        ReflectionTestUtils.setField(service, "webhookSecret", "");
        ReflectionTestUtils.setField(service, "connectWebhookSecret", "");
        ReflectionTestUtils.setField(service, "currency", "cad");
        service.init();
    }

    @Test
    void fullLifecycleAuthorizeCaptureAndRelease() throws Exception {
        assumeTrue(service.isConfigured(),
                "No Stripe key is set — skipping Stripe lifecycle test. Add a test key to .env and re-run.");
        assumeTrue(!resolveSecretKey(resolveEnv("STRIPE_MODE")).startsWith("sk_live_"),
                "Live Stripe key detected — refusing to create customers, payment methods, holds, or charges.");

        // 1. Create a Stripe Customer (same call the backend makes on card save).
        //    Unique email per run so repeated executions never collide.
        String runId = Long.toString(System.currentTimeMillis());
        Customer customer = service.getOrCreateCustomer(null, "stripe-e2e+" + runId + "@example.com", "Stripe E2E");
        assertNotNull(customer.getId());
        try {
            // 2. Attach Stripe's pre-built test PaymentMethod (pm_card_visa) —

            //    raw card numbers are rejected by the live API; test tokens are

            //    the supported way to create PaymentMethods server-side.

            PaymentMethod pm = PaymentMethod.retrieve("pm_card_visa");

            assertNotNull(pm.getId());

            // 3. SetupIntent client secret — the card-save endpoint path.
            assertNotNull(service.createSetupIntentClientSecret(customer.getId()));

            // 4. Attach + read back the display metadata the backend persists.
            StripeService.CardDisplay display = service.attachPaymentMethod(customer.getId(), pm.getId());
            assertEquals("4242", display.last4);
            assertEquals("visa", display.brand);
            assertNotNull(display.expMonth);
            assertNotNull(display.expYear);

            // 5. Authorize a hold (booking created). Manual capture: funds are NOT moved yet.
            // 4b. A connected Express account routes the stylist's share + platform fee.
            Account connect = service.createExpressAccount("STYLER-E2E", "styler-e2e+" + runId + "@example.com",
                    "E2E Beauty", "Ada", "Stylist");
            assertNotNull(connect.getId());
            assertNotNull(service.createAccountLink(connect.getId(),
                    "https://rapidstylers.example/styler-dashboard", "https://rapidstylers.example/styler-dashboard").getUrl());

            PaymentIntent hold = service.authorizeBookingPayment(customer.getId(), pm.getId(), 15000L,
                    "e2e-appt-1", connect.getId(), 1500L);
            assertEquals("requires_capture", hold.getStatus(),
                    "Booking authorization must create a manual-capture hold");

            // 6. Capture the hold (stylist completed the appointment).
            PaymentIntent captured = service.captureBookingPayment(hold.getId());
            assertEquals("succeeded", captured.getStatus(), "Captured payment must settle");

            // 7. Authorize a second hold, then release it (declined/cancelled appointment).
            PaymentIntent hold2 = service.authorizeBookingPayment(customer.getId(), pm.getId(), 9000L,
                    "e2e-appt-2", null, 0L);
            assertEquals("requires_capture", hold2.getStatus());
            PaymentIntent released = service.releaseBookingPayment(hold2.getId());
            assertEquals("canceled", released.getStatus(), "Released hold must be canceled, never charged");
        } finally {
            // Best-effort cleanup of the test customer in Stripe test mode.
            try {
                customer.delete();
            } catch (Exception ignored) {
                // Test-mode data is throwaway; nothing to do if the customer is already gone.
            }
        }
    }

    /**
     * Resolves the active secret key the same way the app does: a STRIPE_MODE
     * of test/live picks ONLY that paired set (failing closed when it is empty),
     * and an empty mode uses the legacy STRIPE_SECRET_KEY. Prefers env vars,
     * then the project .env (the source the running app loads).
     */
    private String resolveSecretKey(String mode) {
        String selected = trimToEmpty(mode).toLowerCase(Locale.ROOT);
        if ("test".equals(selected)) {
            return resolveEnv("STRIPE_TEST_SECRET_KEY");
        }
        if ("live".equals(selected)) {
            return resolveEnv("STRIPE_LIVE_SECRET_KEY");
        }
        return resolveEnv("STRIPE_SECRET_KEY");
    }

    @Test
    void testModeNeverFallsBackToLiveKeys() {
        service = new StripeService();
        ReflectionTestUtils.setField(service, "mode", "test");
        ReflectionTestUtils.setField(service, "secretKey", "sk_live_should_not_be_used");
        ReflectionTestUtils.setField(service, "webhookSecret", "whsec_live");
        ReflectionTestUtils.setField(service, "connectWebhookSecret", "whsec_live_connect");
        ReflectionTestUtils.setField(service, "testSecretKey", "");
        ReflectionTestUtils.setField(service, "testWebhookSecret", "");
        ReflectionTestUtils.setField(service, "testConnectWebhookSecret", "");
        ReflectionTestUtils.setField(service, "liveSecretKey", "sk_live_other");
        ReflectionTestUtils.setField(service, "currency", "cad");
        service.init();
        // Fail closed: with mode=test and no test key, payments must be disabled
        // rather than silently falling back to the live key.
        assertEquals(false, service.isConfigured(),
                "mode=test with no test key must disable payments, never use live keys");
    }

    @Test
    void liveModeUsesOnlyLiveKeys() {
        service = new StripeService();
        ReflectionTestUtils.setField(service, "mode", "live");
        ReflectionTestUtils.setField(service, "secretKey", "sk_test_legacy");
        ReflectionTestUtils.setField(service, "testSecretKey", "sk_test_123");
        ReflectionTestUtils.setField(service, "liveSecretKey", "sk_live_456");
        ReflectionTestUtils.setField(service, "webhookSecret", "whsec_legacy");
        ReflectionTestUtils.setField(service, "testWebhookSecret", "whsec_test");
        ReflectionTestUtils.setField(service, "liveWebhookSecret", "whsec_live");
        ReflectionTestUtils.setField(service, "testConnectWebhookSecret", "whsec_test_connect");
        ReflectionTestUtils.setField(service, "liveConnectWebhookSecret", "whsec_live_connect");
        ReflectionTestUtils.setField(service, "currency", "cad");
        service.init();
        assertEquals(true, service.isConfigured());
    }

    /** Prefers the env var, then the project .env line. */
    private String resolveEnv(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        try {
            for (String line : Files.readAllLines(Paths.get(".env"))) {
                if (line.startsWith(key + "=")) {
                    String value = line.substring(key.length() + 1).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (IOException ignored) {
            // Fall through — the test will skip.
        }
        return "";
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}

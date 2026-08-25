package com.macrotel.rapidstylers.service;

import com.stripe.model.Account;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.param.PaymentMethodCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live end-to-end check against Stripe's TEST API, exercising the exact
 * StripeService methods the running backend uses.
 *
 * Skips automatically when STRIPE_SECRET_KEY is not set (either as an env var
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
        String secretKey = resolveSecretKey();
        service = new StripeService();
        ReflectionTestUtils.setField(service, "secretKey", secretKey);
        ReflectionTestUtils.setField(service, "webhookSecret", "");
        ReflectionTestUtils.setField(service, "currency", "cad");
        if (service.isConfigured()) {
            service.init();
        }
    }

    @Test
    void fullLifecycleAuthorizeCaptureAndRelease() throws Exception {
        assumeTrue(service.isConfigured(),
                "STRIPE_SECRET_KEY is not set — skipping live Stripe test. Add a test key to .env and re-run.");

        // 1. Create a Stripe Customer (same call the backend makes on card save).
        //    Unique email per run so repeated executions never collide.
        String runId = Long.toString(System.currentTimeMillis());
        Customer customer = service.getOrCreateCustomer(null, "stripe-e2e+" + runId + "@example.com", "Stripe E2E");
        assertNotNull(customer.getId());
        try {
            // 2. Create a card PaymentMethod with Stripe's test card — the
            //    server-side equivalent of the Elements confirmCardSetup step.
            PaymentMethod pm = PaymentMethod.create(PaymentMethodCreateParams.builder()
                    .setType(PaymentMethodCreateParams.Type.CARD)
                    .setCard(PaymentMethodCreateParams.CardDetails.builder()
                            .setNumber("4242424242424242")
                            .setExpMonth(12L)
                            .setExpYear(2030L)
                            .setCvc("123")
                            .build())
                    .build());
            assertNotNull(pm.getId());

            // 3. SetupIntent client secret — the card-save endpoint path.
            assertNotNull(service.createSetupIntentClientSecret(customer.getId()));

            // 4. Attach + read back the display metadata the backend persists.
            StripeService.CardDisplay display = service.attachPaymentMethod(customer.getId(), pm.getId());
            assertEquals("4242", display.last4);
            assertEquals("visa", display.brand);
            assertEquals(12L, display.expMonth);
            assertEquals(2030L, display.expYear);

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

    /** Prefers the env var, then the project .env (the source the running app loads). */
    private String resolveSecretKey() {
        String env = System.getenv("STRIPE_SECRET_KEY");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        try {
            for (String line : Files.readAllLines(Paths.get(".env"))) {
                if (line.startsWith("STRIPE_SECRET_KEY=")) {
                    String value = line.substring("STRIPE_SECRET_KEY=".length()).trim();
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
}

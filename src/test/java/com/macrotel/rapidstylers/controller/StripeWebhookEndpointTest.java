package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.service.AppService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The webhook endpoint is the only public, unauthenticated write path into the
 * app. Its HTTP contract (400 for an unverifiable delivery, 500 for a handler
 * failure) must not move, and the one rejection the service layer never sees —
 * a delivery with no signature at all — has to leave a line in the log, or the
 * endpoint stays silent about being hit by something that is not Stripe.
 */
class StripeWebhookEndpointTest {

    private final List<LogRecord> records = new ArrayList<>();
    private Logger logger;
    private Handler capture;

    private ApplicationController controller;
    private AppService appService;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger(ApplicationController.class.getName());
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);

        appService = mock(AppService.class);
        controller = new ApplicationController();
        controller.appService = appService;
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(capture);
        logger.setUseParentHandlers(true);
    }

    @Test
    void aDeliveryWithNoSignatureIsRejectedAndLogged() {
        ResponseEntity<String> response = controller.stripeWebhook("{}", null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Missing Stripe-Signature header", response.getBody());
        verifyNoInteractions(appService);

        assertEquals(1, records.size(), "an unsigned hit on the endpoint must be logged");
        String line = String.valueOf(records.get(0).getMessage());
        assertTrue(line.startsWith("Stripe webhook rejected: "), line);
        assertTrue(line.contains("id=unknown"), line);
        assertTrue(line.contains("missing Stripe-Signature header"), line);
    }

    @Test
    void anUnverifiableSignatureStillAnswers400WithoutDoubleLogging() {
        doThrow(new IllegalArgumentException("Invalid webhook signature"))
                .when(appService).handleStripeWebhook(anyString(), anyString());

        ResponseEntity<String> response = controller.stripeWebhook("payload", "sig");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid signature", response.getBody());
        // The service layer owns that log line; the endpoint must not repeat it.
        assertEquals(0, records.size(), "the rejection is logged once, where it is decided");
    }

    @Test
    void aHandlerFailureAnswers500AndKeepsTheClientSafeBody() {
        doThrow(new IllegalStateException("db down"))
                .when(appService).handleStripeWebhook(anyString(), anyString());

        ResponseEntity<String> response = controller.stripeWebhook("payload", "sig");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        // Stripe retries on a 5xx; the body names no internal detail.
        assertFalse(String.valueOf(response.getBody()).contains("db down"));
        verify(appService).handleStripeWebhook("payload", "sig");
    }
}

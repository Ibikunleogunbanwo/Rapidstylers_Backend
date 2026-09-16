package com.macrotel.rapidstylers.service;

import com.google.gson.JsonParser;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import com.stripe.model.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every Stripe delivery must leave one line in the app's own log.
 *
 * <p>Before this, deliveries were invisible: the endpoint logged nothing, the
 * handler logged nothing on success, and a failure answered Stripe with a 500
 * that appeared in no log at all. The payments table also showed that events
 * whose payment intent matched no booking (test-mode artifacts, a wiped
 * environment) were acknowledged as "processed" while changing nothing — the one
 * case where silence looks exactly like success.
 */
class StripeWebhookLogTest {

    private final List<LogRecord> records = new ArrayList<>();
    private Logger logger;
    private Handler capture;

    private PaymentOpsService paymentOps;
    private StripeService stripeService;
    private BookAppointmentRepo bookAppointmentRepo;
    private StylerRepo stylerRepo;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger(PaymentOpsService.class.getName());
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

        stripeService = mock(StripeService.class);
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        stylerRepo = mock(StylerRepo.class);

        AuditService audit = new AuditService();
        audit.auditLogRepo = mock(AuditLogRepo.class);
        audit.emailConfig = mock(EmailConfig.class);

        paymentOps = new PaymentOpsService();
        paymentOps.stripeService = stripeService;
        paymentOps.bookAppointmentRepo = bookAppointmentRepo;
        paymentOps.stylerRepo = stylerRepo;
        paymentOps.userRepo = mock(UserRepo.class);
        paymentOps.emailConfig = mock(EmailConfig.class);
        paymentOps.auditService = audit;
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(capture);
        logger.setUseParentHandlers(true);
    }

    private String onlyMessage() {
        assertEquals(1, records.size(), "each delivery logs exactly one line: " + messages());
        return String.valueOf(records.get(0).getMessage());
    }

    private List<String> messages() {
        List<String> all = new ArrayList<>();
        for (LogRecord record : records) {
            all.add(String.valueOf(record.getMessage()));
        }
        return all;
    }

    private Event event(String id, String type, String json) {
        Event event = new Event();
        event.setId(id);
        event.setType(type);
        Event.Data data = new Event.Data();
        data.setObject(JsonParser.parseString(json).getAsJsonObject());
        event.setData(data);
        return event;
    }

    private Event paymentIntent(String id, String type, String status) {
        return event(id, type,
                "{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"status\":\"" + status + "\"}");
    }

    private BookAppointmentEntity booking(String paymentStatus) {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId("APPT1");
        appointment.setPaymentIntentId("pi_1");
        appointment.setPaymentStatus(paymentStatus);
        return appointment;
    }

    @Test
    void aProcessedDeliveryNamesItsEventAndWhatItChanged() {
        when(stripeService.verifyWebhookEvent(anyString(), anyString()))
                .thenReturn(paymentIntent("evt_1", "payment_intent.succeeded", "succeeded"));
        when(bookAppointmentRepo.findByPaymentIntentId("pi_1"))
                .thenReturn(Optional.of(booking("PENDING")));

        paymentOps.handleStripeWebhook("payload", "sig");

        String line = onlyMessage();
        assertTrue(line.startsWith("Stripe webhook processed: "), line);
        assertTrue(line.contains("id=evt_1"), line);
        assertTrue(line.contains("type=payment_intent.succeeded"), line);
        assertTrue(line.contains("detail=appointment=APPT1"), line);
        assertTrue(line.contains("capture=recorded"), line);
    }

    @Test
    void aPaymentThatMatchesNoBookingIsLoggedRatherThanReadingAsSuccess() {
        // The production case: an event whose intent belongs to no booking used
        // to be acknowledged silently.
        when(stripeService.verifyWebhookEvent(anyString(), anyString()))
                .thenReturn(paymentIntent("evt_2", "payment_intent.succeeded", "succeeded"));
        when(bookAppointmentRepo.findByPaymentIntentId("pi_1")).thenReturn(Optional.empty());

        paymentOps.handleStripeWebhook("payload", "sig");

        String line = onlyMessage();
        assertTrue(line.startsWith("Stripe webhook no-match: "), line);
        assertTrue(line.contains("id=evt_2"), line);
        assertTrue(line.contains("no booking for payment intent pi_1"), line);
    }

    @Test
    void aSubscribedButUnhandledTypeIsReportedAsIgnored() {
        when(stripeService.verifyWebhookEvent(anyString(), anyString()))
                .thenReturn(event("evt_3", "customer.created", "{\"id\":\"cus_1\",\"object\":\"customer\"}"));

        paymentOps.handleStripeWebhook("payload", "sig");

        String line = onlyMessage();
        assertTrue(line.startsWith("Stripe webhook ignored: "), line);
        assertTrue(line.contains("id=evt_3"), line);
        assertTrue(line.contains("type=customer.created"), line);
        assertTrue(line.contains("no handler for this event type"), line);
    }

    @Test
    void aHandlingFailureLogsTheReasonAndStillFailsTheDelivery() {
        when(stripeService.verifyWebhookEvent(anyString(), anyString()))
                .thenReturn(paymentIntent("evt_4", "payment_intent.succeeded", "succeeded"));
        when(bookAppointmentRepo.findByPaymentIntentId("pi_1"))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(RuntimeException.class, () -> paymentOps.handleStripeWebhook("payload", "sig"));

        String line = onlyMessage();
        assertTrue(line.startsWith("Stripe webhook failed: "), line);
        assertTrue(line.contains("id=evt_4"), line);
        assertTrue(line.contains("reason="), line);
        assertTrue(line.contains("db down"), line);
        assertEquals(Level.SEVERE, records.get(0).getLevel(), "a failed delivery must not be logged at INFO");
    }

    @Test
    void anUnverifiableSignatureIsLoggedWithoutBorrowingAnEventId() {
        when(stripeService.verifyWebhookEvent(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Invalid webhook signature"));

        assertThrows(IllegalArgumentException.class, () -> paymentOps.handleStripeWebhook("payload", "sig"));

        String line = onlyMessage();
        assertTrue(line.startsWith("Stripe webhook rejected: "), line);
        assertTrue(line.contains("Invalid webhook signature"), line);
        // The body is unverified at this point, so nothing from it may be logged.
        assertFalse(line.contains("id=evt_"), line);
        assertFalse(line.contains("payload"), line);
    }

    @Test
    void aDisputeThatCannotBeAppliedFailsLoudlyInsteadOfReturningStripeA200() {
        // The dispute branch used to swallow its own exception: payment state
        // stayed wrong, Stripe was told 200, and nothing was retried or logged.
        when(stripeService.verifyWebhookEvent(anyString(), anyString()))
                .thenReturn(event("evt_5", "charge.dispute.created",
                        "{\"id\":\"dp_1\",\"object\":\"dispute\",\"status\":\"needs_response\",\"payment_intent\":\"pi_1\"}"));
        when(bookAppointmentRepo.findByPaymentIntentId("pi_1"))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(RuntimeException.class, () -> paymentOps.handleStripeWebhook("payload", "sig"));

        String line = onlyMessage();
        assertTrue(line.startsWith("Stripe webhook failed: "), line);
        assertTrue(line.contains("type=charge.dispute.created"), line);
        assertTrue(line.contains("db down"), line);
    }

    @Test
    void aConnectUpdateNamesTheStylerItTouched() {
        when(stripeService.verifyWebhookEvent(anyString(), anyString()))
                .thenReturn(event("evt_6", "account.updated",
                        "{\"id\":\"acct_1\",\"object\":\"account\",\"details_submitted\":true,\"payouts_enabled\":true}"));
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("DS5713");
        styler.setConnectOnboardingStatus("PENDING");
        when(stylerRepo.findByStripeConnectAccountId("acct_1")).thenReturn(Optional.of(styler));

        paymentOps.handleStripeWebhook("payload", "sig");

        String line = onlyMessage();
        assertTrue(line.startsWith("Stripe webhook processed: "), line);
        assertTrue(line.contains("styler=DS5713"), line);
        assertTrue(line.contains("status=COMPLETE"), line);
        assertEquals("COMPLETE", styler.getConnectOnboardingStatus());
    }

    @Test
    void theDeliveryLogNeverCarriesTheRequestBody() {
        when(stripeService.verifyWebhookEvent(anyString(), anyString()))
                .thenReturn(paymentIntent("evt_7", "payment_intent.payment_failed", "requires_payment_method"));
        when(bookAppointmentRepo.findByPaymentIntentId("pi_1"))
                .thenReturn(Optional.of(booking("PENDING")));

        String body = "{\"secret\":\"whsec_live_do_not_log\",\"card\":\"4242424242424242\"}";
        paymentOps.handleStripeWebhook(body, "sig");

        for (String line : messages()) {
            assertFalse(line.contains("whsec"), "the signature secret must never be logged: " + line);
            assertFalse(line.contains("4242"), "card data must never be logged: " + line);
        }
    }
}

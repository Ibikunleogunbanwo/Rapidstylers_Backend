package com.macrotel.rapidstylers.payment.service;

import org.springframework.test.util.ReflectionTestUtils;
import com.macrotel.rapidstylers.service.AuditService;
import com.macrotel.rapidstylers.support.TestServices;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.stripe.model.Event;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisputeWebhookTest {

    private TestServices services;
    private BookAppointmentRepo bookAppointmentRepo;
    private StripeService stripeService;
    private EmailConfig emailConfig;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        stripeService = mock(StripeService.class);
        emailConfig = mock(EmailConfig.class);
        services.bookAppointmentRepo = bookAppointmentRepo;
        services.stripeService = stripeService;
        services.auditLogRepo = mock(AuditLogRepo.class);
        services.emailConfig = emailConfig;
        services.outboxEventService = mock(OutboxEventService.class);
        // Webhook handling lives in PaymentService — share the same mocks;
        // the ops-alert address now sits on AuditService (moved from AppService).
        AuditService sharedAudit = new AuditService();
        ReflectionTestUtils.setField(sharedAudit, "auditLogRepo", mock(AuditLogRepo.class));
        ReflectionTestUtils.setField(sharedAudit, "emailConfig", emailConfig);
        org.springframework.test.util.ReflectionTestUtils.setField(sharedAudit, "adminAlertEmail", "ops@example.com");
        PaymentService paymentOps = new PaymentService();
        paymentOps.bookAppointmentRepo = bookAppointmentRepo;
        paymentOps.stripeService = stripeService;
        paymentOps.auditService = sharedAudit;
        services.auditService = sharedAudit;
        services.paymentService = paymentOps;
    }

    private Event disputeEvent(String type, String status) {
        Event event = new Event();
        event.setType(type);
        Event.Data data = new Event.Data();
        data.setObject(JsonParser.parseString(
                "{\"id\":\"dp_1\",\"object\":\"dispute\",\"status\":\"" + status + "\",\"payment_intent\":\"pi_1\"}")
                .getAsJsonObject());
        event.setData(data);
        return event;
    }

    private BookAppointmentEntity booking(String paymentStatus) {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId("APPT1");
        appointment.setPaymentIntentId("pi_1");
        appointment.setPaymentStatus(paymentStatus);
        return appointment;
    }

    @Test
    void disputeOpenedFlagsBookingAndAlertsOps() {
        BookAppointmentEntity appointment = booking("CAPTURED");
        when(stripeService.verifyWebhookEvent(anyString(), anyString()))
                .thenReturn(disputeEvent("charge.dispute.created", "needs_response"));
        when(bookAppointmentRepo.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(appointment));

        services.paymentService.handleStripeWebhook("payload", "sig");

        assertEquals("DISPUTED", appointment.getPaymentStatus());
        verify(bookAppointmentRepo).save(appointment);
        verify(emailConfig).sendSimpleMail(eq("ops@example.com"), contains("Action required"), contains("dispute"));
    }

    @Test
    void disputeWonRestoresCapturedStatus() {
        BookAppointmentEntity appointment = booking("DISPUTED");
        when(stripeService.verifyWebhookEvent(anyString(), anyString()))
                .thenReturn(disputeEvent("charge.dispute.closed", "won"));
        when(bookAppointmentRepo.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(appointment));

        services.paymentService.handleStripeWebhook("payload", "sig");

        assertEquals("CAPTURED", appointment.getPaymentStatus());
    }

    @Test
    void disputeLostMarksBookingDisputeLost() {
        BookAppointmentEntity appointment = booking("DISPUTED");
        when(stripeService.verifyWebhookEvent(anyString(), anyString()))
                .thenReturn(disputeEvent("charge.dispute.closed", "lost"));
        when(bookAppointmentRepo.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(appointment));

        services.paymentService.handleStripeWebhook("payload", "sig");

        assertEquals("DISPUTE_LOST", appointment.getPaymentStatus());
    }
}

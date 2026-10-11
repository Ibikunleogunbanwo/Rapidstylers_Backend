package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.stripe.model.PaymentIntent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentReconciliationServiceTest {

    private PaymentReconciliationService service;
    private BookAppointmentRepo bookAppointmentRepo;

    @BeforeEach
    void setUp() {
        service = new PaymentReconciliationService();
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        ReflectionTestUtils.setField(service, "appointmentRecords", new com.macrotel.rapidstylers.booking.service.AppointmentRecords(bookAppointmentRepo));
        ReflectionTestUtils.setField(service, "auditService", new com.macrotel.rapidstylers.common.service.AuditService());
        ReflectionTestUtils.setField(service, "emailConfig", mock(EmailConfig.class));
        ReflectionTestUtils.setField(service, "windowHours", 72L);
        ReflectionTestUtils.setField(service, "reconcileIntervalMs", 86400000L);
        ReflectionTestUtils.setField(service, "adminAlertEmail", "");
    }

    private PaymentIntent intent(String id, String status) {
        PaymentIntent pi = new PaymentIntent();
        pi.setId(id);
        pi.setStatus(status);
        pi.setAmount(12500L);
        return pi;
    }

    private BookAppointmentEntity appointment(String intentId, String paymentStatus, String bookingStatus) {
        BookAppointmentEntity a = new BookAppointmentEntity();
        a.setAppointmentId("APPT1");
        a.setPaymentIntentId(intentId);
        a.setPaymentStatus(paymentStatus);
        a.setStatus(bookingStatus);
        return a;
    }

    @Test
    void scheduledReconciliationWaitsBeforeItsFirstProviderRequest() throws Exception {
        Scheduled scheduled = PaymentReconciliationService.class
                .getMethod("scheduledReconcile")
                .getAnnotation(Scheduled.class);

        assertEquals("${app.payment.reconcile-initial-delay-ms:60000}", scheduled.initialDelayString());
    }

    /**
     * A bare {@code created=<epoch>} is an EXACT second match to Stripe, not a
     * lower bound, so it would return zero intents and the drift check would
     * report "0 issue(s)" while examining nothing. The lookback must be the
     * range form {@code created[gte]}.
     */
    @Test
    void lookbackFilterIsARangeNotAnExactSecondMatch() {
        Map<String, Object> params = PaymentReconciliationService.listParams(1791465689L).toMap();

        Object created = params.get("created");
        assertTrue(created instanceof Map, "created must be a range object, not a bare timestamp: " + created);
        assertEquals(1791465689L, ((Number) ((Map<?, ?>) created).get("gte")).longValue());
        assertEquals(100L, ((Number) params.get("limit")).longValue());
    }

    @Test
    void noDriftWhenStripeSucceededAndLocalCaptured() {
        when(bookAppointmentRepo.findAll()).thenReturn(List.of(appointment("pi_1", "CAPTURED", "0")));

        Map<String, Object> report = service.buildReport(List.of(intent("pi_1", "succeeded")));

        assertEquals(Boolean.TRUE, report.get("ok"));
        assertEquals(1, report.get("matched"));
        assertEquals(0, report.get("issueCount"));
    }

    @Test
    void flagsSucceededIntentWhenLocalNotCaptured() {
        when(bookAppointmentRepo.findAll()).thenReturn(List.of(appointment("pi_1", "AUTHORIZED", "3")));

        Map<String, Object> report = service.buildReport(List.of(intent("pi_1", "succeeded")));

        assertEquals(Boolean.FALSE, report.get("ok"));
        assertEquals(1, report.get("issueCount"));
        assertTrue(((List<?>) report.get("issues")).get(0).toString().contains("succeeded but local is AUTHORIZED"));
    }

    @Test
    void flagsOrphanedStripeIntent() {
        when(bookAppointmentRepo.findAll()).thenReturn(List.of());

        Map<String, Object> report = service.buildReport(List.of(intent("pi_1", "succeeded")));

        assertEquals(Boolean.FALSE, report.get("ok"));
        assertTrue(((List<?>) report.get("issues")).get(0).toString().contains("no local appointment"));
    }

    @Test
    void flagsStaleHoldOnCancelledBooking() {
        when(bookAppointmentRepo.findAll()).thenReturn(List.of(appointment("pi_1", "AUTHORIZED", "4")));

        Map<String, Object> report = service.buildReport(List.of());

        assertEquals(Boolean.FALSE, report.get("ok"));
        assertTrue(((List<?>) report.get("issues")).get(0).toString().contains("Stale authorized hold"));
    }

    @Test
    void awaitingCaptureMatchesAuthorizedLocalState() {
        when(bookAppointmentRepo.findAll()).thenReturn(List.of(appointment("pi_1", "AUTHORIZED", "3")));

        Map<String, Object> report = service.buildReport(List.of(intent("pi_1", "requires_capture")));

        assertEquals(Boolean.TRUE, report.get("ok"));
        assertEquals(1, report.get("matched"));
    }
}

package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

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
        ReflectionTestUtils.setField(service, "bookAppointmentRepo", bookAppointmentRepo);
        ReflectionTestUtils.setField(service, "auditLogRepo", mock(AuditLogRepo.class));
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

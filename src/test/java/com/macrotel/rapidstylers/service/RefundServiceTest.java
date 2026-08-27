package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.RefundEntity;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.RefundRequestData;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.BookingSlotLockRepo;
import com.macrotel.rapidstylers.repo.RefundRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundServiceTest {

    private AppService appService;
    private BookAppointmentRepo bookAppointmentRepo;
    private RefundRepo refundRepo;
    private StripeService stripeService;
    private OutboxEventService outboxEventService;
    private PayoutReversalService payoutReversalService;
    private BookingSlotLockRepo slotLockRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        refundRepo = mock(RefundRepo.class);
        stripeService = mock(StripeService.class);
        outboxEventService = mock(OutboxEventService.class);
        slotLockRepo = mock(BookingSlotLockRepo.class);
        appService.bookAppointmentRepo = bookAppointmentRepo;
        appService.refundRepo = refundRepo;
        appService.stripeService = stripeService;
        appService.outboxEventService = outboxEventService;
        appService.bookingSlotLockRepo = slotLockRepo;
        payoutReversalService = mock(PayoutReversalService.class);
        appService.payoutReversalService = payoutReversalService;
        appService.auditLogRepo = mock(AuditLogRepo.class);
        appService.emailConfig = mock(EmailConfig.class);
        org.springframework.test.util.ReflectionTestUtils.setField(appService, "stylerCancelWindowHours", 24L);
    }

    private BookAppointmentEntity capturedAppointment() {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId("APPT1");
        appointment.setUserId("USER1");
        appointment.setStylerId("STYLER1");
        appointment.setPaymentIntentId("pi_1");
        appointment.setPaymentStatus("CAPTURED");
        appointment.setPaymentAmount("125.00");
        appointment.setPrice("125.00");
        return appointment;
    }

    @Test
    void adminRefundRecordsRefundAndMarksBookingRefunded() throws Exception {
        BookAppointmentEntity appointment = capturedAppointment();
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        when(stripeService.isConfigured()).thenReturn(true);
        when(refundRepo.existsByPaymentIntentIdAndStatus("pi_1", "COMPLETED")).thenReturn(false);
        com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
        stripeRefund.setId("re_1");
        doReturn(stripeRefund).when(stripeService).refundBookingPayment(eq("pi_1"), eq(12500L), any(), any());

        RefundRequestData data = new RefundRequestData();
        data.setAppointmentId("APPT1");
        data.setReason("Client request");

        BaseResponse response = appService.adminRefund("ADMIN1", data);

        assertEquals("200", response.getStatusCode());
        assertEquals("REFUNDED", appointment.getPaymentStatus());
        verify(bookAppointmentRepo).save(appointment);
        ArgumentCaptor<RefundEntity> captor = ArgumentCaptor.forClass(RefundEntity.class);
        verify(refundRepo, times(2)).save(captor.capture());
        RefundEntity completed = captor.getAllValues().get(1);
        assertEquals("COMPLETED", completed.getStatus());
        assertEquals("125.00", completed.getAmount());
        assertEquals("re_1", completed.getStripeRefundId());
        assertEquals("ADMIN1", completed.getCreatedBy());
        verify(outboxEventService).refundEvent(eq(appointment), eq("125.00"), eq("Client request"), eq(true));
    }

    @Test
    void adminRefundBlocksSecondRefundForSamePaymentIntent() throws Exception {
        BookAppointmentEntity appointment = capturedAppointment();
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        when(stripeService.isConfigured()).thenReturn(true);
        when(refundRepo.existsByPaymentIntentIdAndStatus("pi_1", "COMPLETED")).thenReturn(true);

        RefundRequestData data = new RefundRequestData();
        data.setAppointmentId("APPT1");
        BaseResponse response = appService.adminRefund("ADMIN1", data);

        assertEquals("400", response.getStatusCode());
        verify(stripeService, never()).refundBookingPayment(anyString(), anyLong(), any(), any());
        verify(refundRepo, never()).save(any(RefundEntity.class));
    }

    @Test
    void adminRefundRejectsUncapturedPayment() throws Exception {
        BookAppointmentEntity appointment = capturedAppointment();
        appointment.setPaymentStatus("AUTHORIZED");
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        when(stripeService.isConfigured()).thenReturn(true);

        RefundRequestData data = new RefundRequestData();
        data.setAppointmentId("APPT1");
        BaseResponse response = appService.adminRefund("ADMIN1", data);

        assertEquals("400", response.getStatusCode());
        verify(stripeService, never()).refundBookingPayment(anyString(), anyLong(), any(), any());
    }

    @Test
    void adminRefundSupportsPartialAmount() throws Exception {
        BookAppointmentEntity appointment = capturedAppointment();
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        when(stripeService.isConfigured()).thenReturn(true);
        when(refundRepo.existsByPaymentIntentIdAndStatus("pi_1", "COMPLETED")).thenReturn(false);
        com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
        stripeRefund.setId("re_1");
        doReturn(stripeRefund).when(stripeService).refundBookingPayment(eq("pi_1"), eq(5000L), any(), any());

        RefundRequestData data = new RefundRequestData();
        data.setAppointmentId("APPT1");
        data.setAmount("50.00");
        BaseResponse response = appService.adminRefund("ADMIN1", data);

        assertEquals("200", response.getStatusCode());
        verify(stripeService).refundBookingPayment(eq("pi_1"), eq(5000L), any(), any());
    }

    @Test
    void cancellingCapturedBookingAutoRefundsFullAmount() throws Exception {
        BookAppointmentEntity appointment = capturedAppointment();
        appointment.setStatus("3");
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        when(stripeService.isConfigured()).thenReturn(true);
        when(refundRepo.existsByPaymentIntentIdAndStatus("pi_1", "COMPLETED")).thenReturn(false);
        com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
        stripeRefund.setId("re_1");
        doReturn(stripeRefund).when(stripeService).refundBookingPayment(eq("pi_1"), eq(12500L), any(), any());

        BaseResponse response = appService.cancelAppointment("USER1", "APPT1");

        assertEquals("200", response.getStatusCode());
        assertEquals("4", appointment.getStatus());
        assertEquals("REFUNDED", appointment.getPaymentStatus());
        verify(slotLockRepo).deleteByAppointmentId("APPT1");
        verify(refundRepo, atLeastOnce()).save(any(RefundEntity.class));
        verify(outboxEventService).refundEvent(eq(appointment), any(), any(), eq(true));
    }

    @Test
    void stylerCancelsCompletedBookingAutoRefundsExactlyOnce() throws Exception {
        BookAppointmentEntity appointment = capturedAppointment();
        appointment.setStatus("0"); // completed — payment captured
        appointment.setCompletedAt(LocalDateTime.now()); // within the cancel window
        appointment.setStripeTransferId("tr_1"); // stylist payout already made
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        when(stripeService.isConfigured()).thenReturn(true);
        when(refundRepo.existsByPaymentIntentIdAndStatus("pi_1", "COMPLETED")).thenReturn(false);
        com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
        stripeRefund.setId("re_1");
        doReturn(stripeRefund).when(stripeService).refundBookingPayment(eq("pi_1"), eq(12500L), any(), any());

        BaseResponse response = appService.stylerCancelAppointment("STYLER1", "APPT1");

        assertEquals("200", response.getStatusCode());
        assertEquals("4", appointment.getStatus());
        assertEquals("REFUNDED", appointment.getPaymentStatus());
        verify(slotLockRepo).deleteByAppointmentId("APPT1");
        verify(stripeService).refundBookingPayment(eq("pi_1"), eq(12500L), any(), any());
        verify(outboxEventService).refundEvent(eq(appointment), any(), any(), eq(true));
        // The payout was already transferred — an automatic reversal is requested.
        verify(payoutReversalService).requestReversal(eq("APPT1"), eq("tr_1"), any(), any());
    }

    @Test
    void secondCancelAfterRefundDoesNotRefundAgain() throws Exception {
        BookAppointmentEntity appointment = capturedAppointment();
        appointment.setStatus("0");
        appointment.setCompletedAt(LocalDateTime.now());
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        when(stripeService.isConfigured()).thenReturn(true);
        when(refundRepo.existsByPaymentIntentIdAndStatus("pi_1", "COMPLETED")).thenReturn(false);
        com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
        stripeRefund.setId("re_1");
        doReturn(stripeRefund).when(stripeService).refundBookingPayment(eq("pi_1"), eq(12500L), any(), any());

        BaseResponse first = appService.stylerCancelAppointment("STYLER1", "APPT1");
        // The second cancel arrives after the booking is already cancelled — the
        // state machine rejects it before any refund logic runs.
        BaseResponse second = appService.stylerCancelAppointment("STYLER1", "APPT1");

        assertEquals("200", first.getStatusCode());
        assertEquals("400", second.getStatusCode());
        verify(stripeService, times(1)).refundBookingPayment(eq("pi_1"), anyLong(), any(), any());
        verify(outboxEventService, times(1)).refundEvent(eq(appointment), any(), any(), eq(true));
    }

    @Test
    void concurrentCancelSeesRefundedPaymentAndSkipsSecondRefund() throws Exception {
        // Simulates the pessimistic-lock outcome: the second transaction reads
        // the state the first committed — payment already REFUNDED, so no
        // second Stripe refund is created.
        BookAppointmentEntity appointment = capturedAppointment();
        appointment.setStatus("0");
        appointment.setCompletedAt(LocalDateTime.now());
        appointment.setPaymentStatus("REFUNDED");
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        when(stripeService.isConfigured()).thenReturn(true);

        BaseResponse response = appService.stylerCancelAppointment("STYLER1", "APPT1");

        assertEquals("200", response.getStatusCode());
        assertEquals("4", appointment.getStatus());
        verify(stripeService, never()).refundBookingPayment(anyString(), anyLong(), any(), any());
        verify(refundRepo, never()).save(any(RefundEntity.class));
    }

    @Test
    void stylerCannotCancelCompletedBookingOutsideWindow() throws Exception {
        BookAppointmentEntity appointment = capturedAppointment();
        appointment.setStatus("0");
        appointment.setCompletedAt(LocalDateTime.now().minusHours(48)); // outside the 24h window
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        when(stripeService.isConfigured()).thenReturn(true);

        BaseResponse response = appService.stylerCancelAppointment("STYLER1", "APPT1");

        assertEquals("400", response.getStatusCode());
        assertTrue(response.getMessage().contains("within 24 hours of completion"));
        assertEquals("0", appointment.getStatus()); // untouched
        verify(stripeService, never()).refundBookingPayment(anyString(), anyLong(), any(), any());
        verify(refundRepo, never()).save(any(RefundEntity.class));
    }

    @Test
    void completedBookingWithoutCompletionTimeCannotBeCancelled() throws Exception {
        BookAppointmentEntity appointment = capturedAppointment();
        appointment.setStatus("0");
        appointment.setCompletedAt(null); // legacy row — completion time unknown
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        when(stripeService.isConfigured()).thenReturn(true);

        BaseResponse response = appService.stylerCancelAppointment("STYLER1", "APPT1");

        assertEquals("400", response.getStatusCode());
        assertTrue(response.getMessage().contains("completion time is unknown"));
        verify(stripeService, never()).refundBookingPayment(anyString(), anyLong(), any(), any());
    }

    @Test
    void completingAppointmentRecordsCompletionTime() throws Exception {
        BookAppointmentEntity appointment = capturedAppointment();
        appointment.setStatus("3");
        appointment.setAppointmentDate("2020-01-01"); // long past → eligible to complete
        appointment.setAppointmentDateValue(LocalDate.of(2020, 1, 1));
        appointment.setArrivalTime("09:00");
        appointment.setAppointmentStartTime(LocalTime.of(9, 0));
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        // stripeService.isConfigured() defaults to false → no capture/transfer in this test

        BaseResponse response = appService.completeAppointment("STYLER1", "APPT1");

        assertEquals("200", response.getStatusCode());
        assertEquals("0", appointment.getStatus());
        assertNotNull(appointment.getCompletedAt());
    }
}

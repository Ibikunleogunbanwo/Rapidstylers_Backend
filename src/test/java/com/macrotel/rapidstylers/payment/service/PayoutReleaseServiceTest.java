package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.common.repo.AuditLogRepo;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.payment.entity.RefundEntity;
import com.macrotel.rapidstylers.payment.repo.RefundRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The stylist is paid a day after completion, sooner on the customer's word, never while disputed. */
class PayoutReleaseServiceTest {

    private PayoutReleaseService payouts;
    private BookAppointmentRepo bookAppointmentRepo;
    private StripeService stripeService;
    private RefundRepo refundRepo;
    private EmailConfig emailConfig;

    @BeforeEach
    void setUp() throws Exception {
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        stripeService = mock(StripeService.class);
        refundRepo = mock(RefundRepo.class);
        emailConfig = mock(EmailConfig.class);
        when(stripeService.isConfigured()).thenReturn(true);
        com.stripe.model.Transfer transfer = new com.stripe.model.Transfer();
        transfer.setId("tr_9");
        doReturn(transfer).when(stripeService).transferStylistShare(anyString(), anyLong(), anyString(), anyString());

        PaymentService paymentService = new PaymentService();
        paymentService.refundRepo = refundRepo;
        AuditService auditService = new AuditService();
        ReflectionTestUtils.setField(auditService, "auditLogRepo", mock(AuditLogRepo.class));
        ReflectionTestUtils.setField(auditService, "emailConfig", emailConfig);

        payouts = new PayoutReleaseService();
        payouts.bookAppointmentRepo = bookAppointmentRepo;
        payouts.stylerRepo = mock(StylerRepo.class);
        payouts.stripeService = stripeService;
        payouts.paymentService = paymentService;
        payouts.auditService = auditService;
        payouts.emailConfig = emailConfig;
        payouts.adminAlertEmail = "ops@example.com";

        StylerEntity stylist = new StylerEntity();
        stylist.setStripeConnectAccountId("acct_1");
        when(payouts.stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(stylist));
    }

    /** A $125 booking at 12% commission, completed, its payout waiting. */
    private BookAppointmentEntity completed() {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId("APPT1");
        appointment.setUserId("USER1");
        appointment.setStylerId("STYLER1");
        appointment.setStatus("0");
        appointment.setPaymentIntentId("pi_1");
        appointment.setPaymentStatus("CAPTURED");
        appointment.setPaymentAmount("125.00");
        appointment.setCommissionPercent(12.0);
        appointment.setCompletedAt(LocalDateTime.now().minusHours(2));
        appointment.setPayoutDueAt(LocalDateTime.now().plusHours(22));
        when(bookAppointmentRepo.findByAppointmentIdForUpdate("APPT1")).thenReturn(Optional.of(appointment));
        return appointment;
    }

    @Test
    void onlyAChargedBookingWaitsForAPayout() {
        BookAppointmentEntity charged = completed();
        charged.setPayoutDueAt(null);
        payouts.holdAfterCompletion(charged);
        assertNotNull(charged.getPayoutDueAt());

        BookAppointmentEntity unpaid = completed();
        unpaid.setPayoutDueAt(null);
        unpaid.setPaymentStatus("RELEASED");
        payouts.holdAfterCompletion(unpaid);
        assertNull(unpaid.getPayoutDueAt());
    }

    @Test
    void theStylistIsPaidOnceTheDayIsUp() throws Exception {
        BookAppointmentEntity appointment = completed();
        appointment.setPayoutDueAt(LocalDateTime.now().minusMinutes(1));
        when(bookAppointmentRepo.findTop50ByStatusAndPaymentStatusAndStripeTransferIdIsNullAndPayoutDueAtLessThanEqual(
                eq("0"), eq("CAPTURED"), any())).thenReturn(List.of(appointment));

        payouts.releaseDuePayouts();

        // $125 less 12% ($15) is $110.
        verify(stripeService).transferStylistShare("acct_1", 11000L, "APPT1", "payout_APPT1_11000");
        assertEquals("tr_9", appointment.getStripeTransferId());
        assertNull(appointment.getPayoutDueAt());
        assertEquals(1500L, appointment.getPlatformFeeCents());
    }

    @Test
    void aPartRefundedBeforeThePayoutIsNotShared() throws Exception {
        BookAppointmentEntity appointment = completed();
        RefundEntity refund = new RefundEntity();
        refund.setAmount("25.00");
        refund.setStatus("COMPLETED");
        when(refundRepo.findByPaymentIntentIdAndStatus("pi_1", "COMPLETED")).thenReturn(List.of(refund));

        payouts.release(appointment);

        // $100 kept, less 12% ($12), is $88.
        verify(stripeService).transferStylistShare("acct_1", 8800L, "APPT1", "payout_APPT1_8800");
    }

    @Test
    void confirmingTheAppointmentHappenedPaysTheStylistNow() throws Exception {
        BookAppointmentEntity appointment = completed();

        BaseResponse response = payouts.confirmServiceHappened("USER1", "APPT1");

        assertEquals("200", response.getStatusCode());
        assertNotNull(appointment.getServiceConfirmedAt());
        assertEquals("tr_9", appointment.getStripeTransferId());
    }

    @Test
    void aDisputeHoldsThePayoutAndTellsTheAdmin() throws Exception {
        BookAppointmentEntity appointment = completed();

        BaseResponse response = payouts.disputeService("USER1", "APPT1", "The professional never arrived");

        assertEquals("200", response.getStatusCode());
        assertEquals("OPEN", appointment.getServiceDisputeStatus());
        assertEquals("The professional never arrived", appointment.getServiceDisputeReason());
        assertNull(appointment.getPayoutDueAt());
        assertNull(PayoutReleaseService.disputeWindowEndsAt(appointment));
        verify(emailConfig).sendSimpleMail(eq("ops@example.com"), contains("disputed"), contains("APPT1"));

        // Even if it is picked up, a disputed booking is not paid.
        payouts.release(appointment);
        verify(stripeService, never()).transferStylistShare(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void onceTheStylistIsPaidADisputeGoesToSupport() {
        BookAppointmentEntity appointment = completed();
        appointment.setStripeTransferId("tr_1");
        appointment.setPayoutDueAt(null);

        BaseResponse response = payouts.disputeService("USER1", "APPT1", "It didn't happen");

        assertEquals("400", response.getStatusCode());
        assertNull(appointment.getServiceDisputeStatus());
    }

    @Test
    void onlyTheBookingsCustomerCanDisputeIt() {
        BookAppointmentEntity appointment = completed();

        BaseResponse response = payouts.disputeService("SOMEONE_ELSE", "APPT1", "It didn't happen");

        assertEquals("400", response.getStatusCode());
        assertNull(appointment.getServiceDisputeStatus());
    }

    @Test
    void resolvingADisputePaysTheStylistTheirShareOfWhatWasKept() throws Exception {
        BookAppointmentEntity appointment = completed();
        appointment.setServiceDisputeStatus("OPEN");
        appointment.setPayoutDueAt(null);
        RefundEntity refund = new RefundEntity();
        refund.setAmount("50.00");
        refund.setStatus("COMPLETED");
        when(refundRepo.findByPaymentIntentIdAndStatus("pi_1", "COMPLETED")).thenReturn(List.of(refund));

        BaseResponse response = payouts.resolveDispute("ADMIN1", "APPT1", "Arrived late, half refunded");

        assertEquals("200", response.getStatusCode());
        assertEquals("RESOLVED", appointment.getServiceDisputeStatus());
        // $75 kept, less 12% ($9), is $66.
        verify(stripeService).transferStylistShare("acct_1", 6600L, "APPT1", "payout_APPT1_6600");
    }

    @Test
    void afterAFullRefundResolvingPaysNothing() throws Exception {
        BookAppointmentEntity appointment = completed();
        appointment.setServiceDisputeStatus("OPEN");
        appointment.setPayoutDueAt(null);
        appointment.setPaymentStatus("REFUNDED");

        payouts.resolveDispute("ADMIN1", "APPT1", "Never happened, refunded");

        assertEquals("RESOLVED", appointment.getServiceDisputeStatus());
        assertNull(appointment.getPayoutDueAt());
        verify(stripeService, never()).transferStylistShare(anyString(), anyLong(), anyString(), anyString());
    }
}

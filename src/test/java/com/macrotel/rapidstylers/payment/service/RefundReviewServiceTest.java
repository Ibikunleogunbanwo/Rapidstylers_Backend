package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.payment.entity.RefundEntity;
import com.macrotel.rapidstylers.payment.repo.RefundRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The payment details an admin sees before and after refunding a booking. */
class RefundReviewServiceTest {

    private RefundReviewService review;
    private RefundRepo refundRepo;
    private BookAppointmentRepo bookAppointmentRepo;

    private UserRepo userRepo;
    private StylerRepo stylerRepo;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepo.class);
        stylerRepo = mock(StylerRepo.class);
        refundRepo = mock(RefundRepo.class);
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        PaymentService paymentService = new PaymentService();
        paymentService.refundRepo = refundRepo;

        review = new RefundReviewService();
        review.paymentService = paymentService;
        review.refundRepo = refundRepo;
        review.appointmentRecords = new com.macrotel.rapidstylers.booking.service.AppointmentRecords(bookAppointmentRepo);
        review.customerRecords = new com.macrotel.rapidstylers.customer.service.CustomerRecords(userRepo);
        review.stylerRecords = new com.macrotel.rapidstylers.styler.service.StylerRecords(stylerRepo, mock(SubServiceRepo.class));
        review.payoutReversalRepo = mock(com.macrotel.rapidstylers.payment.repo.PayoutReversalRepo.class);

        UserEntity customer = new UserEntity();
        customer.setFirstname("Ada");
        customer.setLastname("Obi");
        customer.setEmailAddress("ada@example.com");
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(customer));
        StylerEntity stylist = new StylerEntity();
        stylist.setFirstname("Chloé");
        stylist.setLastname("Martin");
        stylist.setBusinessName("Chloé Hair");
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(stylist));
    }

    private BookAppointmentEntity completedAndPaidOut() {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId("APPT1");
        appointment.setUserId("USER1");
        appointment.setStylerId("STYLER1");
        appointment.setStatus("0");
        appointment.setPaymentIntentId("pi_1");
        appointment.setPaymentStatus("CAPTURED");
        appointment.setPaymentAmount("125.00");
        appointment.setPlatformFeeCents(1500L);
        appointment.setStylistShareCents(11000L);
        appointment.setStripeTransferId("tr_1");
        when(bookAppointmentRepo.findByAppointmentId("APPT1")).thenReturn(Optional.of(appointment));
        return appointment;
    }

    private RefundEntity completedRefund(String refundId, String amount) {
        RefundEntity refund = new RefundEntity();
        refund.setRefundId(refundId);
        refund.setAppointmentId("APPT1");
        refund.setPaymentIntentId("pi_1");
        refund.setAmount(amount);
        refund.setStatus("COMPLETED");
        refund.setCreatedAt("2026-10-10 12:00:00");
        return refund;
    }

    @Test
    void showsWhatWasChargedRefundedAndStillRefundable() {
        completedAndPaidOut();
        RefundEntity partial = completedRefund("RFND-1", "40.00");
        when(refundRepo.findByPaymentIntentIdAndStatus("pi_1", "COMPLETED")).thenReturn(List.of(partial));
        when(refundRepo.findByAppointmentId("APPT1")).thenReturn(List.of(partial));

        BaseResponse response = review.paymentContext("APPT1");

        assertEquals("200", response.getStatusCode());
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertEquals("COMPLETED", data.get("appointmentStatusLabel"));
        assertEquals("Ada Obi", data.get("customerName"));
        assertEquals("ada@example.com", data.get("customerEmail"));
        assertEquals("Chloé Hair", data.get("businessName"));
        assertEquals("125.00", data.get("capturedAmount"));
        assertEquals("40.00", data.get("refundedTotal"));
        assertEquals("85.00", data.get("refundableRemaining"));
        assertEquals(true, data.get("refundable"));
        assertEquals("15.00", data.get("platformFee"));
        assertEquals("110.00", data.get("stylistShare"));
        assertEquals(true, data.get("stylistPaidOut"));
        assertEquals(List.of(), data.get("payoutRecoveries"));
        assertEquals(List.of(partial), data.get("refunds"));
    }

    @Test
    void aFullyRefundedPaymentHasNothingLeftToRefund() {
        completedAndPaidOut().setPaymentStatus("REFUNDED");
        when(refundRepo.findByPaymentIntentIdAndStatus("pi_1", "COMPLETED"))
                .thenReturn(List.of(completedRefund("RFND-1", "125.00")));

        Map<?, ?> data = (Map<?, ?>) review.paymentContext("APPT1").getData();

        assertEquals("125.00", data.get("refundedTotal"));
        assertEquals("0.00", data.get("refundableRemaining"));
        assertEquals(false, data.get("refundable"));
    }

    @Test
    void anUnknownAppointmentIsRefused() {
        when(bookAppointmentRepo.findByAppointmentId("NOPE")).thenReturn(Optional.empty());

        BaseResponse response = review.paymentContext("NOPE");

        assertEquals("400", response.getStatusCode());
        assertEquals("Invalid Appointment Id", response.getMessage());
    }

    @Test
    void theRefundListKeepsEachRefundsFieldsAndAddsItsPayment() {
        completedAndPaidOut();
        RefundEntity older = completedRefund("RFND-1", "40.00");
        RefundEntity newer = completedRefund("RFND-2", "10.00");
        newer.setCreatedAt("2026-10-10 13:00:00");
        when(refundRepo.findAll()).thenReturn(new java.util.ArrayList<>(List.of(older, newer)));
        when(refundRepo.findByPaymentIntentIdAndStatus("pi_1", "COMPLETED")).thenReturn(List.of(older, newer));

        List<?> rows = (List<?>) review.refundsWithPayments().getData();

        assertEquals(2, rows.size());
        Map<?, ?> first = (Map<?, ?>) rows.get(0);
        assertEquals("RFND-2", first.get("refundId"));
        assertEquals("10.00", first.get("amount"));
        assertEquals("COMPLETED", first.get("status"));
        Map<?, ?> payment = (Map<?, ?>) first.get("payment");
        assertEquals("50.00", payment.get("refundedTotal"));
        assertEquals("75.00", payment.get("refundableRemaining"));
    }
}

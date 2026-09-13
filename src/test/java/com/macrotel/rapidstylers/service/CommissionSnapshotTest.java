package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the deterministic payout policy: the commission percent snapped onto a
 * booking at creation drives its payout, and the fee breakdown (platform fee vs
 * stylist net share) is computed from that snapshot — never from a later rate change.
 *
 * Commission logic now lives in PaymentOpsService (carved out of AppService —
 * see docs/simplification-plan.md); this unit test targets it directly.
 */
class CommissionSnapshotTest {

    private PaymentOpsService paymentOps;

    @BeforeEach
    void setUp() {
        paymentOps = new PaymentOpsService();
        // Live rate is 12% by default (the new platform rate).
        ReflectionTestUtils.setField(paymentOps, "stripeCommissionPercent", 12.0);
        ReflectionTestUtils.setField(paymentOps, "cachedCommissionPercent", null);
    }

    @Test
    void defaultCommissionIsTwelvePercent() {
        assertEquals(12.0, paymentOps.effectiveCommissionPercentForBooking(null), 0.0001);
    }

    @Test
    void bookingSnapshotWinsOverLiveRate() {
        // Booking was created when the rate was 10%; admin later raised the live rate to 12%.
        BookAppointmentEntity booking = new BookAppointmentEntity();
        booking.setCommissionPercent(10.0);

        assertEquals(10.0, paymentOps.effectiveCommissionPercentForBooking(booking), 0.0001);
    }

    @Test
    void bookingWithoutSnapshotFallsBackToLiveRate() {
        BookAppointmentEntity booking = new BookAppointmentEntity(); // commissionPercent null

        assertEquals(12.0, paymentOps.effectiveCommissionPercentForBooking(booking), 0.0001);
    }

    @Test
    void payoutBreakdownAtTwelvePercent() {
        // $120.00 booking at 12% commission → $14.40 platform fee, $105.60 to stylist.
        long totalCents = 12000L;
        long commission = paymentOps.commissionCents(totalCents, 12.0);
        assertEquals(1440L, commission);
        assertEquals(10560L, totalCents - commission);
    }

    @Test
    void payoutBreakdownUsesSnapshotNotLiveRate() {
        // Snapshot 10% vs live 12%: the payout must match the snapshot.
        BookAppointmentEntity booking = new BookAppointmentEntity();
        booking.setCommissionPercent(10.0);
        long totalCents = 12000L;

        double percent = paymentOps.effectiveCommissionPercentForBooking(booking);
        long commission = paymentOps.commissionCents(totalCents, percent);
        assertEquals(1200L, commission, "commission must come from the 10% snapshot");
        assertEquals(10800L, totalCents - commission, "stylist share must reflect the 10% snapshot");
    }

    @Test
    void zeroCommissionYieldsFullShare() {
        long totalCents = 10000L;
        assertEquals(0L, paymentOps.commissionCents(totalCents, 0.0));
        assertEquals(10000L, totalCents - paymentOps.commissionCents(totalCents, 0.0));
    }

    @Test
    void invalidAmountYieldsZeroCommission() {
        assertEquals(0L, paymentOps.commissionCents(0L, 12.0));
        assertNull(new BookAppointmentEntity().getCommissionPercent());
    }
}

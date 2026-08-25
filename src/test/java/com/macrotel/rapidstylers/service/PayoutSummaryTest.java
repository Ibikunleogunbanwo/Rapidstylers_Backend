package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayoutSummaryTest {

    private AppService appService;
    private StylerRepo stylerRepo;
    private BookAppointmentRepo appointmentRepo;
    private StripeService stripeService;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        stylerRepo = mock(StylerRepo.class);
        appointmentRepo = mock(BookAppointmentRepo.class);
        stripeService = mock(StripeService.class);
        when(stripeService.isConfigured()).thenReturn(false);
        appService.stylerRepo = stylerRepo;
        appService.bookAppointmentRepo = appointmentRepo;
        appService.stripeService = stripeService;
        ReflectionTestUtils.setField(appService, "stripeCommissionPercent", 10.0);
    }

    @Test
    void aggregatesCapturedEarningsWithCommissionSplit() {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));

        BookAppointmentEntity paid = appointment("APPT-1", "CAPTURED", "100.00");
        paid.setStatus("0");
        BookAppointmentEntity paid2 = appointment("APPT-2", "CAPTURED", "250.00");
        paid2.setStatus("0");
        BookAppointmentEntity pending = appointment("APPT-3", "PENDING", "80.00");
        BookAppointmentEntity noIntent = appointment("APPT-4", "CAPTURED", "50.00");
        noIntent.setStatus("0");
        noIntent.setPaymentIntentId(null);
        when(appointmentRepo.findByStylerId("STYLER1"))
                .thenReturn(Arrays.asList(paid, paid2, pending, noIntent));

        BaseResponse response = appService.getStylerPayouts("STYLER1");

        assertEquals("200", response.getStatusCode());
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals(false, data.get("connected"));
        assertEquals("NOT_STARTED", data.get("status"));
        // 100.00 -> share 90.00 / commission 10.00 ; 250.00 -> share 225.00 / commission 25.00
        assertEquals("315.00", data.get("totalEarned"));
        assertEquals("35.00", data.get("totalCommission"));
        assertEquals("0.00", data.get("stripeAvailable"));
        assertEquals("0.00", data.get("stripePending"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("appointments");
        assertEquals(2, rows.size());
        assertEquals("APPT-1", rows.get(0).get("appointmentId"));
        assertEquals("90.00", rows.get(0).get("stylerShare"));
        assertEquals("10.00", rows.get(0).get("commission"));
        assertEquals("225.00", rows.get(1).get("stylerShare"));
    }

    @Test
    void connectedStylerExposesAccountStatusEvenWhenStripeDisabled() {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        styler.setStripeConnectAccountId("acct_1");
        styler.setConnectOnboardingStatus("COMPLETE");
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        when(appointmentRepo.findByStylerId("STYLER1")).thenReturn(Collections.emptyList());

        BaseResponse response = appService.getStylerPayouts("STYLER1");

        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals(true, data.get("connected"));
        assertEquals("COMPLETE", data.get("status"));
        assertEquals("0.00", data.get("totalEarned"));
    }

    @Test
    void rejectedStylerExposesPersistedDisabledReason() {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        styler.setStripeConnectAccountId("acct_1");
        styler.setConnectOnboardingStatus("REJECTED");
        styler.setConnectDisabledReason("rejected.terms_of_service");
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        when(appointmentRepo.findByStylerId("STYLER1")).thenReturn(Collections.emptyList());

        BaseResponse response = appService.getStylerPayouts("STYLER1");

        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals("REJECTED", data.get("status"));
        assertEquals("rejected.terms_of_service", data.get("disabledReason"));
    }

    private BookAppointmentEntity appointment(String id, String paymentStatus, String amount) {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId(id);
        appointment.setStylerId("STYLER1");
        appointment.setAppointmentDate("2030-08-24");
        appointment.setArrivalTime("09:30");
        appointment.setPrice(amount);
        appointment.setPaymentAmount(amount);
        appointment.setPaymentStatus(paymentStatus);
        appointment.setStatus("3");
        appointment.setPaymentIntentId("pi_" + id);
        return appointment;
    }
}

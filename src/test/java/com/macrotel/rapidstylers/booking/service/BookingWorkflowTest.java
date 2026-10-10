package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.booking.controller.BookingController;
import com.macrotel.rapidstylers.booking.dto.AppointmentDTO;
import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.pojo.BookAppointmentData;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.booking.repo.BookingSlotLockRepo;
import com.macrotel.rapidstylers.common.service.DTOService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.payment.controller.PaymentController;
import com.macrotel.rapidstylers.payment.entity.CardDetailsEntity;
import com.macrotel.rapidstylers.payment.pojo.CardDetailsData;
import com.macrotel.rapidstylers.payment.repo.CardDetailsRepo;
import com.macrotel.rapidstylers.payment.service.CardService;
import com.macrotel.rapidstylers.payment.service.PaymentService;
import com.macrotel.rapidstylers.payment.service.StripeService;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.repo.AvailabilityExceptionRepo;
import com.macrotel.rapidstylers.styler.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import com.macrotel.rapidstylers.styler.service.GeocodingService;
import com.macrotel.rapidstylers.support.TestServices;
import com.stripe.exception.CardException;
import com.stripe.model.PaymentIntent;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BookingWorkflowTest {

    private TestServices services;
    private BookingSlotLockRepo slotLockRepo;
    private BookAppointmentRepo appointmentRepo;
    private StylerRepo stylerRepo;
    private UserRepo userRepo;
    private SubServiceRepo subServiceRepo;
    private AvailabilityRepo availabilityRepo;
    private AvailabilityExceptionRepo availabilityExceptionRepo;
    private OutboxEventService outboxEventService;
    private CardDetailsRepo cardDetailsRepo;
    private StripeService stripeService;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        slotLockRepo = mock(BookingSlotLockRepo.class);
        appointmentRepo = mock(BookAppointmentRepo.class);
        stylerRepo = mock(StylerRepo.class);
        userRepo = mock(UserRepo.class);
        subServiceRepo = mock(SubServiceRepo.class);
        availabilityRepo = mock(AvailabilityRepo.class);
        availabilityExceptionRepo = mock(AvailabilityExceptionRepo.class);
        outboxEventService = mock(OutboxEventService.class);
        cardDetailsRepo = mock(CardDetailsRepo.class);
        services.bookingSlotLockRepo = slotLockRepo;
        services.bookAppointmentRepo = appointmentRepo;
        services.stylerRepo = stylerRepo;
        services.userRepo = userRepo;
        services.subServiceRepo = subServiceRepo;
        services.availabilityRepo = availabilityRepo;
        services.availabilityExceptionRepo = availabilityExceptionRepo;
        services.emailConfig = mock(EmailConfig.class);
        services.outboxEventService = outboxEventService;
        services.cardDetailsRepo = cardDetailsRepo;
        // Payments disabled in tests: booking proceeds without Stripe authorization.
        stripeService = mock(StripeService.class);
        when(stripeService.isConfigured()).thenReturn(false);
        services.stripeService = stripeService;
        // Same default as application.properties (STRIPE_COMMISSION_PERCENT).
        ReflectionTestUtils.setField(services, "paymentAuthorizationWindowDays", 7L);
        ReflectionTestUtils.setField(services, "paymentAuthorizationLeadHours", 48L);
        // Payment ops (commission, receipts, refunds) live in PaymentService —
        // share the same mocks and rate default (see docs/simplification-plan.md).
        PaymentService paymentOps = new PaymentService();
        ReflectionTestUtils.setField(paymentOps, "bookAppointmentRepo", appointmentRepo);
        ReflectionTestUtils.setField(paymentOps, "stylerRepo", stylerRepo);
        ReflectionTestUtils.setField(paymentOps, "stripeService", stripeService);
        ReflectionTestUtils.setField(paymentOps, "auditService", services.auditService);
        ReflectionTestUtils.setField(paymentOps, "stripeCommissionPercent", 10.0);
        services.paymentService = paymentOps;
    }

    @Test
    void ninetyMinuteBookingCreatesSixQuarterHourLocks() throws Exception {
        Method reserve = BookingService.class.getDeclaredMethod(
                "reserveBookingSlots", BookAppointmentEntity.class, LocalDate.class, LocalTime.class, int.class);
        reserve.setAccessible(true);
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setStylerId("STYLER1");
        appointment.setAppointmentId("APPT-1");

        reserve.invoke(services.bookingService(), appointment, LocalDate.of(2030, 8, 24), LocalTime.of(9, 30), 90);

        verify(slotLockRepo).saveAllAndFlush(argThatListSize(6));
    }

    @Test
    void declinedCardBookingReturnsCardErrorCodeForTheUi() throws Exception {
        BookAppointmentData data = bookingData();
        data.setAppointmentDate(LocalDate.now().plusDays(1).toString());
        data.setPaymentMethodId("pm_123");
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        StylerEntity styler = approvedStyler();
        styler.setConnectOnboardingStatus("COMPLETE");
        SubServiceEntity service = service(90);
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        when(appointmentRepo.findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                anyString(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findDuplicateBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDateValue(anyString(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDate(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(availabilityRepo.findByStylerId(anyString())).thenReturn(Collections.emptyList());
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString())).thenReturn(Optional.empty());

        CardDetailsEntity card = new CardDetailsEntity();
        card.setStripeCustomerId("cus_123");
        card.setStripePaymentMethodId("pm_123");
        when(cardDetailsRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(card));
        when(stripeService.isConfigured()).thenReturn(true);
        when(stripeService.authorizeBookingPayment(anyString(), anyString(), anyLong(), anyString(), any(), anyLong()))
                .thenThrow(new CardException("Your card was declined", "req_1", "card_declined",
                        "insufficient_funds", null, null, 402, null));

        BaseResponse response = services.bookingService().bookAppointment(data);

        assertEquals("400", response.getStatusCode());
        org.junit.jupiter.api.Assertions.assertTrue(
                response.getMessage().toLowerCase().contains("card was declined"));
        Map<String, Object> errorData = (Map<String, Object>) response.getData();
        assertEquals("CARD_DECLINED", errorData.get("paymentError"));
        verify(appointmentRepo, never()).saveAndFlush(any());
    }

    @Test
    void expiredCardBookingReturnsExpiredCode() throws Exception {
        BookAppointmentData data = bookingData();
        data.setAppointmentDate(LocalDate.now().plusDays(1).toString());
        data.setPaymentMethodId("pm_123");
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        StylerEntity styler = approvedStyler();
        styler.setConnectOnboardingStatus("COMPLETE");
        SubServiceEntity service = service(90);
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        when(appointmentRepo.findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                anyString(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findDuplicateBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDateValue(anyString(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDate(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(availabilityRepo.findByStylerId(anyString())).thenReturn(Collections.emptyList());
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString())).thenReturn(Optional.empty());

        CardDetailsEntity card = new CardDetailsEntity();
        card.setStripeCustomerId("cus_123");
        card.setStripePaymentMethodId("pm_123");
        when(cardDetailsRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(card));
        when(stripeService.isConfigured()).thenReturn(true);
        when(stripeService.authorizeBookingPayment(anyString(), anyString(), anyLong(), anyString(), any(), anyLong()))
                .thenThrow(new CardException("Your card has expired", "req_2", "expired_card",
                        null, null, null, 402, null));

        BaseResponse response = services.bookingService().bookAppointment(data);

        assertEquals("400", response.getStatusCode());
        Map<String, Object> errorData = (Map<String, Object>) response.getData();
        assertEquals("CARD_EXPIRED", errorData.get("paymentError"));
    }

    @Test
    void connectedStylistBookingRoutesTransferAndCommission() throws Exception {
        BookAppointmentData data = bookingData();
        data.setAppointmentDate(LocalDate.now().plusDays(1).toString());
        data.setPaymentMethodId("pm_123");
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        StylerEntity styler = approvedStyler();
        styler.setStripeConnectAccountId("acct_123");
        styler.setConnectOnboardingStatus("COMPLETE");
        SubServiceEntity service = service(90);
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        when(appointmentRepo.findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                anyString(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findDuplicateBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDateValue(anyString(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDate(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(availabilityRepo.findByStylerId(anyString())).thenReturn(Collections.emptyList());
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString())).thenReturn(Optional.empty());

        CardDetailsEntity card = new CardDetailsEntity();
        card.setStripeCustomerId("cus_123");
        card.setStripePaymentMethodId("pm_123");
        when(cardDetailsRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(card));
        when(stripeService.isConfigured()).thenReturn(true);
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_123");
        when(stripeService.authorizeBookingPayment(anyString(), anyString(), anyLong(), anyString(), any(), anyLong()))
                .thenReturn(pi);
        when(appointmentRepo.saveAndFlush(any(BookAppointmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BaseResponse response = services.bookingService().bookAppointment(data);

        assertEquals("200", response.getStatusCode());
        // Service price 100.00 -> 10000 cents; 10% commission -> 1000 cents, routed to the stylist's account.
        verify(stripeService).authorizeBookingPayment(eq("cus_123"), eq("pm_123"), eq(10000L), anyString(),
                eq("acct_123"), eq(1000L));
        verify(appointmentRepo).saveAndFlush(argThat(appointment ->
                "pi_123".equals(appointment.getPaymentIntentId())
                        && "AUTHORIZED".equals(appointment.getPaymentStatus())
                        && "100.00".equals(appointment.getPaymentAmount())));
    }

    @Test
    void bookingBlockedForStylerNotOnboardedWhenPaymentsLive() {
        BookAppointmentData data = bookingData();
        data.setAppointmentDate(LocalDate.now().plusDays(1).toString());
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        StylerEntity styler = approvedStyler();
        styler.setStripeConnectAccountId("acct_123"); // created but onboarding not COMPLETE
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(styler));
        when(stripeService.isConfigured()).thenReturn(true);

        BaseResponse response = services.bookingService().bookAppointment(data);

        assertEquals("400", response.getStatusCode());
        assertEquals("This professional is not yet available for online booking", response.getMessage());
        verifyNoInteractions(appointmentRepo);
    }

    @Test
    void bookingAllowedForOnboardedStylerWhenPaymentsLive() throws Exception {
        BookAppointmentData data = bookingData();
        data.setAppointmentDate(LocalDate.now().plusDays(1).toString());
        data.setPaymentMethodId("pm_123");
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        StylerEntity styler = approvedStyler();
        styler.setStripeConnectAccountId("acct_123");
        styler.setConnectOnboardingStatus("COMPLETE");
        SubServiceEntity service = service(90);
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        when(appointmentRepo.findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                anyString(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findDuplicateBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDateValue(anyString(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDate(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(availabilityRepo.findByStylerId(anyString())).thenReturn(Collections.emptyList());
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString())).thenReturn(Optional.empty());

        CardDetailsEntity card = new CardDetailsEntity();
        card.setStripeCustomerId("cus_123");
        card.setStripePaymentMethodId("pm_123");
        when(cardDetailsRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(card));
        when(stripeService.isConfigured()).thenReturn(true);
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_123");
        when(stripeService.authorizeBookingPayment(anyString(), anyString(), anyLong(), anyString(), any(), anyLong()))
                .thenReturn(pi);
        when(appointmentRepo.saveAndFlush(any(BookAppointmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BaseResponse response = services.bookingService().bookAppointment(data);

        assertEquals("200", response.getStatusCode());
    }

    @Test
    void slotCollisionEscapesBookingServiceForTransactionRollback() {
        BookAppointmentData data = bookingData();
        data.setAppointmentDate(LocalDate.now().plusDays(1).toString());
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        StylerEntity styler = approvedStyler();
        SubServiceEntity service = service(90);
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        when(appointmentRepo.findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                anyString(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findDuplicateBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDateValue(anyString(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDate(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(availabilityRepo.findByStylerId(anyString())).thenReturn(Collections.emptyList());
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString())).thenReturn(Optional.empty());
        when(appointmentRepo.saveAndFlush(any(BookAppointmentEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate appointment"));

        assertThrows(DataIntegrityViolationException.class, () -> services.bookingService().bookAppointment(data));
        verify(slotLockRepo, never()).saveAllAndFlush(any());
    }

    @Test
    void homeServiceBookingStoresTravelFeeOnlyForDistanceAboveIncludedRadius() {
        BookAppointmentData data = bookingData();
        data.setServiceTime("homeService");
        data.setTravelDistanceKm(22.5);
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        StylerEntity styler = approvedStyler();
        styler.setIncludedTravelKm(15.0);
        styler.setBaseTravelFee("25.00");
        SubServiceEntity service = service(90);
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        when(appointmentRepo.findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                anyString(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findDuplicateBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDateValue(anyString(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDate(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(availabilityRepo.findByStylerId(anyString())).thenReturn(Collections.emptyList());
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString())).thenReturn(Optional.empty());
        when(appointmentRepo.saveAndFlush(any(BookAppointmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BaseResponse response = services.bookingService().bookAppointment(data);

        assertEquals("200", response.getStatusCode());
        verify(appointmentRepo).saveAndFlush(argThat(appointment ->
                "100.00".equals(appointment.getServicePrice())
                        && "25.00".equals(appointment.getTravelFee())
                        && "125.00".equals(appointment.getPrice())
                        && Double.valueOf(15.0).equals(appointment.getIncludedTravelKm())
                        && Double.valueOf(22.5).equals(appointment.getTravelDistanceKm())
                        && Double.valueOf(7.5).equals(appointment.getBillableTravelKm())
                        && "25.00".equals(appointment.getBaseTravelFee())));
    }

    @Test
    void homeServiceBookingWithinIncludedRadiusHasNoTravelFee() {
        BookAppointmentData data = bookingData();
        data.setServiceTime("homeService");
        data.setTravelDistanceKm(12.0);
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        StylerEntity styler = approvedStyler();
        styler.setIncludedTravelKm(15.0);
        styler.setBaseTravelFee("25.00");
        SubServiceEntity service = service(90);
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        when(appointmentRepo.findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                anyString(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findDuplicateBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDateValue(anyString(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDate(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(availabilityRepo.findByStylerId(anyString())).thenReturn(Collections.emptyList());
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString())).thenReturn(Optional.empty());
        when(appointmentRepo.saveAndFlush(any(BookAppointmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BaseResponse response = services.bookingService().bookAppointment(data);

        assertEquals("200", response.getStatusCode());
        verify(appointmentRepo).saveAndFlush(argThat(appointment ->
                "100.00".equals(appointment.getServicePrice())
                        && "0.00".equals(appointment.getTravelFee())
                        && "100.00".equals(appointment.getPrice())
                        && Double.valueOf(0.0).equals(appointment.getBillableTravelKm())));
    }

    @Test
    void visitStylistBookingDoesNotRequireDistanceOrAddTravelFee() {
        BookAppointmentData data = bookingData();
        data.setServiceTime("visitBarber");
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        StylerEntity styler = approvedStyler();
        SubServiceEntity service = service(90);
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        when(appointmentRepo.findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                anyString(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findDuplicateBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDateValue(anyString(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDate(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(availabilityRepo.findByStylerId(anyString())).thenReturn(Collections.emptyList());
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString())).thenReturn(Optional.empty());
        when(appointmentRepo.saveAndFlush(any(BookAppointmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BaseResponse response = services.bookingService().bookAppointment(data);

        assertEquals("200", response.getStatusCode());
        verify(appointmentRepo).saveAndFlush(argThat(appointment ->
                "100.00".equals(appointment.getServicePrice())
                        && "0.00".equals(appointment.getTravelFee())
                        && "100.00".equals(appointment.getPrice())
                        && Double.valueOf(15.0).equals(appointment.getIncludedTravelKm())
                        && Double.valueOf(0.0).equals(appointment.getTravelDistanceKm())
                        && Double.valueOf(0.0).equals(appointment.getBillableTravelKm())));
    }

    @Test
    void bookingQueuesAppointmentNotificationInsteadOfSendingEmailSynchronously() {
        BookAppointmentData data = bookingData();
        data.setAppointmentDate(LocalDate.now().plusDays(1).toString());
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        customer.setEmailAddress("customer@example.com");
        StylerEntity styler = approvedStyler();
        styler.setEmailAddress("styler@example.com");
        SubServiceEntity service = service(90);
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        when(appointmentRepo.findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                anyString(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findDuplicateBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDateValue(anyString(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDate(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(availabilityRepo.findByStylerId(anyString())).thenReturn(Collections.emptyList());
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString())).thenReturn(Optional.empty());
        when(appointmentRepo.saveAndFlush(any(BookAppointmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BaseResponse response = services.bookingService().bookAppointment(data);

        assertEquals("200", response.getStatusCode());
        verify(outboxEventService).appointmentNotification(any(BookAppointmentEntity.class),
                org.mockito.ArgumentMatchers.eq("Request"),
                org.mockito.ArgumentMatchers.eq("Booking request received"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("New booking request"),
                org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(services.emailConfig);
    }

    @Test
    void appointmentDtoIncludesTravelBreakdownForTechnicianAndClientDisclosure() {
        com.macrotel.rapidstylers.common.service.DTOService dtoService = new com.macrotel.rapidstylers.common.service.DTOService();
        BookAppointmentEntity appointment = appointment("APPT-TRAVEL", "1");
        appointment.setServicePrice("100.00");
        appointment.setTravelFee("25.00");
        appointment.setPrice("125.00");
        appointment.setIncludedTravelKm(15.0);
        appointment.setTravelDistanceKm(22.5);
        appointment.setBillableTravelKm(7.5);
        appointment.setBaseTravelFee("25.00");

        AppointmentDTO dto = dtoService.appointmentDTO(appointment);

        assertEquals("100.00", dto.getServicePrice());
        assertEquals("25.00", dto.getTravelFee());
        assertEquals("125.00", dto.getPrice());
        assertEquals(15.0, dto.getIncludedTravelKm());
        assertEquals(22.5, dto.getTravelDistanceKm());
        assertEquals(7.5, dto.getBillableTravelKm());
        assertEquals("25.00", dto.getBaseTravelFee());
    }

    @Test
    void homeServiceBookingRejectsMissingTravelDistance() {
        BookAppointmentData data = bookingData();
        data.setServiceTime("homeService");
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(approvedStyler()));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service(90)));

        BaseResponse response = services.bookingService().bookAppointment(data);

        assertEquals("400", response.getStatusCode());
        assertEquals("Travel distance is required for home service bookings", response.getMessage());
        verifyNoInteractions(appointmentRepo);
    }

    @Test
    void declineAndCustomerCancelReleaseTheirHeldLocks() {
        BookAppointmentEntity pending = appointment("1");
        when(appointmentRepo.findByAppointmentIdForUpdate("APPT-1")).thenReturn(Optional.of(pending));

        BaseResponse declined = services.appointmentWorkflowService().declineAppointment("STYLER1", "APPT-1");
        assertEquals("200", declined.getStatusCode());
        verify(slotLockRepo).deleteByAppointmentId("APPT-1");

        BookAppointmentEntity accepted = appointment("APPT-2", "3");
        when(appointmentRepo.findByAppointmentIdForUpdate("APPT-2")).thenReturn(Optional.of(accepted));
        BaseResponse cancelled = services.appointmentWorkflowService().cancelAppointment("CUSTOMER1", "APPT-2");
        assertEquals("200", cancelled.getStatusCode());
        verify(slotLockRepo).deleteByAppointmentId("APPT-2");
    }

    @Test
    void acceptWithDecisionNoteRecordsSanitizedNote() {
        BookAppointmentEntity pending = appointment("1");
        pending.setAppointmentId("APPT-NOTE");
        when(appointmentRepo.findByAppointmentIdForUpdate("APPT-NOTE")).thenReturn(Optional.of(pending));

        BaseResponse accepted = services.appointmentWorkflowService().acceptAppointment("STYLER1", "APPT-NOTE", " Accepted anyway — far but worth it ");

        assertEquals("200", accepted.getStatusCode());
        assertEquals("3", pending.getStatus());
        assertEquals("Accepted anyway — far but worth it", pending.getStylerNote());
    }

    @Test
    void decisionNoteIsSanitizedAndMirroredInDto() {
        BookAppointmentEntity pending = appointment("1");
        pending.setAppointmentId("APPT-NOTE2");
        when(appointmentRepo.findByAppointmentIdForUpdate("APPT-NOTE2")).thenReturn(Optional.of(pending));

        services.appointmentWorkflowService().acceptAppointment("STYLER1", "APPT-NOTE2", "<script>alert(1)</script>accepted");
        assertNotNull(pending.getStylerNote());
        assertFalse(pending.getStylerNote().contains("<script>"));

        assertEquals(pending.getStylerNote(), new com.macrotel.rapidstylers.common.service.DTOService().appointmentDTO(pending).getStylerNote());
    }

    @Test
    void bookingAndCardControllerMethodsUseRequestAccountId() {
        BookingController controller = new BookingController();
        BookingService service = mock(BookingService.class);
        ReflectionTestUtils.setField(controller, "bookingService", service);
        BookingRetries retries = new BookingRetries();
        ReflectionTestUtils.setField(retries, "bookingService", service);
        ReflectionTestUtils.setField(controller, "bookingRetries", retries);
        PaymentController paymentController = new PaymentController();
        CardService cardService = mock(CardService.class);
        ReflectionTestUtils.setField(paymentController, "cardService", cardService);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("accountId")).thenReturn("JWT-CUSTOMER");
        BaseResponse success = new BaseResponse(true);
        success.setStatusCode("200");
        when(service.bookAppointment(any(BookAppointmentData.class))).thenReturn(success);
        when(cardService.updateUserCardDetails(any(CardDetailsData.class))).thenReturn(success);

        BookAppointmentData booking = bookingData();
        booking.setUserId("BODY-USER");
        controller.bookAppointment(booking, request);
        verify(service).bookAppointment(org.mockito.ArgumentMatchers.argThat(value ->
                "JWT-CUSTOMER".equals(value.getUserId())));

        CardDetailsData card = new CardDetailsData();
        card.setUserId("BODY-USER");
        paymentController.updateCardDetails(card, request);
        verify(cardService).updateUserCardDetails(org.mockito.ArgumentMatchers.argThat(value ->
                "JWT-CUSTOMER".equals(value.getUserId())));
    }

    @Test
    void homeServiceDistanceIsMeasuredFromTheVisitAddressNotTheClientNumber() {
        BookAppointmentData data = bookingData();
        data.setServiceTime("homeService");
        data.setServiceAddress("  10 Main St, Calgary  ");
        data.setTravelDistanceKm(1.0); // a client-sent number is ignored when an address is given
        StylerEntity styler = homeVisitStyler();
        stubBookableHomeVisit(styler);
        GeocodingService geocoding = mock(GeocodingService.class);
        // About 0.2 degrees of latitude north of the stylist: roughly 22.2 km.
        when(geocoding.geocode("10 Main St, Calgary")).thenReturn(Map.of(
                "latitude", 51.2447, "longitude", -114.0719,
                "formattedAddress", "10 Main St NW, Calgary, AB"));
        services.geocodingService = geocoding;

        BaseResponse response = services.bookingService().bookAppointment(data);

        assertEquals("200", response.getStatusCode());
        verify(appointmentRepo).saveAndFlush(argThat(appointment ->
                Double.valueOf(22.2).equals(appointment.getTravelDistanceKm())
                        && "25.00".equals(appointment.getTravelFee())
                        && "10 Main St NW, Calgary, AB".equals(appointment.getServiceAddress())));
    }

    @Test
    void estimateReturnsTheServerMeasuredDistanceAndVisitAddress() {
        BookAppointmentData data = bookingData();
        data.setServiceTime("homeService");
        data.setServiceAddress("10 Main St, Calgary");
        StylerEntity styler = homeVisitStyler();
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service(90)));
        GeocodingService geocoding = mock(GeocodingService.class);
        when(geocoding.geocode("10 Main St, Calgary")).thenReturn(Map.of(
                "latitude", 51.0547, "longitude", -114.0719));
        services.geocodingService = geocoding;

        BaseResponse response = services.bookingService().estimateBooking(data);

        assertEquals("200", response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> estimate = (Map<String, Object>) response.getData();
        assertEquals(1.1, estimate.get("travelDistanceKm"));
        assertEquals("10 Main St, Calgary", estimate.get("serviceAddress"));
        assertEquals("0.00", estimate.get("travelFee"));
    }

    @Test
    void homeServiceRejectsAnAddressThatCannotBeFound() {
        BookAppointmentData data = bookingData();
        data.setServiceTime("homeService");
        data.setServiceAddress("nowhere at all");
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(homeVisitStyler()));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service(90)));
        services.geocodingService = mock(GeocodingService.class); // geocode() returns null

        BaseResponse response = services.bookingService().estimateBooking(data);

        assertEquals("400", response.getStatusCode());
        assertEquals("We couldn't find that address. Check it and try again", response.getMessage());
    }

    @Test
    void homeServiceRejectsAVisitAddressWhenTheStylistHasNoLocation() {
        BookAppointmentData data = bookingData();
        data.setServiceTime("homeService");
        data.setServiceAddress("10 Main St, Calgary");
        StylerEntity styler = homeVisitStyler();
        styler.setLatitude(null);
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service(90)));
        GeocodingService geocoding = mock(GeocodingService.class);
        services.geocodingService = geocoding;

        BaseResponse response = services.bookingService().estimateBooking(data);

        assertEquals("400", response.getStatusCode());
        assertEquals("This professional's location isn't set, so home visits can't be priced yet", response.getMessage());
        verifyNoInteractions(geocoding);
    }

    @Test
    void aBookingKeepsItsRetryKeyAndFingerprint() {
        BookAppointmentData data = bookingData();
        data.setServiceTime("visitBarber");
        data.setIdempotencyKey("retry-key-1");
        stubBookableHomeVisit(approvedStyler());

        BaseResponse response = services.bookingService().bookAppointment(data, "fp-1");

        assertEquals("200", response.getStatusCode());
        verify(appointmentRepo).saveAndFlush(argThat(appointment ->
                "retry-key-1".equals(appointment.getIdempotencyKey())
                        && "fp-1".equals(appointment.getIdempotencyFingerprint())));
    }

    @Test
    void aTwinThatBookedWhileWeWaitedForTheLockStopsUsBeforeTheCard() throws Exception {
        BookAppointmentData data = bookingData();
        data.setServiceTime("visitBarber");
        data.setIdempotencyKey("retry-key-1");
        stubBookableHomeVisit(approvedStyler());
        when(stripeService.isConfigured()).thenReturn(true);
        when(appointmentRepo.findByIdempotencyKey("retry-key-1")).thenReturn(Optional.of(appointment("1")));

        BaseResponse response = services.bookingService().bookAppointment(data, "fp-1");

        assertEquals(BookingService.ALREADY_SUBMITTED, response.getMessage());
        verify(appointmentRepo, never()).saveAndFlush(any());
        verify(stripeService, never()).authorizeBookingPayment(any(), any(), anyLong(), any(), any(), anyLong());
    }

    private StylerEntity homeVisitStyler() {
        StylerEntity styler = approvedStyler();
        styler.setLatitude(51.0447);
        styler.setLongitude(-114.0719);
        styler.setIncludedTravelKm(15.0);
        styler.setBaseTravelFee("25.00");
        return styler;
    }

    private void stubBookableHomeVisit(StylerEntity styler) {
        UserEntity customer = new UserEntity();
        customer.setUserId("CUSTOMER1");
        when(userRepo.findByUserId("CUSTOMER1")).thenReturn(Optional.of(customer));
        when(stylerRepo.findByStylerIdForUpdate("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service(90)));
        when(appointmentRepo.findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                anyString(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findDuplicateBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDateValue(anyString(), any())).thenReturn(Collections.emptyList());
        when(appointmentRepo.findByStylerIdAndAppointmentDate(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(availabilityRepo.findByStylerId(anyString())).thenReturn(Collections.emptyList());
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString())).thenReturn(Optional.empty());
        when(appointmentRepo.saveAndFlush(any(BookAppointmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private BookAppointmentData bookingData() {
        BookAppointmentData data = new BookAppointmentData();
        data.setUserId("CUSTOMER1");
        data.setStylerId("STYLER1");
        data.setSubServiceId("1");
        data.setAppointmentDate("2030-08-24");
        data.setArrivalTime("09:30");
        data.setNoOfPeople("1");
        return data;
    }

    private StylerEntity approvedStyler() {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        styler.setVerificationStatus("APPROVED");
        // Booking requires a published address, so the fixture carries one and
        // each test varies only the thing it is actually about.
        styler.setBusinessAddress("700 2 St SW, Calgary, Alberta");
        return styler;
    }

    private SubServiceEntity service(int duration) {
        SubServiceEntity service = new SubServiceEntity();
        service.setId(1L);
        service.setStylerId("STYLER1");
        service.setPrice("100.00");
        service.setDurationMinutes(duration);
        return service;
    }

    private BookAppointmentEntity appointment(String status) {
        return appointment("APPT-1", status);
    }

    private BookAppointmentEntity appointment(String id, String status) {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setAppointmentId(id);
        appointment.setUserId("CUSTOMER1");
        appointment.setStylerId("STYLER1");
        appointment.setAppointmentDate("2030-08-24");
        appointment.setArrivalTime("09:30");
        appointment.setAppointmentDateValue(LocalDate.of(2030, 8, 24));
        appointment.setAppointmentStartTime(LocalTime.of(9, 30));
        appointment.setStatus(status);
        appointment.setSubServiceId("1");
        return appointment;
    }

    private static <T> java.util.List<T> argThatListSize(int size) {
        return org.mockito.ArgumentMatchers.argThat(list -> list != null && list.size() == size);
    }
}

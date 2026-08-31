package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.controller.ApplicationController;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.CardDetailsEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.repo.CardDetailsRepo;
import com.stripe.exception.CardException;
import com.stripe.model.PaymentIntent;
import com.macrotel.rapidstylers.dto.AppointmentDTO;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.BookAppointmentData;
import com.macrotel.rapidstylers.pojo.CardDetailsData;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.BookingSlotLockRepo;
import com.macrotel.rapidstylers.repo.AvailabilityExceptionRepo;
import com.macrotel.rapidstylers.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingWorkflowTest {

    private AppService appService;
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
        appService = new AppService();
        slotLockRepo = mock(BookingSlotLockRepo.class);
        appointmentRepo = mock(BookAppointmentRepo.class);
        stylerRepo = mock(StylerRepo.class);
        userRepo = mock(UserRepo.class);
        subServiceRepo = mock(SubServiceRepo.class);
        availabilityRepo = mock(AvailabilityRepo.class);
        availabilityExceptionRepo = mock(AvailabilityExceptionRepo.class);
        outboxEventService = mock(OutboxEventService.class);
        cardDetailsRepo = mock(CardDetailsRepo.class);
        appService.bookingSlotLockRepo = slotLockRepo;
        appService.bookAppointmentRepo = appointmentRepo;
        appService.stylerRepo = stylerRepo;
        appService.userRepo = userRepo;
        appService.subServiceRepo = subServiceRepo;
        appService.availabilityRepo = availabilityRepo;
        appService.availabilityExceptionRepo = availabilityExceptionRepo;
        appService.emailConfig = mock(EmailConfig.class);
        appService.outboxEventService = outboxEventService;
        appService.cardDetailsRepo = cardDetailsRepo;
        // Payments disabled in tests: booking proceeds without Stripe authorization.
        stripeService = mock(StripeService.class);
        when(stripeService.isConfigured()).thenReturn(false);
        appService.stripeService = stripeService;
        // Same default as application.properties (STRIPE_COMMISSION_PERCENT).
        ReflectionTestUtils.setField(appService, "stripeCommissionPercent", 10.0);
        ReflectionTestUtils.setField(appService, "paymentAuthorizationWindowDays", 7L);
        ReflectionTestUtils.setField(appService, "paymentAuthorizationLeadHours", 48L);
    }

    @Test
    void ninetyMinuteBookingCreatesSixQuarterHourLocks() throws Exception {
        Method reserve = AppService.class.getDeclaredMethod(
                "reserveBookingSlots", BookAppointmentEntity.class, LocalDate.class, LocalTime.class, int.class);
        reserve.setAccessible(true);
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setStylerId("STYLER1");
        appointment.setAppointmentId("APPT-1");

        reserve.invoke(appService, appointment, LocalDate.of(2030, 8, 24), LocalTime.of(9, 30), 90);

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

        BaseResponse response = appService.bookAppointment(data);

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

        BaseResponse response = appService.bookAppointment(data);

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

        BaseResponse response = appService.bookAppointment(data);

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

        BaseResponse response = appService.bookAppointment(data);

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

        BaseResponse response = appService.bookAppointment(data);

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

        assertThrows(DataIntegrityViolationException.class, () -> appService.bookAppointment(data));
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

        BaseResponse response = appService.bookAppointment(data);

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

        BaseResponse response = appService.bookAppointment(data);

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

        BaseResponse response = appService.bookAppointment(data);

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

        BaseResponse response = appService.bookAppointment(data);

        assertEquals("200", response.getStatusCode());
        verify(outboxEventService).appointmentNotification(any(BookAppointmentEntity.class),
                org.mockito.ArgumentMatchers.eq("Request"),
                org.mockito.ArgumentMatchers.eq("Booking request received"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("New booking request"),
                org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(appService.emailConfig);
    }

    @Test
    void appointmentDtoIncludesTravelBreakdownForTechnicianAndClientDisclosure() {
        com.macrotel.rapidstylers.service.DTOService dtoService = new com.macrotel.rapidstylers.service.DTOService();
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

        BaseResponse response = appService.bookAppointment(data);

        assertEquals("400", response.getStatusCode());
        assertEquals("Travel distance is required for home service bookings", response.getMessage());
        verifyNoInteractions(appointmentRepo);
    }

    @Test
    void declineAndCustomerCancelReleaseTheirHeldLocks() {
        BookAppointmentEntity pending = appointment("1");
        when(appointmentRepo.findByAppointmentIdForUpdate("APPT-1")).thenReturn(Optional.of(pending));

        BaseResponse declined = appService.declineAppointment("STYLER1", "APPT-1");
        assertEquals("200", declined.getStatusCode());
        verify(slotLockRepo).deleteByAppointmentId("APPT-1");

        BookAppointmentEntity accepted = appointment("APPT-2", "3");
        when(appointmentRepo.findByAppointmentIdForUpdate("APPT-2")).thenReturn(Optional.of(accepted));
        BaseResponse cancelled = appService.cancelAppointment("CUSTOMER1", "APPT-2");
        assertEquals("200", cancelled.getStatusCode());
        verify(slotLockRepo).deleteByAppointmentId("APPT-2");
    }

    @Test
    void acceptWithDecisionNoteRecordsSanitizedNote() {
        BookAppointmentEntity pending = appointment("1");
        pending.setAppointmentId("APPT-NOTE");
        when(appointmentRepo.findByAppointmentIdForUpdate("APPT-NOTE")).thenReturn(Optional.of(pending));

        BaseResponse accepted = appService.acceptAppointment("STYLER1", "APPT-NOTE", " Accepted anyway — far but worth it ");

        assertEquals("200", accepted.getStatusCode());
        assertEquals("3", pending.getStatus());
        assertEquals("Accepted anyway — far but worth it", pending.getStylerNote());
    }

    @Test
    void decisionNoteIsSanitizedAndMirroredInDto() {
        BookAppointmentEntity pending = appointment("1");
        pending.setAppointmentId("APPT-NOTE2");
        when(appointmentRepo.findByAppointmentIdForUpdate("APPT-NOTE2")).thenReturn(Optional.of(pending));

        appService.acceptAppointment("STYLER1", "APPT-NOTE2", "<script>alert(1)</script>accepted");
        assertNotNull(pending.getStylerNote());
        assertFalse(pending.getStylerNote().contains("<script>"));

        assertEquals(pending.getStylerNote(), new com.macrotel.rapidstylers.service.DTOService().appointmentDTO(pending).getStylerNote());
    }

    @Test
    void bookingAndCardControllerMethodsUseRequestAccountId() {
        ApplicationController controller = new ApplicationController();
        AppService service = mock(AppService.class);
        ReflectionTestUtils.setField(controller, "appService", service);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("accountId")).thenReturn("JWT-CUSTOMER");
        BaseResponse success = new BaseResponse(true);
        success.setStatusCode("200");
        when(service.bookAppointment(any(BookAppointmentData.class))).thenReturn(success);
        when(service.updateUserCardDetails(any(CardDetailsData.class))).thenReturn(success);

        BookAppointmentData booking = bookingData();
        booking.setUserId("BODY-USER");
        controller.bookAppointment(booking, request);
        verify(service).bookAppointment(org.mockito.ArgumentMatchers.argThat(value ->
                "JWT-CUSTOMER".equals(value.getUserId())));

        CardDetailsData card = new CardDetailsData();
        card.setUserId("BODY-USER");
        controller.updateCardDetails(card, request);
        verify(service).updateUserCardDetails(org.mockito.ArgumentMatchers.argThat(value ->
                "JWT-CUSTOMER".equals(value.getUserId())));
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

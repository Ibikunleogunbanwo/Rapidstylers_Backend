package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.payment.entity.CardDetailsEntity;
import com.macrotel.rapidstylers.payment.repo.CardDetailsRepo;
import com.macrotel.rapidstylers.payment.service.MoneyUtils;
import com.macrotel.rapidstylers.payment.service.PaymentService;
import com.macrotel.rapidstylers.payment.service.StripeService;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.service.AvailabilityService;
import com.stripe.model.PaymentIntent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Booking payments that wait for their window: decides when a card is authorized, authorizes it once, and runs the scheduler that catches up on due bookings.
 */
@Service
public class PaymentAuthorizationService {
    private static final Logger LOG = Logger.getLogger(PaymentAuthorizationService.class.getName());
    @Autowired
    BookAppointmentRepo bookAppointmentRepo;
    @Autowired
    CardDetailsRepo cardDetailsRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    StripeService stripeService;
    // Initialized inline so bare construction (unit tests) behaves as before;
    // Spring replaces it with the context bean via @Autowired.
    @Autowired
    PaymentService paymentService = new PaymentService();
    @Autowired
    AvailabilityService availabilityService;
    @Value("${app.time-zone:America/Edmonton}")
    private String appTimeZone;
    @Value("${app.booking.payment-authorization-window-days:7}")
    private long paymentAuthorizationWindowDays;
    @Value("${app.booking.payment-authorization-lead-hours:48}")
    private long paymentAuthorizationLeadHours;

    private ZoneId applicationZone(){
        try {
            return ZoneId.of(appTimeZone == null || appTimeZone.isBlank() ? "America/Edmonton" : appTimeZone);
        } catch(Exception ex){
            return ZoneId.of("America/Edmonton");
        }
    }

    public boolean shouldAuthorizeNow(LocalDate appointmentDate, LocalTime appointmentStart){
        ZonedDateTime scheduled = ZonedDateTime.of(appointmentDate, appointmentStart, applicationZone());
        return !scheduled.isAfter(ZonedDateTime.now(applicationZone()).plusDays(paymentAuthorizationWindowDays));
    }

    public LocalDateTime paymentAuthorizationDueAt(LocalDate appointmentDate, LocalTime appointmentStart){
        ZonedDateTime scheduled = ZonedDateTime.of(appointmentDate, appointmentStart, applicationZone())
                .minusHours(paymentAuthorizationLeadHours);
        ZonedDateTime now = ZonedDateTime.now(applicationZone());
        return (scheduled.isBefore(now) ? now : scheduled).toLocalDateTime();
    }

    public LocalDateTime paymentAuthorizationDueAt(BookAppointmentEntity appointment){
        if(appointment.getPaymentAuthorizationDueAt() != null){
            return appointment.getPaymentAuthorizationDueAt();
        }
        LocalDate date = appointment.getAppointmentDateValue();
        LocalTime start = appointment.getAppointmentStartTime();
        if(date == null || start == null){
            date = availabilityService.parseBookingDate(appointment.getAppointmentDate());
            start = availabilityService.parseBookingTime(appointment.getArrivalTime());
        }
        return paymentAuthorizationDueAt(date, start);
    }

    /**
     * Authorizes a scheduled booking exactly once. When captureAfterAuthorization
     * is true, the stylist has already accepted and the authorized amount is
     * captured immediately after Stripe confirms the hold.
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentAttemptResult authorizeScheduledPayment(BookAppointmentEntity appointment, boolean captureAfterAuthorization){
        try {
            Optional<CardDetailsEntity> card = cardDetailsRepo.findByUserId(appointment.getUserId());
            if(card.isEmpty() || card.get().getStripeCustomerId() == null || card.get().getStripePaymentMethodId() == null){
                appointment.setPaymentStatus("PAYMENT_FAILED");
                appointment.setPaymentFailureCode("NO_PAYMENT_METHOD");
                bookAppointmentRepo.save(appointment);
                return PaymentAttemptResult.failure("NO_PAYMENT_METHOD", "Please add a payment method to complete this booking");
            }
            Optional<StylerEntity> styler = stylerRepo.findByStylerId(appointment.getStylerId());
            String destination = styler.filter(s -> s.getStripeConnectAccountId() != null && !s.getStripeConnectAccountId().isBlank())
                    .map(StylerEntity::getStripeConnectAccountId).orElse(null);
            long amountCents = MoneyUtils.centsFromPrice(appointment.getPaymentAmount() == null ? appointment.getPrice() : appointment.getPaymentAmount());
            PaymentIntent intent = stripeService.authorizeBookingPayment(
                    card.get().getStripeCustomerId(), card.get().getStripePaymentMethodId(),
                    amountCents, appointment.getAppointmentId(), destination, paymentService.commissionCents(amountCents));
            appointment.setPaymentIntentId(intent.getId());
            appointment.setPaymentStatus("AUTHORIZED");
            appointment.setPaymentFailureCode(null);
            appointment.setPaymentAuthorizationDueAt(null);
            if(captureAfterAuthorization){
                stripeService.captureBookingPayment(intent.getId());
                appointment.setPaymentStatus("CAPTURED");
                paymentService.sendPaymentReceipt(appointment);
            }
            bookAppointmentRepo.save(appointment);
            return PaymentAttemptResult.success();
        } catch(com.stripe.exception.CardException ex){
            String code = "authentication_required".equals(ex.getCode()) ? "PAYMENT_REQUIRES_ACTION"
                    : "expired_card".equals(ex.getCode()) ? "CARD_EXPIRED" : "CARD_DECLINED";
            appointment.setPaymentStatus("PAYMENT_REQUIRES_ACTION".equals(code) ? "PAYMENT_REQUIRES_ACTION" : "PAYMENT_FAILED");
            appointment.setPaymentFailureCode(code);
            bookAppointmentRepo.save(appointment);
            String message = "PAYMENT_REQUIRES_ACTION".equals(code)
                    ? "Please return to RapidStylers to confirm your payment."
                    : "Your saved card could not be charged. Please update it and try again.";
            return PaymentAttemptResult.failure(code, message);
        } catch(Exception ex){
            LOG.warning("Scheduled payment authorization failed: " + ex.getMessage());
            appointment.setPaymentStatus("PAYMENT_FAILED");
            appointment.setPaymentFailureCode("PAYMENT_ERROR");
            bookAppointmentRepo.save(appointment);
            return PaymentAttemptResult.failure("PAYMENT_ERROR", "Payment could not be completed. Please try again.");
        }
    }

    @Scheduled(fixedDelayString = "${app.booking.payment-scheduler-delay-ms:60000}")
    @Transactional(rollbackFor = Exception.class)
    public void processDuePaymentAuthorizations(){
        if(!stripeService.isConfigured()) return;
        LocalDateTime now = LocalDateTime.now(applicationZone());
        List<BookAppointmentEntity> due = bookAppointmentRepo
                .findByPaymentStatusAndPaymentAuthorizationDueAtBefore("PAYMENT_ACCEPTED_SCHEDULED", now);
        for(BookAppointmentEntity appointment : due){
            if(!"3".equals(appointment.getStatus())) continue;
            authorizeScheduledPayment(appointment, true);
        }
    }

    public static class PaymentAttemptResult {
        public final boolean success;
        public final String code;
        public final String message;
        private PaymentAttemptResult(boolean success, String code, String message){
            this.success = success;
            this.code = code;
            this.message = message;
        }
        private static PaymentAttemptResult success(){ return new PaymentAttemptResult(true, null, null); }
        private static PaymentAttemptResult failure(String code, String message){ return new PaymentAttemptResult(false, code, message); }
    }
}

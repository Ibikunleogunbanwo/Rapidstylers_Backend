package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.booking.repo.BookingSlotLockRepo;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.engagement.service.LoyaltyService;
import com.macrotel.rapidstylers.engagement.service.NotificationService;
import com.macrotel.rapidstylers.payment.service.PaymentService;
import com.macrotel.rapidstylers.payment.service.PayoutReleaseService;
import com.macrotel.rapidstylers.payment.service.StripeService;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.service.AvailabilityService;
import com.stripe.model.Customer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * What happens to a booking after it is made: the stylist accepts, declines, completes or cancels it, the customer cancels it or retries payment, and the money, slots and notifications follow.
 */
@Service
public class AppointmentWorkflowService {
    private static final Logger LOG = Logger.getLogger(AppointmentWorkflowService.class.getName());
    @Autowired
    BookAppointmentRepo bookAppointmentRepo;
    @Autowired
    BookingSlotLockRepo bookingSlotLockRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    StripeService stripeService;
    @Autowired
    ReadCacheService readCacheService;
    // Initialized inline so bare construction (unit tests) behaves as before;
    // Spring replaces both with the context beans via @Autowired.
    @Autowired
    PaymentService paymentService = new PaymentService();
    @Autowired
    AuditService auditService = new AuditService();
    @Autowired
    LoyaltyService loyaltyService;
    @Autowired
    NotificationService notificationService;
    @Autowired
    AvailabilityService availabilityService;
    @Autowired
    PaymentAuthorizationService paymentAuthorizationService;
    @Autowired
    PayoutReleaseService payoutReleaseService;
    @Value("${app.time-zone:America/Edmonton}")
    private String appTimeZone;
    @Value("${app.booking.completion-grace-minutes:0}")
    private long completionGraceMinutes;
    /** Stylists may cancel a completed booking only within this window (hours since completion). */
    @Value("${app.booking.styler-cancel-window-hours:24}")
    private long stylerCancelWindowHours;


    /** Null-safe eviction — unit tests that build this service without a cache never break. */
    private void evict(String... keys){
        if(readCacheService != null){
            readCacheService.evict(keys);
        }
    }

    private ZoneId applicationZone(){
        try {
            return ZoneId.of(appTimeZone == null || appTimeZone.isBlank() ? "America/Edmonton" : appTimeZone);
        } catch(Exception ex){
            return ZoneId.of("America/Edmonton");
        }
    }

    private boolean appointmentStartHasPassed(BookAppointmentEntity appointment){
        LocalDate date = appointment.getAppointmentDateValue();
        LocalTime start = appointment.getAppointmentStartTime();
        if(date == null || start == null){
            try {
                date = availabilityService.parseBookingDate(appointment.getAppointmentDate());
                start = availabilityService.parseBookingTime(appointment.getArrivalTime());
            } catch(Exception ex){
                return false;
            }
        }
        ZonedDateTime scheduled = ZonedDateTime.of(date, start, applicationZone());
        return !ZonedDateTime.now(applicationZone()).isBefore(scheduled.plusMinutes(completionGraceMinutes));
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse acceptAppointment(String stylerId, String appointmentId){
        return acceptAppointment(stylerId, appointmentId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse acceptAppointment(String stylerId, String appointmentId, String decisionNote){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "3", new String[]{"1"},
                "Appointment confirmed", decisionNote);
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse declineAppointment(String stylerId, String appointmentId){
        return declineAppointment(stylerId, appointmentId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse declineAppointment(String stylerId, String appointmentId, String decisionNote){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "2", new String[]{"1"},
                "Appointment rejected", decisionNote);
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse completeAppointment(String stylerId, String appointmentId){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "0", new String[]{"3"}, "Appointment marked as completed");
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse cancelAppointment(String userId, String appointmentId){
        return transitionAppointment(userId, ActorRole.CUSTOMER, appointmentId, "4", new String[]{"1", "3"}, "Appointment cancelled");
    }

    /**
     * Stylist-initiated cancellation of their own appointment. Covers the
     * completes-then-cancels edge case: a booking already marked completed
     * (payment captured) is cancelled by the stylist who completed it, and
     * the captured payment is refunded automatically — exactly once.
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse stylerCancelAppointment(String stylerId, String appointmentId){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "4", new String[]{"3", "0"}, "Appointment cancelled");
    }

    /** Customer retry path for a deferred or failed payment. */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse retryAppointmentPayment(String userId, String appointmentId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<BookAppointmentEntity> found = bookAppointmentRepo.findByAppointmentId(appointmentId);
            if(found.isEmpty()) return Responses.errorResponse(response, "Invalid Appointment Id");
            BookAppointmentEntity appointment = found.get();
            if(!userId.equals(appointment.getUserId())) return Responses.errorResponse(response, "You do not have permission to update this appointment");
            if(!Arrays.asList("PAYMENT_FAILED", "PAYMENT_REQUIRES_ACTION", "PAYMENT_ACCEPTED_SCHEDULED").contains(appointment.getPaymentStatus())){
                return Responses.errorResponse(response, "This appointment does not need a payment retry");
            }
            PaymentAuthorizationService.PaymentAttemptResult payment = paymentAuthorizationService.authorizeScheduledPayment(appointment, "3".equals(appointment.getStatus()));
            if(!payment.success){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage(payment.message);
                response.setData(Collections.singletonMap("paymentError", payment.code));
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Payment completed successfully");
            response.setData(EMPTY_DATA);
        } catch(Exception ex){
            LOG.warning("Payment retry failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Payment could not be completed. Please try again.");
        }
        return response;
    }

    private enum ActorRole { STYLER, CUSTOMER }

    /**
     * Loads an appointment, verifies the actor owns the correct side of it,
     * and applies a status transition only when the current state is allowed.
     * Status codes: 1 pending, 3 accepted, 2 rejected, 0 completed, 4 cancelled.
     */
    private BaseResponse transitionAppointment(String ownerId, ActorRole actorRole, String appointmentId,
                                               String newStatus, String[] allowedFrom, String successMessage){
        return transitionAppointment(ownerId, actorRole, appointmentId, newStatus, allowedFrom, successMessage, null);
    }

    private BaseResponse transitionAppointment(String ownerId, ActorRole actorRole, String appointmentId,
                                               String newStatus, String[] allowedFrom, String successMessage,
                                               String decisionNote){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<BookAppointmentEntity> isExist = bookAppointmentRepo.findByAppointmentIdForUpdate(appointmentId);
            if(isExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Appointment Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            BookAppointmentEntity appointment = isExist.get();
            boolean ownsAppointment = actorRole == ActorRole.STYLER
                    ? ownerId.equals(appointment.getStylerId())
                    : ownerId.equals(appointment.getUserId());
            if(!ownsAppointment){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You do not have permission to update this appointment");
                response.setData(EMPTY_DATA);
                return response;
            }
            if("0".equals(newStatus) && !appointmentStartHasPassed(appointment)){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("An appointment can only be completed after its scheduled start time");
                response.setData(EMPTY_DATA);
                return response;
            }
            boolean allowed = false;
            for(String from : allowedFrom){
                if(from.equals(appointment.getStatus())){
                    allowed = true;
                    break;
                }
            }
            if(!allowed){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This appointment cannot be updated from its current state");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Completed bookings can only be cancelled by the stylist within a
            // short window of completion — after that, refunds go through admin.
            if("4".equals(newStatus) && "0".equals(appointment.getStatus())){
                LocalDateTime completedAt = appointment.getCompletedAt();
                if(completedAt == null){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("This completed appointment cannot be cancelled — completion time is unknown. Please contact support.");
                    response.setData(EMPTY_DATA);
                    return response;
                }
                if(completedAt.isBefore(LocalDateTime.now(applicationZone()).minusHours(stylerCancelWindowHours))){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Appointments can only be cancelled within " + stylerCancelWindowHours + " hours of completion");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            if("3".equals(newStatus) && stripeService.isConfigured()
                    && "PAYMENT_SCHEDULED".equals(appointment.getPaymentStatus())
                    && paymentAuthorizationService.paymentAuthorizationDueAt(appointment).isAfter(LocalDateTime.now(applicationZone()))){
                // The stylist can accept an advance booking before the payment
                // window opens. The scheduler will authorize it later.
                appointment.setPaymentStatus("PAYMENT_ACCEPTED_SCHEDULED");
                appointment.setPaymentFailureCode(null);
            } else if("3".equals(newStatus) && stripeService.isConfigured()
                    && "PAYMENT_SCHEDULED".equals(appointment.getPaymentStatus())){
                PaymentAuthorizationService.PaymentAttemptResult payment = paymentAuthorizationService.authorizeScheduledPayment(appointment, true);
                if(!payment.success){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage(payment.message);
                    response.setData(Collections.singletonMap("paymentError", payment.code));
                    return response;
                }
            }
            if("3".equals(newStatus) && appointment.getPaymentIntentId() != null && stripeService.isConfigured()
                    && "AUTHORIZED".equals(appointment.getPaymentStatus())){
                try {
                    stripeService.captureBookingPayment(appointment.getPaymentIntentId());
                    appointment.setPaymentStatus("CAPTURED");
                    paymentService.sendPaymentReceipt(appointment);
                } catch(Exception ex){
                    LOG.warning("Payment capture failed: " + ex.getMessage());
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Payment capture failed — please try again or update your payment method.");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            if("0".equals(newStatus)){
                appointment.setCompletedAt(LocalDateTime.now(applicationZone()));
            }
            appointment.setStatus(newStatus);
            if(decisionNote != null && !decisionNote.isBlank()){
                appointment.setStylerNote(AppUtils.sanitizeText(decisionNote.trim()));
            }
            bookAppointmentRepo.save(appointment);
            if("2".equals(newStatus) || "4".equals(newStatus)){
                // Rejected and customer-cancelled bookings release their held slots.
                bookingSlotLockRepo.deleteByAppointmentId(appointment.getAppointmentId());
                // A captured payment is refunded automatically; otherwise the
                // authorized hold is released so the card is never charged.
                if(appointment.getPaymentIntentId() != null && stripeService.isConfigured()){
                    if("CAPTURED".equals(appointment.getPaymentStatus())){
                        paymentService.autoRefundCapturedPayment(appointment,
                                "Appointment " + ("2".equals(newStatus) ? "rejected" : "cancelled"), "SYSTEM");
                    } else {
                        try {
                            stripeService.releaseBookingPayment(appointment.getPaymentIntentId());
                            appointment.setPaymentStatus("RELEASED");
                            bookAppointmentRepo.save(appointment);
                        } catch(Exception ex){
                            LOG.warning("Payment hold release failed: " + ex.getMessage());
                        }
                    }
                }
            }
            if("0".equals(newStatus)){
                // The stylist is paid a day from now, not on the spot, so the
                // customer has time to say the appointment didn't happen.
                payoutReleaseService.holdAfterCompletion(appointment);
                bookAppointmentRepo.save(appointment);
                loyaltyService.awardCompletionPoints(appointment.getUserId(), appointment.getAppointmentId());
            }
            auditService.audit(ownerId, actorRole == ActorRole.STYLER ? "STYLER" : "CUSTOMER", "APPOINTMENT_" + newStatus,
                    "APPOINTMENT", appointmentId, successMessage);

            // Notify both parties about the outcome.
            if("3".equals(newStatus)){
                notificationService.sendAppointmentNotification(appointment, "Confirmed",
                        "Your appointment has been confirmed",
                        "The professional has accepted your booking request. See you at your appointment!",
                        "Appointment confirmed",
                        "You have confirmed this client's booking request.");
            } else if("2".equals(newStatus)){
                notificationService.sendAppointmentNotification(appointment, "Declined",
                        "Your appointment request was declined",
                        "The professional could not take this booking. Please book another slot.",
                        "Appointment declined",
                        "You have declined this client's booking request.");
            } else if("0".equals(newStatus)){
                notificationService.sendAppointmentNotification(appointment, "Completed",
                        "Your appointment has been completed",
                        "Thanks for using RapidStylers. We hope you enjoyed your session — a review is welcome. "
                                + "If the appointment didn't happen, tell us from your bookings within a day.",
                        "Appointment completed",
                        "You have marked this appointment as completed.");
            } else if("4".equals(newStatus)){
                notificationService.sendAppointmentNotification(appointment, "Cancelled",
                        "Your appointment was cancelled",
                        "Your appointment has been cancelled. Check your dashboard for updates.",
                        "Appointment cancelled",
                        actorRole == ActorRole.STYLER
                                ? "You have cancelled this appointment."
                                : "This appointment has been cancelled by the client.");
            }

            // Booked-slot cache is display data; evict so the next profile view is fresh.
            evict(ReadCacheService.KEY_STYLER_APPOINTMENTS + appointment.getStylerId());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(successMessage);
            response.setData(EMPTY_DATA);
        }
        catch (RuntimeException ex){
            // Persistence failures must escape the transaction so status changes
            // and slot releases roll back together.
            LOG.warning(ex.getMessage());
            throw ex;
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
}

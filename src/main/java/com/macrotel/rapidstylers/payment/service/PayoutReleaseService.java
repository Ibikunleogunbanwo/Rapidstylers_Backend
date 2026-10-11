package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_MESSAGE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * When the stylist's share of a booking leaves the platform.
 *
 * <p>Marking a booking completed no longer pays the stylist on the spot. The
 * payout waits {@code app.payout.hold-hours} (a day by default). In that time
 * the customer can confirm the appointment happened, which pays the stylist
 * straight away, or say it didn't, which holds the payout until an admin looks
 * at it. When neither happens the payout leaves once the day is up.
 *
 * <p>The transfer's idempotency key is fixed per booking, so the scheduler, a
 * confirmation and an admin release racing each other still pay once.
 */
@Service
public class PayoutReleaseService {

    private static final Logger LOG = Logger.getLogger(PayoutReleaseService.class.getName());
    static final String DISPUTE_OPEN = "OPEN";
    static final String DISPUTE_RESOLVED = "RESOLVED";
    /** Hold reason while the stylist has no Stripe account that can receive payouts. */
    public static final String HOLD_NO_PAYOUT_ACCOUNT = "NO_PAYOUT_ACCOUNT";

    @Autowired
    AppointmentRecords appointmentRecords;
    @Autowired
    StylerRecords stylerRecords;
    @Autowired
    StripeService stripeService;
    @Autowired
    PaymentService paymentService;
    @Autowired
    AuditService auditService;
    @Autowired(required = false)
    EmailConfig emailConfig;

    @Value("${app.payout.hold-hours:24}")
    long holdHours = 24;
    @Value("${app.time-zone:America/Edmonton}")
    String appTimeZone = "America/Edmonton";
    @Value("${app.admin.alert-email:}")
    String adminAlertEmail = "";

    /** Starts the wait on a booking that was just completed. The caller saves it. */
    public void holdAfterCompletion(BookAppointmentEntity appointment) {
        if ("CAPTURED".equals(appointment.getPaymentStatus()) && appointment.getStripeTransferId() == null) {
            appointment.setPayoutDueAt(now().plusHours(holdHours));
        }
    }

    /** Pays every stylist whose wait is over and whose customer has not disputed. */
    @Scheduled(fixedDelayString = "${app.payout.release-interval-ms:600000}")
    public void releaseDuePayouts() {
        if (!stripeService.isConfigured()) {
            return;
        }
        try {
            for (BookAppointmentEntity appointment : appointmentRecords
                    .payoutsDueBy(now())) {
                release(appointment);
            }
        } catch (Exception ex) {
            LOG.warning("Payout release job failed: " + ex.getMessage());
        }
    }

    /**
     * Whether Stripe can pay this stylist: they have a connected account and
     * Stripe has not said it is unfinished or refused. An account whose status
     * never arrived is tried, as before.
     */
    public static boolean canReceivePayouts(StylerEntity styler) {
        if (styler == null || styler.getStripeConnectAccountId() == null
                || styler.getStripeConnectAccountId().isBlank()) {
            return false;
        }
        String status = styler.getConnectOnboardingStatus();
        return !"PENDING".equals(status) && !"REJECTED".equals(status);
    }

    /**
     * Sends the stylist their share of what the platform kept (the charge minus
     * any refunds), unless a dispute is open. Returns true when nothing is left
     * waiting: paid, or nothing to pay. A stylist who cannot receive payouts yet
     * keeps the payout, marked NO_PAYOUT_ACCOUNT, until their setup is done.
     */
    boolean release(BookAppointmentEntity appointment) {
        if (DISPUTE_OPEN.equals(appointment.getServiceDisputeStatus())) {
            return false;
        }
        try {
            Optional<StylerEntity> styler = stylerRecords.findByStylerId(appointment.getStylerId());
            String destination = styler.map(StylerEntity::getStripeConnectAccountId).orElse(null);
            long keptCents = paymentService.refundableCents(appointment);
            double percent = paymentService.effectiveCommissionPercentForBooking(appointment);
            // The platform pays for any promo discount: the stylist's share comes
            // from the full price, and the platform's fee is what is left of what it kept.
            long baseCents = PromotionService.stylistBaseCents(appointment.getPromoDiscountCents(),
                    keptCents, paymentService.capturedCents(appointment));
            long stylistShareCents = baseCents - paymentService.commissionCents(baseCents, percent);
            long commission = keptCents - stylistShareCents;
            if (appointment.getCommissionPercent() == null) {
                appointment.setCommissionPercent(percent);
            }
            appointment.setPlatformFeeCents(commission);
            appointment.setStylistShareCents(stylistShareCents);
            if (stylistShareCents > 0 && !canReceivePayouts(styler.orElse(null))) {
                holdForPayoutAccount(appointment, styler.orElse(null));
                return false;
            }
            if (stylistShareCents > 0) {
                com.stripe.model.Transfer transfer = stripeService.transferStylistShare(destination, stylistShareCents,
                        appointment.getAppointmentId(),
                        "payout_" + appointment.getAppointmentId() + "_" + stylistShareCents);
                if (transfer != null) {
                    appointment.setStripeTransferId(transfer.getId());
                }
            }
            appointment.setPayoutDueAt(null);
            appointment.setPayoutHoldReason(null);
            appointmentRecords.save(appointment);
            return true;
        } catch (Exception ex) {
            // Left due, so the next run tries again with the same idempotency key.
            LOG.warning("Stylist payout for " + appointment.getAppointmentId() + " failed: " + ex.getMessage());
            return false;
        }
    }

    /** The customer says the appointment happened, so the stylist is paid now. */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse confirmServiceHappened(String userId, String appointmentId) {
        BaseResponse response = new BaseResponse(true);
        Optional<BookAppointmentEntity> found = customersCompletedBooking(userId, appointmentId);
        if (found.isEmpty()) {
            return Responses.errorResponse(response, "Invalid Appointment Id");
        }
        BookAppointmentEntity appointment = found.get();
        if (DISPUTE_OPEN.equals(appointment.getServiceDisputeStatus())) {
            return Responses.errorResponse(response, "You reported a problem with this appointment. Our team will be in touch.");
        }
        if (appointment.getServiceConfirmedAt() == null) {
            appointment.setServiceConfirmedAt(now());
            auditService.audit(userId, "CUSTOMER", "SERVICE_CONFIRMED", "APPOINTMENT", appointmentId,
                    "Customer confirmed the appointment happened");
        }
        if (appointment.getPayoutDueAt() != null) {
            // Due now: paid here, or by the next run if this attempt fails.
            appointment.setPayoutDueAt(now());
            if (stripeService.isConfigured()) {
                release(appointment);
            }
        }
        appointmentRecords.save(appointment);
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage("Thanks for confirming");
        response.setData(java.util.Collections.emptyList());
        return response;
    }

    /**
     * The customer says the appointment didn't happen as booked. Allowed while
     * the stylist's payout is still waiting; holds it until an admin resolves it.
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse disputeService(String userId, String appointmentId, String reason) {
        BaseResponse response = new BaseResponse(true);
        Optional<BookAppointmentEntity> found = customersCompletedBooking(userId, appointmentId);
        if (found.isEmpty()) {
            return Responses.errorResponse(response, "Invalid Appointment Id");
        }
        BookAppointmentEntity appointment = found.get();
        if (DISPUTE_OPEN.equals(appointment.getServiceDisputeStatus())) {
            return Responses.errorResponse(response, "You already reported a problem with this appointment");
        }
        if (appointment.getServiceConfirmedAt() != null || appointment.getPayoutDueAt() == null
                || appointment.getStripeTransferId() != null) {
            return Responses.errorResponse(response,
                    "This appointment can no longer be disputed here. Please contact support.");
        }
        String why = reason == null ? "" : AppUtils.sanitizeText(reason.trim());
        if (why.isBlank()) {
            return Responses.errorResponse(response, "Tell us what went wrong");
        }
        appointment.setServiceDisputeStatus(DISPUTE_OPEN);
        appointment.setServiceDisputeReason(why.length() > 1000 ? why.substring(0, 1000) : why);
        appointment.setServiceDisputedAt(now());
        appointment.setPayoutDueAt(null); // held: only resolving the dispute pays the stylist
        appointmentRecords.save(appointment);
        auditService.audit(userId, "CUSTOMER", "SERVICE_DISPUTED", "APPOINTMENT", appointmentId,
                "Customer disputed the appointment: " + appointment.getServiceDisputeReason());
        alertAdmin("Appointment " + appointmentId + " was disputed by the customer: "
                + appointment.getServiceDisputeReason() + ". The stylist payout is on hold until it is resolved.");
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage("Thanks for telling us. The professional won't be paid until our team has looked into it.");
        response.setData(java.util.Collections.emptyList());
        return response;
    }

    /** Every booking whose customer has an open dispute, oldest first. */
    public BaseResponse openDisputes() {
        BaseResponse response = new BaseResponse(true);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BookAppointmentEntity appointment : appointmentRecords.findByServiceDisputeStatus(DISPUTE_OPEN)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("appointmentId", appointment.getAppointmentId());
            row.put("userId", appointment.getUserId());
            row.put("stylerId", appointment.getStylerId());
            row.put("appointmentDate", appointment.getAppointmentDate());
            row.put("arrivalTime", appointment.getArrivalTime());
            row.put("completedAt", text(appointment.getCompletedAt()));
            row.put("disputedAt", text(appointment.getServiceDisputedAt()));
            row.put("reason", appointment.getServiceDisputeReason());
            row.put("paymentStatus", appointment.getPaymentStatus());
            rows.add(row);
        }
        rows.sort(java.util.Comparator.comparing(r -> String.valueOf(r.get("disputedAt"))));
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(rows);
        return response;
    }

    /**
     * Closes a dispute. Refund the customer first if they are owed one (in full
     * or in part); closing then pays the stylist their share of whatever the
     * platform kept, which is nothing after a full refund.
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse resolveDispute(String adminId, String appointmentId, String note) {
        BaseResponse response = new BaseResponse(true);
        Optional<BookAppointmentEntity> found = appointmentId == null ? Optional.empty()
                : appointmentRecords.findByAppointmentIdForUpdate(appointmentId.trim());
        if (found.isEmpty()) {
            return Responses.errorResponse(response, "Invalid Appointment Id");
        }
        BookAppointmentEntity appointment = found.get();
        if (!DISPUTE_OPEN.equals(appointment.getServiceDisputeStatus())) {
            return Responses.errorResponse(response, "This appointment has no open dispute");
        }
        String cleanNote = note == null || note.isBlank() ? null : AppUtils.sanitizeText(note.trim());
        if (cleanNote != null && cleanNote.length() > 1000) cleanNote = cleanNote.substring(0, 1000);
        appointment.setServiceDisputeStatus(DISPUTE_RESOLVED);
        appointment.setServiceDisputeResolutionNote(cleanNote);
        appointment.setServiceDisputeResolvedAt(now());
        appointment.setPayoutDueAt(now());
        auditService.audit(adminId, "ADMIN", "SERVICE_DISPUTE_RESOLVED", "APPOINTMENT", appointmentId,
                "Dispute resolved" + (cleanNote == null ? "" : ": " + cleanNote));
        boolean paid = "CAPTURED".equals(appointment.getPaymentStatus()) && appointment.getStripeTransferId() == null
                && stripeService.isConfigured() && release(appointment);
        if (!paid) {
            if (!"CAPTURED".equals(appointment.getPaymentStatus())) {
                appointment.setPayoutDueAt(null); // fully refunded: nothing left to pay
            }
            appointmentRecords.save(appointment);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appointmentId", appointment.getAppointmentId());
        data.put("stylistShare", appointment.getStylistShareCents() == null ? null
                : MoneyUtils.moneyCents(appointment.getStylistShareCents()));
        data.put("stripeTransferId", appointment.getStripeTransferId());
        data.put("resolutionNote", appointment.getServiceDisputeResolutionNote());
        data.put("resolvedAt", appointment.getServiceDisputeResolvedAt());
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage("Dispute resolved");
        response.setData(data);
        return response;
    }

    /**
     * Keeps the payout until the stylist can receive it. The stylist is told the
     * first time, not once per booking; the account.updated webhook queues every
     * held payout again once Stripe says payouts are enabled.
     */
    private void holdForPayoutAccount(BookAppointmentEntity appointment, StylerEntity styler) {
        boolean firstHeld = !appointmentRecords.existsByStylerIdAndPayoutHoldReason(
                appointment.getStylerId(), HOLD_NO_PAYOUT_ACCOUNT);
        appointment.setPayoutHoldReason(HOLD_NO_PAYOUT_ACCOUNT);
        appointment.setPayoutDueAt(null);
        appointmentRecords.save(appointment);
        auditService.audit("system", "SYSTEM", "PAYOUT_HELD", "APPOINTMENT", appointment.getAppointmentId(),
                "Stylist has no payout account that can receive payouts yet");
        if (firstHeld && styler != null) {
            askForPayoutSetup(styler);
        }
    }

    private void askForPayoutSetup(StylerEntity styler) {
        if (emailConfig == null || styler.getEmailAddress() == null || styler.getEmailAddress().isBlank()) {
            return;
        }
        try {
            emailConfig.sendSimpleMail(styler.getEmailAddress(), "RapidStylers - You have earnings waiting",
                    "<p>You have earnings from completed appointments waiting for you.</p>"
                            + "<p>Finish your payout setup from the Payouts page of your dashboard and we will "
                            + "send them to you straight away.</p><p>Thank you,<br>The RapidStylers Team</p>");
        } catch (Exception ex) {
            LOG.warning("Payout setup email failed: " + ex.getMessage());
        }
    }

    /**
     * Completed, charged bookings whose stylist has not been paid and whose
     * payout is not scheduled: held for a payout account, or older bookings
     * from before payouts waited a day. Open disputes are listed separately.
     */
    public BaseResponse unpaidPayouts() {
        BaseResponse response = new BaseResponse(true);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BookAppointmentEntity appointment : appointmentRecords
                .paidWithNoPayoutScheduled()) {
            if (DISPUTE_OPEN.equals(appointment.getServiceDisputeStatus())) {
                continue;
            }
            Optional<StylerEntity> styler = stylerRecords.findByStylerId(appointment.getStylerId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("appointmentId", appointment.getAppointmentId());
            row.put("stylerId", appointment.getStylerId());
            row.put("businessName", styler.map(StylerEntity::getBusinessName).orElse(null));
            row.put("completedAt", text(appointment.getCompletedAt()));
            row.put("stylistShare", appointment.getStylistShareCents() == null ? null
                    : MoneyUtils.moneyCents(appointment.getStylistShareCents()));
            row.put("reason", appointment.getPayoutHoldReason() == null ? "NOT_SCHEDULED" : appointment.getPayoutHoldReason());
            row.put("connectStatus", styler.map(StylerEntity::getConnectOnboardingStatus).orElse(null));
            row.put("canReceivePayouts", canReceivePayouts(styler.orElse(null)));
            rows.add(row);
        }
        rows.sort(java.util.Comparator.comparing(r -> String.valueOf(r.get("completedAt"))));
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(rows);
        return response;
    }

    /**
     * An admin sends an unpaid payout now: after checking that an older booking
     * was never paid outside the app, or once a stylist's account is fixed.
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse payNow(String adminId, String appointmentId) {
        BaseResponse response = new BaseResponse(true);
        Optional<BookAppointmentEntity> found = appointmentId == null ? Optional.empty()
                : appointmentRecords.findByAppointmentIdForUpdate(appointmentId.trim());
        if (found.isEmpty()) {
            return Responses.errorResponse(response, "Invalid Appointment Id");
        }
        BookAppointmentEntity appointment = found.get();
        if (!"0".equals(appointment.getStatus()) || !"CAPTURED".equals(appointment.getPaymentStatus())
                || appointment.getStripeTransferId() != null) {
            return Responses.errorResponse(response, "This booking has no unpaid stylist payout");
        }
        if (DISPUTE_OPEN.equals(appointment.getServiceDisputeStatus())) {
            return Responses.errorResponse(response, "Resolve the customer's dispute first");
        }
        if (!stripeService.isConfigured()) {
            return Responses.errorResponse(response, "Payments are not configured");
        }
        auditService.audit(adminId, "ADMIN", "PAYOUT_RELEASED_BY_ADMIN", "APPOINTMENT", appointmentId, "Admin sent the stylist payout");
        appointment.setPayoutDueAt(now());
        boolean paid = release(appointment);
        if (!paid && appointment.getPayoutDueAt() != null) {
            appointmentRecords.save(appointment); // failed: left due for the next run
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appointmentId", appointment.getAppointmentId());
        data.put("stylistShare", appointment.getStylistShareCents() == null ? null
                : MoneyUtils.moneyCents(appointment.getStylistShareCents()));
        data.put("stripeTransferId", appointment.getStripeTransferId());
        data.put("payoutHoldReason", appointment.getPayoutHoldReason());
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(paid ? "Payout sent"
                : HOLD_NO_PAYOUT_ACCOUNT.equals(appointment.getPayoutHoldReason())
                        ? "The stylist has no payout account yet; it will be sent once they finish payout setup"
                        : "The payout could not be sent now; it will be retried");
        response.setData(data);
        return response;
    }

    /** When the customer can no longer dispute: the payout's due time, while it waits. */
    public static LocalDateTime disputeWindowEndsAt(BookAppointmentEntity appointment) {
        return appointment.getServiceConfirmedAt() == null && appointment.getStripeTransferId() == null
                && appointment.getServiceDisputeStatus() == null ? appointment.getPayoutDueAt() : null;
    }

    private Optional<BookAppointmentEntity> customersCompletedBooking(String userId, String appointmentId) {
        if (userId == null || appointmentId == null || appointmentId.isBlank()) {
            return Optional.empty();
        }
        return appointmentRecords.findByAppointmentIdForUpdate(appointmentId.trim())
                .filter(a -> userId.equals(a.getUserId()) && "0".equals(a.getStatus()));
    }

    private void alertAdmin(String message) {
        if (emailConfig == null || adminAlertEmail == null || adminAlertEmail.isBlank()) {
            LOG.warning(message);
            return;
        }
        try {
            emailConfig.sendSimpleMail(adminAlertEmail, "RapidStylers - Appointment disputed", "<p>" + message + "</p>");
        } catch (Exception ex) {
            LOG.warning("Dispute alert email failed: " + ex.getMessage());
        }
    }

    private LocalDateTime now() {
        ZoneId zone;
        try {
            zone = ZoneId.of(appTimeZone == null || appTimeZone.isBlank() ? "America/Edmonton" : appTimeZone);
        } catch (Exception ex) {
            zone = ZoneId.of("America/Edmonton");
        }
        return LocalDateTime.now(zone);
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}

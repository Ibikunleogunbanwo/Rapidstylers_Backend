package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.payment.entity.RefundEntity;
import com.macrotel.rapidstylers.payment.pojo.RefundRequestData;
import com.macrotel.rapidstylers.payment.repo.CardDetailsRepo;
import com.macrotel.rapidstylers.payment.repo.PlatformSettingRepo;
import com.macrotel.rapidstylers.payment.repo.RefundRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import com.macrotel.rapidstylers.styler.service.VendorZoneResolver;
import com.stripe.model.Account;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Payment operations: Stripe webhooks, payment receipts, automatic and admin
 * refunds, stylist payout summaries, Connect onboarding status transitions, and
 * the platform-commission source of truth.
 *
 * Booking-side payment authorization (near-term authorize, scheduled
 * authorization, capture on acceptance) lives in BookingService with the
 * booking workflow; it hands its payment side effects (receipts, auto-refunds)
 * to this service.
 */
@Service
public class PaymentService {

    private static final Logger LOG = Logger.getLogger(PaymentService.class.getName());

    private final AppUtils appUtils = new AppUtils();

    @Autowired
    AppointmentRecords appointmentRecords;
    @Autowired
    CustomerRecords customerRecords;
    @Autowired
    StylerRecords stylerRecords;
    @Autowired
    CardDetailsRepo cardDetailsRepo;
    @Autowired
    RefundRepo refundRepo;
    @Autowired
    PlatformSettingRepo platformSettingRepo;
    @Autowired
    StripeService stripeService;
    @Autowired
    EmailConfig emailConfig;
    @Autowired(required = false)
    OutboxEventService outboxEventService;
    @Autowired(required = false)
    PayoutReversalService payoutReversalService;
    @Autowired
    AuditService auditService;

    @Value("${app.stripe.commission-percent:12}")
    private double stripeCommissionPercent;

    // Runtime commission (admin-configurable). null = use the @Value default.
    private volatile Double cachedCommissionPercent;

    @PostConstruct
    void loadCommissionSetting() {
        try {
            if (platformSettingRepo == null) return;
            platformSettingRepo.findBySettingKey(COMMISSION_SETTING_KEY).ifPresent(setting -> {
                try {
                    cachedCommissionPercent = Double.parseDouble(setting.getSettingValue());
                } catch (NumberFormatException ignored) {
                }
            });
        } catch (Exception ex) {
            LOG.warning("Commission setting load failed: " + ex.getMessage());
        }
    }

    /** Effective commission percent: admin setting when present, else the .env default. */
    public double effectiveCommissionPercent() {
        Double cached = cachedCommissionPercent;
        return cached != null ? cached : stripeCommissionPercent;
    }

    /** Platform commission in minor units, based on the effective commission percent. */
    public long commissionCents(long amountCents) {
        return commissionCents(amountCents, effectiveCommissionPercent());
    }

    /** Platform commission in minor units at an explicit percent. */
    public long commissionCents(long amountCents, double percent) {
        if (percent <= 0 || amountCents <= 0) return 0L;
        return Math.round(amountCents * percent / 100.0);
    }

    /**
     * Commission percent for a booking: the rate snapped at booking creation when
     * present, else the current effective rate. Payouts must use this so a later
     * admin commission change never retroactively rewrites a completed booking.
     */
    public double effectiveCommissionPercentForBooking(BookAppointmentEntity appointment) {
        if (appointment != null && appointment.getCommissionPercent() != null) {
            return appointment.getCommissionPercent();
        }
        return effectiveCommissionPercent();
    }

    /** Called by the admin commission write path so the cache follows the DB immediately. */
    public void updateCachedCommissionPercent(double percent) {
        this.cachedCommissionPercent = percent;
    }

    public BaseResponse getCommissionSetting(String adminId) {
        BaseResponse response = new BaseResponse(true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commissionPercent", effectiveCommissionPercent());
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return response;
    }

    public BaseResponse updateCommissionSetting(String adminId, double percent) {
        BaseResponse response = new BaseResponse(true);
        try {
            if (percent < 0 || percent > 100) {
                return errorResponse(response, "Commission must be between 0 and 100");
            }
            com.macrotel.rapidstylers.payment.entity.PlatformSettingEntity setting =
                    platformSettingRepo.findBySettingKey(COMMISSION_SETTING_KEY)
                            .orElseGet(() -> new com.macrotel.rapidstylers.payment.entity.PlatformSettingEntity(COMMISSION_SETTING_KEY, "12"));
            setting.setSettingValue(String.valueOf(percent));
            platformSettingRepo.save(setting);
            updateCachedCommissionPercent(percent);
            auditService.audit(adminId, "ADMIN", "UPDATE_COMMISSION", "SETTINGS", COMMISSION_SETTING_KEY, String.valueOf(percent));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Commission updated");
            response.setData(EMPTY_DATA);
        } catch (Exception ex) {
            LOG.warning("Commission update failed: " + ex.getMessage());
            return errorResponse(response, "Could not update commission");
        }
        return response;
    }

    /**
     * Sends payment receipts to the customer and stylist when a PaymentIntent
     * is captured. Goes through the outbox (Kafka -> NotificationEventConsumer)
     * when available, falling back to a direct email for tests.
     */
    public void sendPaymentReceipt(BookAppointmentEntity appointment) {
        try {
            if (outboxEventService != null) {
                outboxEventService.paymentSucceeded(appointment);
                return;
            }
            String serviceName = "Service";
            if (appointment.getSubServiceId() != null && !appointment.getSubServiceId().isEmpty()) {
                try {
                    Optional<SubServiceEntity> sub = stylerRecords.findSubService(appointment.getStylerId(), Long.parseLong(appointment.getSubServiceId()));
                    if (sub.isPresent() && sub.get().getName() != null) {
                        serviceName = sub.get().getName();
                    }
                } catch (Exception ignored) {
                }
            }
            Optional<StylerEntity> stylerOpt = stylerRecords.findByStylerId(appointment.getStylerId());
            // The receipt repeats the appointment's time, which is written on
            // the stylist's clock, so it names that zone too.
            String zoneSuffix = VendorZoneResolver.timeSuffixForStyler(
                    stylerOpt.map(StylerEntity::getTimeZone).orElse(null),
                    stylerOpt.map(StylerEntity::getProvince).orElse(null));
            String when = (appointment.getAppointmentDate() == null ? "" : appointment.getAppointmentDate())
                    + (appointment.getArrivalTime() == null || appointment.getArrivalTime().isBlank()
                            ? "" : " at " + appointment.getArrivalTime() + zoneSuffix);
            String paid = appointment.getPaymentAmount() == null
                    ? (appointment.getPrice() == null ? "—" : appointment.getPrice()) : appointment.getPaymentAmount();
            String details = "<p>Service: " + serviceName + "<br>"
                    + "Date: " + when + "<br>"
                    + "Total paid: $" + paid + "<br>"
                    + "Appointment ref: " + appointment.getAppointmentId() + "</p>"
                    + "<p>Thank you,<br>The RapidStylers Team</p>";
            String subject = "RapidStylers — Payment receipt";

            Optional<UserEntity> userOpt = customerRecords.findByUserId(appointment.getUserId());
            String customerEmail = userOpt.map(UserEntity::getEmailAddress).orElse(null);
            if (customerEmail != null && !customerEmail.isBlank()) {
                String name = userOpt.map(u -> (u.getFirstname() + " " + u.getLastname()).trim()).orElse("there");
                emailConfig.sendSimpleMail(customerEmail, subject,
                        "<p>Dear " + (name.isBlank() ? "there" : name) + ",</p>"
                                + "<p><strong>Payment received</strong> — thank you for your business.</p>" + details);
            }
            String stylerEmail = stylerOpt.map(StylerEntity::getEmailAddress).orElse(null);
            if (stylerEmail != null && !stylerEmail.isBlank()) {
                String name = stylerOpt.map(s -> (s.getFirstname() + " " + s.getLastname()).trim()).orElse("Stylist");
                if (name.isBlank()) name = stylerOpt.map(StylerEntity::getBusinessName).orElse("Stylist");
                emailConfig.sendSimpleMail(stylerEmail, subject,
                        "<p>Dear " + name + ",</p>"
                                + "<p><strong>Payment received</strong> — the client's payment has been received.</p>" + details);
            }
        } catch (Exception ex) {
            LOG.warning("Payment receipt mail failed: " + ex.getMessage());
        }
    }

    /**
     * Handles signature-verified Stripe webhook events. Capture/release are
     * normally done synchronously in the appointment transitions; this covers
     * async outcomes (e.g. delayed card actions) so payment state stays true.
     *
     * <p>Every delivery leaves exactly one line in the log — what arrived, and
     * what it did. A payment that matched no booking used to vanish silently,
     * and a handling failure answered Stripe with a 500 that appeared nowhere in
     * the app's own logs; both are now visible without the Stripe dashboard.
     */
    public void handleStripeWebhook(String payload, String signatureHeader) {
        Event event;
        try {
            event = stripeService.verifyWebhookEvent(payload, signatureHeader);
        } catch (IllegalArgumentException ex) {
            // A delivery whose signature does not verify carries no trustworthy
            // event id, so the rejection is described on its own terms. The
            // controller still answers the same 400 it always has.
            LOG.warning("Stripe webhook rejected: reason=" + failureReason(ex));
            throw ex;
        }
        try {
            Delivery delivery = applyEvent(event);
            LOG.info("Stripe webhook " + delivery.outcome() + ": id=" + eventId(event)
                    + " type=" + eventType(event) + " detail=" + delivery.detail());
        } catch (Exception ex) {
            // Rethrow so the controller keeps its 500 contract and Stripe
            // retries, but never without saying why.
            LOG.severe("Stripe webhook failed: id=" + eventId(event) + " type=" + eventType(event)
                    + " reason=" + failureReason(ex));
            throw ex;
        }
    }

    /**
     * Applies one verified event and reports what it did: the outcome word plus
     * a short detail naming the row it touched, so a log reader can tell a real
     * update from an event that matched nothing.
     */
    private Delivery applyEvent(Event event) {
        String type = event.getType();
        if ("payment_intent.succeeded".equals(type)) {
            PaymentIntent intent = (PaymentIntent) event.getData().getObject();
            Optional<BookAppointmentEntity> booking = appointmentRecords.findByPaymentIntentId(intent.getId());
            if (booking.isEmpty()) return unmatchedIntent(intent.getId());
            BookAppointmentEntity a = booking.get();
            // Capture is normally persisted synchronously; this webhook keeps
            // state correct when Stripe completes it asynchronously.
            boolean alreadyCaptured = "CAPTURED".equals(a.getPaymentStatus());
            a.setPaymentStatus("CAPTURED");
            a.setPaymentFailureCode(null);
            appointmentRecords.save(a);
            if (!alreadyCaptured) {
                sendPaymentReceipt(a);
            }
            auditService.audit("system", "SYSTEM", "PAYMENT_CAPTURED", "APPOINTMENT", a.getAppointmentId(), "Payment captured");
            return new Delivery("processed", "appointment=" + a.getAppointmentId()
                    + (alreadyCaptured ? " capture=already-recorded" : " capture=recorded"));
        }
        if ("payment_intent.payment_failed".equals(type)) {
            PaymentIntent intent = (PaymentIntent) event.getData().getObject();
            Optional<BookAppointmentEntity> booking = appointmentRecords.findByPaymentIntentId(intent.getId());
            if (booking.isEmpty()) return unmatchedIntent(intent.getId());
            BookAppointmentEntity a = booking.get();
            a.setPaymentStatus("PAYMENT_FAILED");
            a.setPaymentFailureCode("PAYMENT_FAILED");
            appointmentRecords.save(a);
            auditService.audit("system", "SYSTEM", "PAYMENT_FAILED", "APPOINTMENT", a.getAppointmentId(), "Payment failed");
            return new Delivery("processed", "appointment=" + a.getAppointmentId() + " paymentStatus=PAYMENT_FAILED");
        }
        if ("payment_intent.canceled".equals(type)) {
            PaymentIntent intent = (PaymentIntent) event.getData().getObject();
            Optional<BookAppointmentEntity> booking = appointmentRecords.findByPaymentIntentId(intent.getId());
            if (booking.isEmpty()) return unmatchedIntent(intent.getId());
            BookAppointmentEntity a = booking.get();
            a.setPaymentStatus("RELEASED");
            appointmentRecords.save(a);
            auditService.audit("system", "SYSTEM", "PAYMENT_RELEASED", "APPOINTMENT", a.getAppointmentId(), "Payment hold released");
            return new Delivery("processed", "appointment=" + a.getAppointmentId() + " paymentStatus=RELEASED");
        }
        if ("account.updated".equals(type)) {
            return new Delivery("processed", handleAccountUpdated((Account) event.getData().getObject()));
        }
        if ("charge.dispute.created".equals(type)) {
            return new Delivery("processed", handleDispute((com.stripe.model.Dispute) event.getData().getObject(), true));
        }
        if ("charge.dispute.closed".equals(type)) {
            return new Delivery("processed", handleDispute((com.stripe.model.Dispute) event.getData().getObject(), false));
        }
        // Subscribed-but-unhandled types are normal (Stripe sends more than this
        // app acts on), so they are reported, not treated as failures.
        return new Delivery("ignored", "no handler for this event type");
    }

    /** A payment event whose intent belongs to no booking: worth a line of its own. */
    private Delivery unmatchedIntent(String paymentIntentId) {
        return new Delivery("no-match", "no booking for payment intent " + paymentIntentId);
    }

    /** What one delivery did, for the log. */
    private record Delivery(String outcome, String detail) {}

    private static String eventId(Event event) {
        String id = event == null ? null : event.getId();
        return id == null || id.isBlank() ? "unknown" : id;
    }

    private static String eventType(Event event) {
        String type = event == null ? null : event.getType();
        return type == null || type.isBlank() ? "unknown" : type;
    }

    private static String failureReason(Exception ex) {
        if (ex == null) return "unknown";
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Records dispute lifecycle on the booking: an opened dispute flags the
     * payment and alerts ops; a closed dispute resolves it (won -> CAPTURED,
     * lost -> DISPUTE_LOST). Returns a short account of what it did.
     *
     * <p>Deliberately no longer swallows its own failures: a dispute that could
     * not be applied left the payment state wrong, told Stripe 200, and left no
     * trace, so Stripe never retried it. Exceptions now reach the caller, which
     * logs the reason and answers 500 so the delivery is retried.
     */
    private String handleDispute(com.stripe.model.Dispute dispute, boolean opened) {
        String paymentIntentId = dispute == null ? null : dispute.getPaymentIntent();
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return "dispute " + (dispute == null ? "unknown" : dispute.getId()) + " carries no payment intent";
        }
        Optional<BookAppointmentEntity> booking = appointmentRecords.findByPaymentIntentId(paymentIntentId);
        if (booking.isEmpty()) {
            return "no booking for payment intent " + paymentIntentId;
        }
        BookAppointmentEntity a = booking.get();
        if (opened) {
            a.setPaymentStatus("DISPUTED");
            appointmentRecords.save(a);
            auditService.audit("system", "SYSTEM", "PAYMENT_DISPUTE_OPENED", "APPOINTMENT", a.getAppointmentId(),
                    "Chargeback opened (dispute " + dispute.getId() + ")");
            auditService.alertAdmin("Payment dispute opened for appointment " + a.getAppointmentId()
                    + " (dispute " + dispute.getId() + ")");
            return "appointment=" + a.getAppointmentId() + " dispute=" + dispute.getId() + " opened";
        }
        boolean lost = "lost".equalsIgnoreCase(dispute.getStatus());
        a.setPaymentStatus(lost ? "DISPUTE_LOST" : "CAPTURED");
        appointmentRecords.save(a);
        auditService.audit("system", "SYSTEM", "PAYMENT_DISPUTE_CLOSED", "APPOINTMENT", a.getAppointmentId(),
                "Dispute closed (" + dispute.getStatus() + ") for dispute " + dispute.getId());
        return "appointment=" + a.getAppointmentId() + " dispute=" + dispute.getId()
                + " closed as " + dispute.getStatus();
    }

    /**
     * Applies the account.updated webhook: updates the styler's Connect
     * onboarding status and emails them when onboarding completes or is
     * rejected (only on actual transitions, so retries never re-send).
     */
    String handleAccountUpdated(Account account) {
        String status;
        String disabledReason = null;
        if (Boolean.TRUE.equals(account.getDetailsSubmitted())) {
            if (Boolean.TRUE.equals(account.getPayoutsEnabled())) {
                status = "COMPLETE";
            } else if (account.getRequirements() != null && account.getRequirements().getDisabledReason() != null) {
                status = "REJECTED";
                disabledReason = account.getRequirements().getDisabledReason();
            } else {
                status = "PENDING";
            }
        } else {
            status = "PENDING";
        }
        Optional<StylerEntity> styler = stylerRecords.findByStripeConnectAccountId(account.getId());
        if (styler.isEmpty()) {
            return "no styler is linked to Connect account " + account.getId();
        }
        StylerEntity s = styler.get();
        String previous = s.getConnectOnboardingStatus();
        s.setConnectOnboardingStatus(status);
        // Persist the rejection reason so the Payouts page can show it; clear it
        // once the account is verified or still in progress.
        s.setConnectDisabledReason("REJECTED".equals(status) ? disabledReason : null);
        stylerRecords.save(s);
        auditService.audit("system", "SYSTEM", "CONNECT_ACCOUNT_UPDATED", "STYLER", s.getStylerId(), status);
        int queued = 0;
        if (PayoutReleaseService.canReceivePayouts(s)) {
            queued = queueHeldPayouts(s.getStylerId());
        }
        if ("COMPLETE".equals(status) && !"COMPLETE".equals(previous)) {
            sendConnectStatusEmail(s, "RapidStylers - Payouts are ready",
                    "Your payout account is connected",
                    "Your Stripe account is connected and payouts are enabled. Your share of "
                            + "completed appointments will be paid on Stripe's regular payout schedule.");
        } else if ("REJECTED".equals(status) && !"REJECTED".equals(previous)) {
            String reason = disabledReason == null ? "" : " (" + disabledReason.replace('_', ' ') + ")";
            sendConnectStatusEmail(s, "RapidStylers - Payout setup needs attention",
                    "Your payout account could not be verified",
                    "Stripe could not verify your payout account" + reason
                            + ". Please reconnect from your dashboard or contact support.");
        }
        return "styler=" + s.getStylerId() + " status=" + status
                + ("REJECTED".equals(status) && disabledReason != null ? " reason=" + disabledReason : "")
                + (queued > 0 ? " heldPayoutsQueued=" + queued : "");
    }

    /**
     * Payouts kept back because the stylist had no payout account become due
     * now; the payout job sends them. Safe to repeat: once queued they are no
     * longer marked held.
     */
    int queueHeldPayouts(String stylerId) {
        List<BookAppointmentEntity> held = appointmentRecords.findByStylerIdAndPayoutHoldReason(
                stylerId, PayoutReleaseService.HOLD_NO_PAYOUT_ACCOUNT);
        // A day back, so it is already due whatever the app's time zone.
        LocalDateTime alreadyDue = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1);
        for (BookAppointmentEntity appointment : held) {
            appointment.setPayoutHoldReason(null);
            appointment.setPayoutDueAt(alreadyDue);
            appointmentRecords.save(appointment);
        }
        return held.size();
    }

    private void sendConnectStatusEmail(StylerEntity styler, String subject, String headline, String detail) {
        try {
            if (styler.getEmailAddress() == null || styler.getEmailAddress().isBlank()) return;
            String name = (styler.getFirstname() + " " + styler.getLastname()).trim();
            if (name.isBlank()) name = styler.getBusinessName() == null ? "Stylist" : styler.getBusinessName();
            emailConfig.sendSimpleMail(styler.getEmailAddress(), subject,
                    "<p>Dear " + name + ",</p><p><strong>" + headline + "</strong></p>"
                            + "<p>" + detail + "</p><p>Thank you,<br>The RapidStylers Team</p>");
        } catch (Exception ex) {
            LOG.warning("Connect status email failed: " + ex.getMessage());
        }
    }

    /**
     * Refunds whatever is left of a captured payment: all of it, or the rest
     * after an earlier partial refund. Used by automatic paths (reject / cancel
     * after capture). Idempotent: once the whole payment is back with the
     * customer there is nothing left, so nothing is refunded again.
     */
    public void autoRefundCapturedPayment(BookAppointmentEntity appointment, String reason, String actorId) {
        RefundEntity refund = null;
        try {
            long totalCents = refundableCents(appointment);
            if (totalCents <= 0) {
                return;
            }
            String refundId = "RFND-" + appUtils.randomAlphanumeric(8).toUpperCase(Locale.ROOT);
            refund = new RefundEntity();
            refund.setRefundId(refundId);
            refund.setAppointmentId(appointment.getAppointmentId());
            refund.setPaymentIntentId(appointment.getPaymentIntentId());
            refund.setAmount(String.format(Locale.ROOT, "%.2f", totalCents / 100.0));
            refund.setReason(reason);
            refund.setStatus("REQUESTED");
            refund.setCreatedBy(actorId == null || actorId.isBlank() ? "SYSTEM" : actorId);
            refund.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            refundRepo.save(refund);
            com.stripe.model.Refund stripeRefund = stripeService.refundBookingPayment(
                    appointment.getPaymentIntentId(), totalCents, reason, RefundSafety.idempotencyKey(refund));
            refund.setStripeRefundId(stripeRefund.getId());
            refund.setStatus("COMPLETED");
            refund.setCompletedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            appointment.setPaymentStatus("REFUNDED");
            appointmentRecords.save(appointment);
            refundRepo.save(refund);
            auditService.audit(actorId, "SYSTEM", "PAYMENT_REFUND_AUTO", "APPOINTMENT", appointment.getAppointmentId(),
                    "Automatic refund of $" + refund.getAmount() + " — " + reason);
            // A completed-then-cancelled booking means the stylist payout transfer
            // was already created. Recover it automatically: the reversal is
            // attempted now and retried by a scheduled job when the stylist's
            // balance cannot cover it yet.
            if (appointment.getStripeTransferId() != null && !appointment.getStripeTransferId().isBlank()) {
                if (payoutReversalService != null) {
                    long shareCents = stylistShareOfRefund(appointment, totalCents);
                    payoutReversalService.requestReversal(appointment.getAppointmentId(),
                            appointment.getStripeTransferId(),
                            String.format(Locale.ROOT, "%.2f", shareCents / 100.0),
                            "Appointment cancelled after completion (refund " + refund.getRefundId() + ")");
                } else {
                    auditService.audit(actorId, "SYSTEM", "PAYOUT_REVERSAL_REQUIRED", "APPOINTMENT", appointment.getAppointmentId(),
                            "Refunded after completion — stylist payout " + appointment.getStripeTransferId()
                                    + " needs recovery");
                }
            }
            if (outboxEventService != null) {
                outboxEventService.refundEvent(appointment, refund.getAmount(), reason, true);
            }
        } catch (Exception ex) {
            LOG.warning("Automatic refund failed: " + ex.getMessage());
            if (refund != null && "REQUESTED".equals(refund.getStatus())) {
                // An admin pressing Refund on this booking finishes an unconfirmed one.
                refund.setStatus(RefundSafety.definitelyNotRefunded(ex) ? "FAILED" : RefundSafety.UNCONFIRMED);
                refund.setFailureCode(String.valueOf(ex.getMessage()));
                refundRepo.save(refund);
            }
        }
    }

    /**
     * Admin-initiated refund of a captured booking payment, in full or in part.
     *
     * <p>Partial refunds: a payment can be refunded in several parts until all of
     * it is back with the customer. Each refund may be at most what is still
     * refundable; asking for more is refused with that amount, never quietly
     * cut down. A blank amount refunds everything still refundable. The booking
     * stays CAPTURED while part of the money is kept and becomes REFUNDED only
     * once the whole payment is returned, which is when further refunds stop.
     *
     * <p>The appointment row is locked, so two refunds for one payment run one
     * after the other and the second sees what the first returned.
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse adminRefund(String adminId, RefundRequestData data) {
        BaseResponse response = new BaseResponse(true);
        try {
            if (data == null || data.getAppointmentId() == null || data.getAppointmentId().isBlank()) {
                return errorResponse(response, "appointmentId is required");
            }
            // Lock the appointment row so an admin refund racing a cancellation
            // serializes instead of refunding the same payment twice.
            Optional<BookAppointmentEntity> appointmentOpt = appointmentRecords.findByAppointmentIdForUpdate(data.getAppointmentId().trim());
            if (appointmentOpt.isEmpty()) {
                return errorResponse(response, "Invalid Appointment Id");
            }
            BookAppointmentEntity appointment = appointmentOpt.get();
            if (!stripeService.isConfigured() || appointment.getPaymentIntentId() == null || appointment.getPaymentIntentId().isBlank()) {
                return errorResponse(response, "This appointment has no payment to refund");
            }
            Optional<RefundEntity> unconfirmed = refundRepo.findByAppointmentId(appointment.getAppointmentId()).stream()
                    .filter(r -> RefundSafety.UNCONFIRMED.equals(r.getStatus())).findFirst();
            if (unconfirmed.isPresent()) {
                return finishUnconfirmedRefund(adminId, appointment, unconfirmed.get(), data, response);
            }
            if (!"CAPTURED".equals(appointment.getPaymentStatus())) {
                return errorResponse(response, "Payment is not captured — nothing to refund");
            }
            long capturedCents = capturedCents(appointment);
            long alreadyRefundedCents = refundedCents(appointment.getPaymentIntentId());
            long refundableCents = Math.max(0, capturedCents - alreadyRefundedCents);
            if (refundableCents <= 0) {
                return errorResponse(response, "This payment has already been refunded");
            }
            long refundCents = refundableCents;
            if (data.getAmount() != null && !data.getAmount().isBlank()) {
                long requestedCents = MoneyUtils.centsFromPrice(data.getAmount());
                if (requestedCents <= 0) {
                    return errorResponse(response, "Invalid refund amount");
                }
                if (requestedCents > refundableCents) {
                    return errorResponse(response, "You can refund at most $" + MoneyUtils.moneyCents(refundableCents)
                            + " on this payment ($" + MoneyUtils.moneyCents(alreadyRefundedCents)
                            + " of $" + MoneyUtils.moneyCents(capturedCents) + " already refunded)");
                }
                refundCents = requestedCents;
            }
            if (refundCents <= 0) {
                return errorResponse(response, "Invalid refund amount");
            }
            // Stripe is the source of truth: if it has refunded more than our
            // records show (a refund whose reply never reached us), stop.
            long stripeRefundedCents;
            try {
                stripeRefundedCents = stripeService.refundedCents(appointment.getPaymentIntentId());
            } catch (Exception ex) {
                return errorResponse(response, "Could not check this payment with Stripe, so nothing was refunded. Try again in a moment.");
            }
            if (stripeRefundedCents > alreadyRefundedCents) {
                return errorResponse(response, "Stripe shows $" + MoneyUtils.moneyCents(stripeRefundedCents)
                        + " refunded on this payment but our records show $" + MoneyUtils.moneyCents(alreadyRefundedCents)
                        + ". Nothing was refunded; check the payment in the Stripe dashboard first.");
            }
            String refundId = "RFND-" + appUtils.randomAlphanumeric(8).toUpperCase(Locale.ROOT);
            RefundEntity refund = new RefundEntity();
            refund.setRefundId(refundId);
            refund.setAppointmentId(appointment.getAppointmentId());
            refund.setPaymentIntentId(appointment.getPaymentIntentId());
            refund.setAmount(String.format(Locale.ROOT, "%.2f", refundCents / 100.0));
            refund.setReason(data.getReason());
            refund.setStatus("REQUESTED");
            refund.setCreatedBy(adminId == null || adminId.isBlank() ? "SYSTEM" : adminId);
            refund.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            refundRepo.save(refund);
            try {
                com.stripe.model.Refund stripeRefund = stripeService.refundBookingPayment(
                        appointment.getPaymentIntentId(), refundCents, data.getReason(), RefundSafety.idempotencyKey(refund));
                completeAdminRefund(adminId, appointment, refund, stripeRefund, data, response);
                response.setMessage("Refund processed");
            } catch (Exception ex) {
                LOG.warning("Refund failed: " + ex.getMessage());
                if (!RefundSafety.definitelyNotRefunded(ex)) {
                    refund.setStatus(RefundSafety.UNCONFIRMED);
                    refund.setFailureCode(String.valueOf(ex.getMessage()));
                    refundRepo.save(refund);
                    auditService.audit(adminId, "ADMIN", "PAYMENT_REFUND_UNCONFIRMED", "APPOINTMENT", appointment.getAppointmentId(),
                            "Stripe did not confirm refund " + refundId + ": " + ex.getMessage());
                    return errorResponse(response, "Stripe did not confirm this refund of $" + refund.getAmount()
                            + ", so it may or may not have gone through. Press Refund again: it finishes this same refund and never refunds twice.");
                }
                refund.setStatus("FAILED");
                refund.setFailureCode(String.valueOf(ex.getMessage()));
                refundRepo.save(refund);
                auditService.audit(adminId, "ADMIN", "PAYMENT_REFUND_FAILED", "APPOINTMENT", appointment.getAppointmentId(),
                        "Refund failed: " + ex.getMessage());
                return errorResponse(response, "Refund failed — " + ex.getMessage());
            }
        } catch (Exception ex) {
            LOG.warning("Admin refund error: " + ex.getMessage());
        }
        return response;
    }

    /**
     * Finishes a refund whose Stripe reply was lost: finds it at Stripe by its
     * key, or sends it again under the same idempotency key, so it can never
     * become two refunds. Nothing new is refunded in this call.
     */
    private BaseResponse finishUnconfirmedRefund(String adminId, BookAppointmentEntity appointment, RefundEntity refund,
                                                 RefundRequestData data, BaseResponse response) {
        String key = RefundSafety.idempotencyKey(refund);
        com.stripe.model.Refund stripeRefund;
        try {
            stripeRefund = stripeService.findRefund(appointment.getPaymentIntentId(), key).orElse(null);
            if (stripeRefund == null) {
                stripeRefund = stripeService.refundBookingPayment(appointment.getPaymentIntentId(),
                        MoneyUtils.centsFromPrice(refund.getAmount()), refund.getReason(), key);
            }
        } catch (Exception ex) {
            if (RefundSafety.definitelyNotRefunded(ex)) {
                refund.setStatus("FAILED");
                refund.setFailureCode(String.valueOf(ex.getMessage()));
                refundRepo.save(refund);
                return errorResponse(response, "The earlier refund of $" + refund.getAmount() + " did not go through: "
                        + ex.getMessage() + ". You can refund again now.");
            }
            return errorResponse(response, "Stripe still has not confirmed the earlier refund of $" + refund.getAmount()
                    + ". Try again in a moment: it finishes that same refund and never refunds twice.");
        }
        completeAdminRefund(adminId, appointment, refund, stripeRefund, data, response);
        response.setMessage("Finished the earlier refund of $" + refund.getAmount()
                + " that Stripe had not confirmed. Nothing new was refunded; refund again if more is owed.");
        return response;
    }

    /** Records a refund Stripe has accepted, and fills the admin's response. */
    private void completeAdminRefund(String adminId, BookAppointmentEntity appointment, RefundEntity refund,
                                     com.stripe.model.Refund stripeRefund, RefundRequestData data, BaseResponse response) {
        long capturedCents = capturedCents(appointment);
        long refundCents = MoneyUtils.centsFromPrice(refund.getAmount());
        long refundedNowCents = refundedCents(appointment.getPaymentIntentId()) + refundCents;
        refund.setStripeRefundId(stripeRefund.getId());
        refund.setStatus("COMPLETED");
        refund.setCompletedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        boolean fullyRefunded = refundedNowCents >= capturedCents;
        if (fullyRefunded) {
            appointment.setPaymentStatus("REFUNDED");
            appointmentRecords.save(appointment);
        }
        refundRepo.save(refund);
        String reason = refund.getReason();
        auditService.audit(adminId, "ADMIN", "PAYMENT_REFUND", "APPOINTMENT", appointment.getAppointmentId(),
                "Refunded $" + refund.getAmount() + (reason == null || reason.isBlank() ? "" : " — " + reason));
        if (outboxEventService != null) {
            outboxEventService.refundEvent(appointment, refund.getAmount(), reason, true);
        }
        String recovered = recoverStylistShare(adminId, appointment, refund, refundCents, data);
        response.setStatusCode(SUCCESS_STATUS_CODE);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refundId", refund.getRefundId());
        result.put("amount", refund.getAmount());
        result.put("status", "COMPLETED");
        result.put("stripeRefundId", stripeRefund.getId());
        result.put("capturedAmount", MoneyUtils.moneyCents(capturedCents));
        result.put("refundedTotal", MoneyUtils.moneyCents(refundedNowCents));
        result.put("refundableRemaining", MoneyUtils.moneyCents(Math.max(0, capturedCents - refundedNowCents)));
        result.put("fullyRefunded", fullyRefunded);
        result.put("stylistShareRecovered", recovered);
        response.setData(result);
    }

    /**
     * When the admin asked for it and the stylist has been paid, takes the
     * stylist's part of this refund back from their payout. Returns the amount
     * being recovered, or null when nothing is.
     */
    private String recoverStylistShare(String adminId, BookAppointmentEntity appointment, RefundEntity refund,
                                       long refundCents, RefundRequestData data) {
        String transferId = appointment.getStripeTransferId();
        if (!Boolean.TRUE.equals(data.getRecoverStylistShare()) || transferId == null || transferId.isBlank()
                || payoutReversalService == null) {
            return null;
        }
        long shareCents = stylistShareOfRefund(appointment, refundCents);
        if (shareCents <= 0) {
            return null;
        }
        String amount = MoneyUtils.moneyCents(shareCents);
        auditService.audit(adminId, "ADMIN", "PAYOUT_RECOVERY_REQUESTED", "APPOINTMENT", appointment.getAppointmentId(),
                "Taking back $" + amount + " of the stylist payout for refund " + refund.getRefundId());
        payoutReversalService.requestRefundReversal(appointment.getAppointmentId(), transferId, amount,
                "Stylist share of refund " + refund.getRefundId(), refund.getRefundId());
        return amount;
    }

    /**
     * The stylist's part of a refund: the refund split the way the booking's
     * payout was split between stylist and platform.
     */
    public long stylistShareOfRefund(BookAppointmentEntity appointment, long refundCents) {
        Long share = appointment.getStylistShareCents();
        Long fee = appointment.getPlatformFeeCents();
        if (share != null && fee != null && share + fee > 0) {
            return Math.round((double) refundCents * share / (share + fee));
        }
        return refundCents - commissionCents(refundCents, effectiveCommissionPercentForBooking(appointment));
    }

    /** What the customer was charged for this booking, in cents. */
    public long capturedCents(BookAppointmentEntity appointment) {
        return MoneyUtils.centsFromPrice(appointment.getPaymentAmount() == null
                ? appointment.getPrice() : appointment.getPaymentAmount());
    }

    /** What has already gone back to the customer on this payment, in cents. */
    public long refundedCents(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return 0L;
        }
        long cents = 0L;
        for (RefundEntity refund : refundRepo.findByPaymentIntentIdAndStatus(paymentIntentId, "COMPLETED")) {
            cents += MoneyUtils.centsFromPrice(refund.getAmount());
        }
        return cents;
    }

    /** The part of a captured payment that can still be refunded, in cents. */
    public long refundableCents(BookAppointmentEntity appointment) {
        return Math.max(0L, capturedCents(appointment) - refundedCents(appointment.getPaymentIntentId()));
    }

    private BaseResponse errorResponse(BaseResponse response, String message) {
        response.setStatusCode(ERROR_STATUS_CODE);
        response.setMessage(message);
        response.setData(EMPTY_DATA);
        return response;
    }
}

package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.CardDetailsEntity;
import com.macrotel.rapidstylers.entity.RefundEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.RefundRequestData;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.CardDetailsRepo;
import com.macrotel.rapidstylers.repo.PlatformSettingRepo;
import com.macrotel.rapidstylers.repo.RefundRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import com.stripe.model.Account;
import com.stripe.model.Balance;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Payment domain operations carved out of AppService (simplification-plan Step 2,
 * carve 1): Stripe webhooks, payment receipts, automatic and admin refunds,
 * stylist payout summaries, Connect onboarding status transitions, and the
 * platform-commission source of truth.
 *
 * Booking-side payment authorization (near-term authorize, scheduled
 * authorization, capture on acceptance) stays in AppService with the booking
 * workflow; it delegates its payment side effects (receipts, auto-refunds,
 * audit, ops alerts) to this service. The HTTP contract is unchanged —
 * ApplicationController still calls AppService, which delegates here.
 */
@Service
public class PaymentOpsService {

    private static final Logger LOG = Logger.getLogger(PaymentOpsService.class.getName());

    private final AppUtils appUtils = new AppUtils();

    @Autowired
    BookAppointmentRepo bookAppointmentRepo;
    @Autowired
    UserRepo userRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    SubServiceRepo subServiceRepo;
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

    /** Platform commission in minor units at an explicit percent (package-private for tests). */
    long commissionCents(long amountCents, double percent) {
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
            com.macrotel.rapidstylers.entity.PlatformSettingEntity setting =
                    platformSettingRepo.findBySettingKey(COMMISSION_SETTING_KEY)
                            .orElseGet(() -> new com.macrotel.rapidstylers.entity.PlatformSettingEntity(COMMISSION_SETTING_KEY, "12"));
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
                    Optional<SubServiceEntity> sub = subServiceRepo.isServiceExistById(appointment.getStylerId(), Long.parseLong(appointment.getSubServiceId()));
                    if (sub.isPresent() && sub.get().getName() != null) {
                        serviceName = sub.get().getName();
                    }
                } catch (Exception ignored) {
                }
            }
            String when = (appointment.getAppointmentDate() == null ? "" : appointment.getAppointmentDate())
                    + (appointment.getArrivalTime() == null || appointment.getArrivalTime().isBlank() ? "" : " at " + appointment.getArrivalTime());
            String paid = appointment.getPaymentAmount() == null
                    ? (appointment.getPrice() == null ? "—" : appointment.getPrice()) : appointment.getPaymentAmount();
            String details = "<p>Service: " + serviceName + "<br>"
                    + "Date: " + when + "<br>"
                    + "Total paid: $" + paid + "<br>"
                    + "Appointment ref: " + appointment.getAppointmentId() + "</p>"
                    + "<p>Thank you,<br>The RapidStylers Team</p>";
            String subject = "RapidStylers — Payment receipt";

            Optional<UserEntity> userOpt = userRepo.findByUserId(appointment.getUserId());
            String customerEmail = userOpt.map(UserEntity::getEmailAddress).orElse(null);
            if (customerEmail != null && !customerEmail.isBlank()) {
                String name = userOpt.map(u -> (u.getFirstname() + " " + u.getLastname()).trim()).orElse("there");
                emailConfig.sendSimpleMail(customerEmail, subject,
                        "<p>Dear " + (name.isBlank() ? "there" : name) + ",</p>"
                                + "<p><strong>Payment received</strong> — thank you for your business.</p>" + details);
            }
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(appointment.getStylerId());
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
     */
    public void handleStripeWebhook(String payload, String signatureHeader) {
        Event event = stripeService.verifyWebhookEvent(payload, signatureHeader);
        if ("payment_intent.succeeded".equals(event.getType())) {
            PaymentIntent intent = (PaymentIntent) event.getData().getObject();
            bookAppointmentRepo.findByPaymentIntentId(intent.getId()).ifPresent(a -> {
                // Capture is normally persisted synchronously; this webhook keeps
                // state correct when Stripe completes it asynchronously.
                boolean alreadyCaptured = "CAPTURED".equals(a.getPaymentStatus());
                a.setPaymentStatus("CAPTURED");
                a.setPaymentFailureCode(null);
                bookAppointmentRepo.save(a);
                if (!alreadyCaptured) {
                    sendPaymentReceipt(a);
                }
                auditService.audit("system", "SYSTEM", "PAYMENT_CAPTURED", "APPOINTMENT", a.getAppointmentId(), "Payment captured");
            });
        } else if ("payment_intent.payment_failed".equals(event.getType())) {
            PaymentIntent intent = (PaymentIntent) event.getData().getObject();
            bookAppointmentRepo.findByPaymentIntentId(intent.getId()).ifPresent(a -> {
                a.setPaymentStatus("PAYMENT_FAILED");
                a.setPaymentFailureCode("PAYMENT_FAILED");
                bookAppointmentRepo.save(a);
                auditService.audit("system", "SYSTEM", "PAYMENT_FAILED", "APPOINTMENT", a.getAppointmentId(), "Payment failed");
            });
        } else if ("account.updated".equals(event.getType())) {
            handleAccountUpdated((Account) event.getData().getObject());
        } else if ("payment_intent.canceled".equals(event.getType())) {
            PaymentIntent intent = (PaymentIntent) event.getData().getObject();
            bookAppointmentRepo.findByPaymentIntentId(intent.getId()).ifPresent(a -> {
                a.setPaymentStatus("RELEASED");
                bookAppointmentRepo.save(a);
                auditService.audit("system", "SYSTEM", "PAYMENT_RELEASED", "APPOINTMENT", a.getAppointmentId(), "Payment hold released");
            });
        } else if ("charge.dispute.created".equals(event.getType())) {
            handleDispute((com.stripe.model.Dispute) event.getData().getObject(), true);
        } else if ("charge.dispute.closed".equals(event.getType())) {
            handleDispute((com.stripe.model.Dispute) event.getData().getObject(), false);
        }
    }

    /**
     * Records dispute lifecycle on the booking: an opened dispute flags the
     * payment and alerts ops; a closed dispute resolves it (won -> CAPTURED,
     * lost -> DISPUTE_LOST).
     */
    private void handleDispute(com.stripe.model.Dispute dispute, boolean opened) {
        try {
            String paymentIntentId = dispute == null ? null : dispute.getPaymentIntent();
            if (paymentIntentId == null || paymentIntentId.isBlank()) {
                LOG.warning("Dispute webhook missing payment intent: " + (dispute == null ? "null" : dispute.getId()));
                return;
            }
            bookAppointmentRepo.findByPaymentIntentId(paymentIntentId).ifPresent(a -> {
                if (opened) {
                    a.setPaymentStatus("DISPUTED");
                    bookAppointmentRepo.save(a);
                    auditService.audit("system", "SYSTEM", "PAYMENT_DISPUTE_OPENED", "APPOINTMENT", a.getAppointmentId(),
                            "Chargeback opened (dispute " + dispute.getId() + ")");
                    auditService.alertAdmin("Payment dispute opened for appointment " + a.getAppointmentId()
                            + " (dispute " + dispute.getId() + ")");
                } else {
                    boolean lost = "lost".equalsIgnoreCase(dispute.getStatus());
                    a.setPaymentStatus(lost ? "DISPUTE_LOST" : "CAPTURED");
                    bookAppointmentRepo.save(a);
                    auditService.audit("system", "SYSTEM", "PAYMENT_DISPUTE_CLOSED", "APPOINTMENT", a.getAppointmentId(),
                            "Dispute closed (" + dispute.getStatus() + ") for dispute " + dispute.getId());
                }
            });
        } catch (Exception ex) {
            LOG.warning("Dispute handling failed: " + ex.getMessage());
        }
    }

    /**
     * Applies the account.updated webhook: updates the styler's Connect
     * onboarding status and emails them when onboarding completes or is
     * rejected (only on actual transitions, so retries never re-send).
     */
    void handleAccountUpdated(Account account) {
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
        String finalStatus = status;
        String finalDisabledReason = disabledReason;
        stylerRepo.findByStripeConnectAccountId(account.getId()).ifPresent(s -> {
            String previous = s.getConnectOnboardingStatus();
            s.setConnectOnboardingStatus(finalStatus);
            // Persist the rejection reason so the Payouts page can show it; clear it
            // once the account is verified or still in progress.
            s.setConnectDisabledReason("REJECTED".equals(finalStatus) ? finalDisabledReason : null);
            stylerRepo.save(s);
            auditService.audit("system", "SYSTEM", "CONNECT_ACCOUNT_UPDATED", "STYLER", s.getStylerId(), finalStatus);
            if ("COMPLETE".equals(finalStatus) && !"COMPLETE".equals(previous)) {
                sendConnectStatusEmail(s, "RapidStylers - Payouts are ready",
                        "Your payout account is connected",
                        "Your Stripe account is connected and payouts are enabled. Your share of "
                                + "completed appointments will be paid on Stripe's regular payout schedule.");
            } else if ("REJECTED".equals(finalStatus) && !"REJECTED".equals(previous)) {
                String reason = finalDisabledReason == null ? "" : " (" + finalDisabledReason.replace('_', ' ') + ")";
                sendConnectStatusEmail(s, "RapidStylers - Payout setup needs attention",
                        "Your payout account could not be verified",
                        "Stripe could not verify your payout account" + reason
                                + ". Please reconnect from your dashboard or contact support.");
            }
        });
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
     * Refunds a captured payment in full — used by automatic paths (reject /
     * cancel after capture). Idempotent: skips when a completed refund already
     * exists for the payment intent.
     */
    public void autoRefundCapturedPayment(BookAppointmentEntity appointment, String reason, String actorId) {
        try {
            if (refundRepo.existsByPaymentIntentIdAndStatus(appointment.getPaymentIntentId(), "COMPLETED")) {
                return;
            }
            long totalCents = MoneyUtils.centsFromPrice(appointment.getPaymentAmount() == null
                    ? appointment.getPrice() : appointment.getPaymentAmount());
            if (totalCents <= 0) {
                return;
            }
            String refundId = "RFND-" + appUtils.randomAlphanumeric(8).toUpperCase(Locale.ROOT);
            RefundEntity refund = new RefundEntity();
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
                    appointment.getPaymentIntentId(), totalCents, reason,
                    "refund_" + appointment.getPaymentIntentId() + "_" + refundId);
            refund.setStripeRefundId(stripeRefund.getId());
            refund.setStatus("COMPLETED");
            refund.setCompletedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            appointment.setPaymentStatus("REFUNDED");
            bookAppointmentRepo.save(appointment);
            refundRepo.save(refund);
            auditService.audit(actorId, "SYSTEM", "PAYMENT_REFUND_AUTO", "APPOINTMENT", appointment.getAppointmentId(),
                    "Automatic refund of $" + refund.getAmount() + " — " + reason);
            // A completed-then-cancelled booking means the stylist payout transfer
            // was already created. Recover it automatically: the reversal is
            // attempted now and retried by a scheduled job when the stylist's
            // balance cannot cover it yet.
            if (appointment.getStripeTransferId() != null && !appointment.getStripeTransferId().isBlank()) {
                if (payoutReversalService != null) {
                    long shareCents = totalCents - commissionCents(totalCents);
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
        }
    }

    /**
     * Admin-initiated refund of a captured booking payment. Idempotent per
     * payment intent: a completed refund blocks a second one, so retries and
     * double-clicks never double-refund.
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
            Optional<BookAppointmentEntity> appointmentOpt = bookAppointmentRepo.findByAppointmentIdForUpdate(data.getAppointmentId().trim());
            if (appointmentOpt.isEmpty()) {
                return errorResponse(response, "Invalid Appointment Id");
            }
            BookAppointmentEntity appointment = appointmentOpt.get();
            if (!stripeService.isConfigured() || appointment.getPaymentIntentId() == null || appointment.getPaymentIntentId().isBlank()) {
                return errorResponse(response, "This appointment has no payment to refund");
            }
            if (!"CAPTURED".equals(appointment.getPaymentStatus())) {
                return errorResponse(response, "Payment is not captured — nothing to refund");
            }
            if (refundRepo.existsByPaymentIntentIdAndStatus(appointment.getPaymentIntentId(), "COMPLETED")) {
                return errorResponse(response, "This payment has already been refunded");
            }
            long totalCents = MoneyUtils.centsFromPrice(appointment.getPaymentAmount() == null
                    ? appointment.getPrice() : appointment.getPaymentAmount());
            long refundCents = totalCents;
            if (data.getAmount() != null && !data.getAmount().isBlank()) {
                long requestedCents = MoneyUtils.centsFromPrice(data.getAmount());
                if (requestedCents <= 0) {
                    return errorResponse(response, "Invalid refund amount");
                }
                refundCents = Math.min(requestedCents, totalCents);
            }
            if (refundCents <= 0) {
                return errorResponse(response, "Invalid refund amount");
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
                        appointment.getPaymentIntentId(), refundCents, data.getReason(),
                        "refund_" + appointment.getPaymentIntentId() + "_" + refundId);
                refund.setStripeRefundId(stripeRefund.getId());
                refund.setStatus("COMPLETED");
                refund.setCompletedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                appointment.setPaymentStatus("REFUNDED");
                bookAppointmentRepo.save(appointment);
                refundRepo.save(refund);
                auditService.audit(adminId, "ADMIN", "PAYMENT_REFUND", "APPOINTMENT", appointment.getAppointmentId(),
                        "Refunded $" + refund.getAmount()
                                + (data.getReason() == null || data.getReason().isBlank() ? "" : " — " + data.getReason()));
                if (outboxEventService != null) {
                    outboxEventService.refundEvent(appointment, refund.getAmount(), data.getReason(), true);
                }
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("Refund processed");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("refundId", refundId);
                result.put("amount", refund.getAmount());
                result.put("status", "COMPLETED");
                result.put("stripeRefundId", stripeRefund.getId());
                response.setData(result);
            } catch (Exception ex) {
                LOG.warning("Refund failed: " + ex.getMessage());
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

    /** Lists all refund records, newest first, for the admin view. */
    public BaseResponse adminRefunds(String adminId) {
        BaseResponse response = new BaseResponse(true);
        try {
            List<RefundEntity> refunds = refundRepo.findAll();
            refunds.sort(java.util.Comparator.comparing(RefundEntity::getCreatedAt,
                    java.util.Comparator.nullsLast(String::compareTo)).reversed());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Refunds retrieved");
            response.setData(refunds);
        } catch (Exception ex) {
            LOG.warning("Admin refund list error: " + ex.getMessage());
        }
        return response;
    }

    public BaseResponse getStylerPayouts(String stylerId) {
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(stylerId);
            if (stylerOpt.isEmpty()) {
                return errorResponse(response, "Invalid Styler Id");
            }
            StylerEntity styler = stylerOpt.get();
            String accountId = styler.getStripeConnectAccountId();
            boolean connected = accountId != null && !accountId.isBlank();

            BigDecimal totalEarned = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalCommission = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            List<Map<String, Object>> appointments = new ArrayList<>();
            for (BookAppointmentEntity appointment : bookAppointmentRepo.findByStylerId(stylerId)) {
                if (!"0".equals(appointment.getStatus())
                        || appointment.getPaymentIntentId() == null
                        || !"CAPTURED".equals(appointment.getPaymentStatus())) {
                    continue;
                }
                BigDecimal total = MoneyUtils.amount(appointment.getPaymentAmount() == null
                        ? appointment.getPrice() : appointment.getPaymentAmount());
                BigDecimal commission = total.multiply(BigDecimal.valueOf(effectiveCommissionPercent()))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal share = total.subtract(commission);
                totalEarned = totalEarned.add(share);
                totalCommission = totalCommission.add(commission);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("appointmentId", appointment.getAppointmentId());
                row.put("date", appointment.getAppointmentDate());
                row.put("arrivalTime", appointment.getArrivalTime());
                row.put("total", MoneyUtils.money(total));
                row.put("commission", MoneyUtils.money(commission));
                row.put("stylerShare", MoneyUtils.money(share));
                appointments.add(row);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("connected", connected);
            data.put("status", connected && styler.getConnectOnboardingStatus() != null
                    ? styler.getConnectOnboardingStatus() : "NOT_STARTED");
            data.put("disabledReason", connected ? styler.getConnectDisabledReason() : null);
            data.put("totalEarned", MoneyUtils.money(totalEarned));
            data.put("totalCommission", MoneyUtils.money(totalCommission));
            data.put("stripeAvailable", "0.00");
            data.put("stripePending", "0.00");
            if (connected && stripeService.isConfigured()) {
                try {
                    Balance balance = Balance.retrieve(RequestOptions.builder().setStripeAccount(accountId).build());
                    data.put("stripeAvailable", MoneyUtils.moneyCents(sumBalanceAmounts(balance.getAvailable(), stripeService.currency())));
                    data.put("stripePending", MoneyUtils.moneyCents(sumPendingAmounts(balance.getPending(), stripeService.currency())));
                } catch (Exception ex) {
                    LOG.warning("Connected balance lookup failed: " + ex.getMessage());
                }
            }
            data.put("appointments", appointments);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch (Exception ex) {
            LOG.warning("Payout summary failed: " + ex.getMessage());
            return errorResponse(response, "Could not load payout summary");
        }
        return response;
    }

    private long sumBalanceAmounts(java.util.List<Balance.Available> entries, String currency) {
        long total = 0L;
        for (Balance.Available entry : entries) {
            if (entry.getAmount() != null && (currency == null || currency.equals(entry.getCurrency()))) {
                total += entry.getAmount();
            }
        }
        return total;
    }

    private long sumPendingAmounts(java.util.List<Balance.Pending> entries, String currency) {
        long total = 0L;
        for (Balance.Pending entry : entries) {
            if (entry.getAmount() != null && (currency == null || currency.equals(entry.getCurrency()))) {
                total += entry.getAmount();
            }
        }
        return total;
    }

    private BaseResponse errorResponse(BaseResponse response, String message) {
        response.setStatusCode(ERROR_STATUS_CODE);
        response.setMessage(message);
        response.setData(EMPTY_DATA);
        return response;
    }
}

package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.config.ThrottledLog;
import com.macrotel.rapidstylers.entity.AuditLogEntity;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentIntentCollection;
import com.stripe.param.PaymentIntentListParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static com.macrotel.rapidstylers.config.AppConstants.ERROR_STATUS_CODE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * Cross-checks recent Stripe PaymentIntents against locally stored bookings so
 * drift (paid in Stripe but not captured locally, stale holds, orphaned
 * intents) becomes visible instead of silently building up.
 *
 * Runs on a schedule and on demand via GET /admin/payment_reconciliation.
 * Every run writes an audit record; runs with findings also email the
 * configured ops address (app.admin.alert-email).
 */
@Service
public class PaymentReconciliationService {

    private static final Logger LOG = Logger.getLogger(PaymentReconciliationService.class.getName());

    @Autowired
    private BookAppointmentRepo bookAppointmentRepo;
    @Autowired
    private AuditLogRepo auditLogRepo;
    @Autowired
    private EmailConfig emailConfig;

    @Value("${app.payment.reconcile-window-hours:72}")
    private long windowHours;

    @Value("${app.payment.reconcile-interval-ms:86400000}")
    private long reconcileIntervalMs;

    @Value("${app.admin.alert-email:}")
    private String adminAlertEmail;

    /** Most recent run report, exposed to the admin endpoint. */
    private volatile Map<String, Object> lastReport = new LinkedHashMap<>();

    @Scheduled(fixedDelayString = "${app.payment.reconcile-interval-ms:86400000}")
    public void scheduledReconcile() {
        runReconciliation();
    }

    public BaseResponse runReconciliation() {
        BaseResponse response = new BaseResponse(true);
        try {
            List<PaymentIntent> intents = fetchRecentIntents();
            Map<String, Object> report = buildReport(intents);
            lastReport = report;

            String summary = "Checked " + report.get("stripeIntentsChecked") + " Stripe intents; "
                    + report.get("issueCount") + " issue(s)";
            audit("system", "SYSTEM", "PAYMENT_RECONCILIATION", "PAYMENT",
                    String.valueOf(System.currentTimeMillis()), summary);
            if (!((Boolean) report.get("ok"))) {
                @SuppressWarnings("unchecked")
                List<String> issues = (List<String>) report.get("issues");
                alertAdmin(summary + "\n" + String.join("\n", issues));
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(summary);
            response.setData(report);
        } catch (StripeException ex) {
            // The scheduled run fires on every context boot, so a missing/invalid
            // Stripe key would otherwise warn once per boot — throttle to one line
            // per window; the audit-less failure is still visible in the response.
            ThrottledLog.warnOncePerWindow(LOG, "payment/reconcile-failed",
                    "Payment reconciliation failed: " + ex.getMessage());
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Reconciliation failed: " + ex.getMessage());
            response.setData(Collections.emptyMap());
        } catch (Exception ex) {
            ThrottledLog.warnOncePerWindow(LOG, "payment/reconcile-error",
                    "Payment reconciliation error: " + ex.getMessage());
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Reconciliation error: " + ex.getMessage());
            response.setData(Collections.emptyMap());
        }
        return response;
    }

    /** Returns PaymentIntents created within the configured lookback window. */
    List<PaymentIntent> fetchRecentIntents() throws StripeException {
        long sinceEpoch = System.currentTimeMillis() / 1000 - windowHours * 3600;
        PaymentIntentListParams params = PaymentIntentListParams.builder()
                .setCreated(sinceEpoch)
                .setLimit(100L)
                .build();
        PaymentIntentCollection collection = PaymentIntent.list(params);
        if (collection == null || collection.getData() == null) {
            return Collections.emptyList();
        }
        return collection.getData();
    }

    /**
     * Matches Stripe intents against local bookings and produces the drift
     * report. Package-private so the matcher is unit-testable without the
     * static Stripe API.
     */
    Map<String, Object> buildReport(List<PaymentIntent> intents) {
        Map<String, Object> report = new LinkedHashMap<>();

        Map<String, BookAppointmentEntity> localByIntent = new HashMap<>();
        for (BookAppointmentEntity appointment : bookAppointmentRepo.findAll()) {
            if (appointment.getPaymentIntentId() != null && !appointment.getPaymentIntentId().isBlank()) {
                localByIntent.put(appointment.getPaymentIntentId(), appointment);
            }
        }

        List<String> issues = new ArrayList<>();
        int matched = 0;
        for (PaymentIntent intent : intents) {
            BookAppointmentEntity local = localByIntent.get(intent.getId());
            if (local == null) {
                issues.add("Stripe intent " + intent.getId() + " has no local appointment");
                continue;
            }
            matched++;
            String stripeStatus = intent.getStatus() == null ? "unknown" : intent.getStatus();
            String localStatus = local.getPaymentStatus() == null ? "null" : local.getPaymentStatus();
            if ("succeeded".equals(stripeStatus) && !"CAPTURED".equals(localStatus)) {
                issues.add("Stripe " + intent.getId() + " succeeded but local is " + localStatus
                        + " (appointment " + local.getAppointmentId() + ")");
            } else if ("requires_capture".equals(stripeStatus)
                    && !"AUTHORIZED".equals(localStatus)
                    && !"PAYMENT_ACCEPTED_SCHEDULED".equals(localStatus)) {
                issues.add("Stripe " + intent.getId() + " awaiting capture but local is " + localStatus
                        + " (appointment " + local.getAppointmentId() + ")");
            } else if ("canceled".equals(stripeStatus)
                    && !"RELEASED".equals(localStatus)
                    && !"REFUNDED".equals(localStatus)
                    && !"PAYMENT_FAILED".equals(localStatus)) {
                issues.add("Stripe " + intent.getId() + " canceled but local is " + localStatus
                        + " (appointment " + local.getAppointmentId() + ")");
            }
        }

        // Stale holds: a local AUTHORIZED payment on a cancelled/rejected booking
        // should have been released; flag it so ops can clean up.
        for (BookAppointmentEntity appointment : localByIntent.values()) {
            if ("AUTHORIZED".equals(appointment.getPaymentStatus())
                    && ("2".equals(appointment.getStatus()) || "4".equals(appointment.getStatus()))) {
                issues.add("Stale authorized hold on appointment " + appointment.getAppointmentId()
                        + " (" + appointment.getPaymentIntentId() + ") — should be released");
            }
        }

        report.put("runAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        report.put("windowHours", windowHours);
        report.put("stripeIntentsChecked", intents.size());
        report.put("matched", matched);
        report.put("issueCount", issues.size());
        report.put("issues", issues);
        report.put("ok", issues.isEmpty());
        return report;
    }

    /** Latest report from the most recent run, for the admin endpoint. */
    public Map<String, Object> latestReport() {
        return lastReport;
    }

    private void audit(String actorId, String actorRole, String action, String resourceType,
                       String resourceId, String details) {
        try {
            if (auditLogRepo != null) {
                auditLogRepo.save(new AuditLogEntity(actorId, actorRole, action, resourceType, resourceId, details));
            }
        } catch (Exception ex) {
            LOG.warning("Audit log write failed: " + ex.getMessage());
        }
    }

    private void alertAdmin(String message) {
        if (adminAlertEmail == null || adminAlertEmail.isBlank()) {
            LOG.warning("Payment reconciliation findings (no alert email configured): " + message);
            return;
        }
        try {
            if (emailConfig != null) {
                emailConfig.sendSimpleMail(adminAlertEmail, "RapidStylers - Payment reconciliation issues",
                        "<pre>" + message + "</pre>");
            }
        } catch (Exception ex) {
            LOG.warning("Reconciliation alert email failed: " + ex.getMessage());
        }
    }
}

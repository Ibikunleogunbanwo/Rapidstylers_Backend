package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.config.ThrottledLog;
import com.macrotel.rapidstylers.payment.entity.ReconciliationRunEntity;
import com.macrotel.rapidstylers.payment.repo.ReconciliationRunRepo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentIntentCollection;
import com.stripe.param.PaymentIntentListParams;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.ERROR_STATUS_CODE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * Cross-checks recent Stripe PaymentIntents against locally stored bookings so
 * drift (paid in Stripe but not captured locally, stale holds, orphaned
 * intents) becomes visible instead of silently building up.
 *
 * Runs on a schedule and on demand via GET /admin/payment_reconciliation.
 * Every run writes an audit record and keeps its report (the admin screen
 * shows the latest through GET /admin/payment_reconciliation_latest); runs
 * with findings also email the configured ops address (app.admin.alert-email).
 */
@Service
public class PaymentReconciliationService {

    private static final Logger LOG = Logger.getLogger(PaymentReconciliationService.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private AppointmentRecords appointmentRecords;
    @Autowired
    private AuditService auditService;
    @Autowired
    private EmailConfig emailConfig;
    /** Optional so unit tests that build this by hand need no database. */
    @Autowired(required = false)
    private ReconciliationRunRepo reconciliationRunRepo;

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";

    @Value("${app.payment.reconcile-window-hours:72}")
    private long windowHours;

    @Value("${app.payment.reconcile-interval-ms:86400000}")
    private long reconcileIntervalMs;

    /** This deployment's name (live, demo, local), matching the tag StripeService puts on intents. */
    @Value("${APP_ENV:local}")
    private String environment = "local";

    @Value("${app.admin.alert-email:}")
    private String adminAlertEmail;

    /** Most recent run report, exposed to the admin endpoint. */
    private volatile Map<String, Object> lastReport = new LinkedHashMap<>();

    @Scheduled(fixedDelayString = "${app.payment.reconcile-interval-ms:86400000}",
            initialDelayString = "${app.payment.reconcile-initial-delay-ms:60000}")
    public void scheduledReconcile() {
        runReconciliation(TRIGGER_SCHEDULED);
    }

    /** A run an admin asked for. */
    public BaseResponse runReconciliation() {
        return runReconciliation(TRIGGER_MANUAL);
    }

    public BaseResponse runReconciliation(String trigger) {
        BaseResponse response = new BaseResponse(true);
        try {
            List<PaymentIntent> intents = fetchRecentIntents();
            Map<String, Object> report = buildReport(intents);
            lastReport = report;
            saveRun(report, trigger);

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

    /**
     * List params for the lookback window. Kept package-private so the range
     * shape is unit-testable: a bare {@code created=<epoch>} is an EXACT
     * second match in Stripe's API, not a lower bound, so it must be built as
     * {@code created[gte]} or the drift check silently examines nothing.
     */
    static PaymentIntentListParams listParams(long sinceEpoch) {
        return PaymentIntentListParams.builder()
                .setCreated(PaymentIntentListParams.Created.builder().setGte(sinceEpoch).build())
                .setLimit(100L)
                .build();
    }

    /** Returns PaymentIntents created within the configured lookback window. */
    List<PaymentIntent> fetchRecentIntents() throws StripeException {
        long sinceEpoch = System.currentTimeMillis() / 1000 - windowHours * 3600;
        PaymentIntentListParams params = listParams(sinceEpoch);
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
        for (BookAppointmentEntity appointment : appointmentRecords.findAll()) {
            if (appointment.getPaymentIntentId() != null && !appointment.getPaymentIntentId().isBlank()) {
                localByIntent.put(appointment.getPaymentIntentId(), appointment);
            }
        }

        List<String> issues = new ArrayList<>();
        int matched = 0;
        int otherEnvironments = 0;
        for (PaymentIntent intent : intents) {
            BookAppointmentEntity local = localByIntent.get(intent.getId());
            if (local == null && !isOurs(intent)) {
                // Made by another deployment sharing this Stripe account (the
                // demo, a local run, a test suite): not missing from here.
                otherEnvironments++;
                continue;
            }
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
        report.put("otherEnvironments", otherEnvironments);
        report.put("issueCount", issues.size());
        report.put("issues", issues);
        report.put("ok", issues.isEmpty());
        return report;
    }

    /**
     * Whether a payment intent belongs to this deployment. Intents carry the
     * environment that created them; an intent from another environment, or
     * one not made by RapidStylers at all, is not ours to reconcile. Older
     * intents without the tag are still checked, as before.
     */
    boolean isOurs(PaymentIntent intent) {
        Map<String, String> metadata = intent.getMetadata();
        if (metadata == null || metadata.isEmpty()) return true;
        String platform = metadata.get("platform");
        if (platform != null && !"rapidstylers".equals(platform)) return false;
        String origin = metadata.get("environment");
        return origin == null || origin.isBlank() || origin.equals(environment());
    }

    private String environment() {
        return environment == null || environment.isBlank() ? "local" : environment.trim();
    }

    /** Latest report from the most recent run, for the admin endpoint. */
    public Map<String, Object> latestReport() {
        return lastReport;
    }

    /**
     * Keeps a run's report. Never throws: a run that checked Stripe has done its
     * job even if the copy for the admin screen could not be written.
     */
    void saveRun(Map<String, Object> report, String trigger) {
        if (reconciliationRunRepo == null) {
            return;
        }
        try {
            ReconciliationRunEntity run = new ReconciliationRunEntity();
            run.setRanAt(LocalDateTime.now());
            run.setTriggerSource(TRIGGER_SCHEDULED.equals(trigger) ? TRIGGER_SCHEDULED : TRIGGER_MANUAL);
            run.setOk(Boolean.TRUE.equals(report.get("ok")));
            run.setIssueCount(((Number) report.getOrDefault("issueCount", 0)).intValue());
            run.setReportJson(JSON.writeValueAsString(report));
            reconciliationRunRepo.save(run);
        } catch (Exception ex) {
            LOG.warning("Could not save the reconciliation report: " + ex.getMessage());
        }
    }

    /**
     * The newest saved run: its report plus ranAt (ISO-8601 with offset) and
     * trigger (SCHEDULED or MANUAL). Data is null, with a message saying so,
     * before the first run.
     */
    public BaseResponse latestSavedReport() {
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<ReconciliationRunEntity> latest = reconciliationRunRepo == null ? Optional.empty()
                    : reconciliationRunRepo.findFirstByOrderByRanAtDescIdDesc();
            response.setStatusCode(SUCCESS_STATUS_CODE);
            if (latest.isEmpty()) {
                response.setMessage("No reconciliation report yet");
                response.setData(null);
                return response;
            }
            ReconciliationRunEntity run = latest.get();
            Map<String, Object> data = JSON.readValue(run.getReportJson(), new TypeReference<LinkedHashMap<String, Object>>() { });
            data.put("ranAt", run.getRanAt().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString());
            data.put("trigger", run.getTriggerSource());
            response.setMessage("Latest reconciliation report");
            response.setData(data);
        } catch (Exception ex) {
            LOG.warning("Could not read the reconciliation report: " + ex.getMessage());
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Could not load the reconciliation report");
            response.setData(Collections.emptyMap());
        }
        return response;
    }

    private void audit(String actorId, String actorRole, String action, String resourceType,
                       String resourceId, String details) {
        if (auditService != null) {
            auditService.audit(actorId, actorRole, action, resourceType, resourceId, details);
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

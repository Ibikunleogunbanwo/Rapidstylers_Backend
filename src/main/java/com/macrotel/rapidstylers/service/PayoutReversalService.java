package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.AuditLogEntity;
import com.macrotel.rapidstylers.entity.PayoutReversalEntity;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import com.macrotel.rapidstylers.repo.PayoutReversalRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Recovers stylist payout transfers when a completed booking is cancelled and
 * refunded. A reversal record is created once per transfer and attempted
 * immediately; failures are retried by a scheduled job (funds may not be
 * available yet — e.g. the stylist already withdrew them), with exponential
 * backoff, until the attempt budget is exhausted.
 *
 * The Stripe idempotency key is stable per reversal record, so retries can
 * never double-reverse a transfer.
 */
@Service
public class PayoutReversalService {

    private static final Logger LOG = Logger.getLogger(PayoutReversalService.class.getName());

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private PayoutReversalRepo payoutReversalRepo;
    @Autowired
    private StripeService stripeService;
    @Autowired
    private AuditLogRepo auditLogRepo;
    @Autowired
    private EmailConfig emailConfig;

    @Value("${app.payout.reversal-max-attempts:5}")
    private int maxAttempts;

    @Value("${app.payout.reversal-retry-interval-ms:1800000}")
    private long retryIntervalMs;

    @Value("${app.admin.alert-email:}")
    private String adminAlertEmail;

    /**
     * Creates a reversal record (at most one per transfer) and attempts the
     * Stripe reversal immediately. Safe to call from the refund path.
     */
    public void requestReversal(String appointmentId, String transferId, String amount, String reason) {
        if (transferId == null || transferId.isBlank()) {
            return;
        }
        try {
            if (!payoutReversalRepo.findByTransferId(transferId).isEmpty()) {
                return; // already tracked — the retry job owns it
            }
            PayoutReversalEntity record = new PayoutReversalEntity();
            record.setReversalId("REV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
            record.setAppointmentId(appointmentId);
            record.setTransferId(transferId);
            record.setAmount(amount);
            record.setStatus("PENDING");
            record.setCreatedAt(LocalDateTime.now().format(TS));
            record.setNextAttemptAt(LocalDateTime.now());
            payoutReversalRepo.save(record);
            audit("SYSTEM", "SYSTEM", "PAYOUT_REVERSAL_REQUESTED", "APPOINTMENT", appointmentId,
                    "Reversal requested for transfer " + transferId + " (" + record.getReversalId() + ")");
            attemptReversal(record);
        } catch (Exception ex) {
            LOG.warning("Payout reversal request failed: " + ex.getMessage());
        }
    }

    /** Retries reversals that failed and whose next attempt is due. */
    @Scheduled(fixedDelayString = "${app.payout.reversal-retry-interval-ms:1800000}")
    public void retryDueReversals() {
        try {
            List<PayoutReversalEntity> due = payoutReversalRepo
                    .findTop20ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                            List.of("PENDING", "FAILED"), LocalDateTime.now());
            for (PayoutReversalEntity record : due) {
                attemptReversal(record);
            }
        } catch (Exception ex) {
            LOG.warning("Payout reversal retry job failed: " + ex.getMessage());
        }
    }

    /** Attempts the Stripe reversal once and transitions the record. */
    private void attemptReversal(PayoutReversalEntity record) {
        try {
            com.stripe.model.TransferReversal reversal = stripeService.reverseTransfer(
                    record.getTransferId(),
                    "Appointment " + record.getAppointmentId() + " cancelled after completion",
                    "reversal_" + record.getTransferId() + "_" + record.getReversalId());
            record.setStatus("REVERSED");
            record.setStripeReversalId(reversal.getId());
            record.setLastError(null);
            record.setReversedAt(LocalDateTime.now().format(TS));
            payoutReversalRepo.save(record);
            audit("SYSTEM", "SYSTEM", "PAYOUT_REVERSED", "APPOINTMENT", record.getAppointmentId(),
                    "Payout recovered: transfer " + record.getTransferId() + " reversed (" + reversal.getId() + ")");
            LOG.info("Payout transfer reversed: " + record.getTransferId());
        } catch (Exception ex) {
            record.setAttempts(record.getAttempts() + 1);
            record.setLastError(String.valueOf(ex.getMessage()));
            if (record.getAttempts() >= maxAttempts) {
                record.setStatus("PERMANENTLY_FAILED");
                payoutReversalRepo.save(record);
                audit("SYSTEM", "SYSTEM", "PAYOUT_REVERSAL_FAILED", "APPOINTMENT", record.getAppointmentId(),
                        "Payout " + record.getTransferId() + " could not be reversed after "
                                + record.getAttempts() + " attempts: " + ex.getMessage());
                alertAdmin("Payout recovery failed: transfer " + record.getTransferId()
                        + " for appointment " + record.getAppointmentId()
                        + " could not be reversed after " + record.getAttempts()
                        + " attempts (" + ex.getMessage() + "). Recover manually.");
            } else {
                record.setStatus("FAILED");
                record.setNextAttemptAt(LocalDateTime.now().plusMinutes(backoffMinutes(record.getAttempts())));
                payoutReversalRepo.save(record);
                audit("SYSTEM", "SYSTEM", "PAYOUT_REVERSAL_RETRYING", "APPOINTMENT", record.getAppointmentId(),
                        "Reversal attempt " + record.getAttempts() + " failed for " + record.getTransferId()
                                + ": " + ex.getMessage());
            }
            LOG.warning("Payout reversal attempt " + record.getAttempts() + " failed for "
                    + record.getTransferId() + ": " + ex.getMessage());
        }
    }

    /** 5, 10, 20, … minutes, capped at 24 hours. */
    private long backoffMinutes(int attempts) {
        long minutes = 5L * (1L << Math.min(attempts - 1, 7));
        return Math.min(minutes, 1440L);
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
            LOG.warning(message);
            return;
        }
        try {
            if (emailConfig != null) {
                emailConfig.sendSimpleMail(adminAlertEmail, "RapidStylers - Payout recovery action required",
                        "<pre>" + message + "</pre>");
            }
        } catch (Exception ex) {
            LOG.warning("Payout reversal alert email failed: " + ex.getMessage());
        }
    }
}

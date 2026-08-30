package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.repo.OTPRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Logger;

/**
 * Recovers abandoned customer registrations as a staged cron e-mail campaign.
 *
 * A customer who requests a sign-up OTP but never creates an account leaves an
 * otp_codes row and nothing else. The cron runs frequently and, per sign-up
 * attempt, e-mails exactly the next unpaid milestone once its age threshold is
 * crossed, off the outbox -&gt; Kafka -&gt; email pipeline:
 *   stage 1 (24 hours)   — friendly reminder
 *   stage 2 (7 days)     — corporate/value message
 *   stage 3 (14 days)    — benefit nudge
 *   stage 4 (1 month)    — final call
 * Only emails that still have no user_accounts row are followed up, and each
 * stage is sent at most once (tracked on the latest attempt's otp row). The
 * same cron run also purges otp_codes older than the retention window.
 */
@Component
public class SignupFollowUpService {

    private static final Logger LOG = Logger.getLogger(SignupFollowUpService.class.getName());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final OTPRepo otpRepo;
    private final OutboxEventService outboxEventService;

    @Value("${app.signup-followup.hours-stage-1:24}")
    private long hoursStage1;

    @Value("${app.signup-followup.hours-stage-2:168}")
    private long hoursStage2;

    @Value("${app.signup-followup.hours-stage-3:336}")
    private long hoursStage3;

    @Value("${app.signup-followup.hours-stage-4:720}")
    private long hoursStage4;

    @Value("${app.signup-followup.retention-days:30}")
    private long retentionDays;

    public SignupFollowUpService(OTPRepo otpRepo, OutboxEventService outboxEventService) {
        this.otpRepo = otpRepo;
        this.outboxEventService = outboxEventService;
    }

    /** Cron entry point (default: hourly; overridable via config). Also exposed for tests. */
    @Scheduled(cron = "${app.signup-followup.cron:0 0 * * * *}")
    @Transactional(rollbackFor = Exception.class)
    public void followUpAbandonedSignups() {
        try {
            LocalDateTime now = LocalDateTime.now();
            for (Object[] row : otpRepo.findSignupAttemptCandidates()) {
                String email = String.valueOf(row[0]);
                String attemptDt = String.valueOf(row[1]);
                int currentStage = row[2] == null ? 0 : ((Number) row[2]).intValue();
                LocalDateTime attemptedAt;
                try {
                    attemptedAt = LocalDateTime.parse(attemptDt, STAMP);
                } catch (Exception ex) {
                    continue; // unparseable timestamp — skip, don't fail the job
                }
                int due = dueStage(Math.abs(Duration.between(attemptedAt, now).toHours()), currentStage);
                if (due > 0) {
                    outboxEventService.signupReminder(email, due);
                    otpRepo.markLatestSignupStage(email, due, now);
                    LOG.info("Scheduled sign-up follow-up stage " + due + " for " + email);
                }
            }
        } catch (Exception ex) {
            LOG.warning("Signup follow-up failed: " + ex.getMessage());
        }

        purgeExpiredOtps();
    }

    /** Highest unpaid milestone due given the attempt age in hours and the stage already sent. */
    private int dueStage(long hoursSinceAttempt, int currentStage) {
        int due = 0;
        if (hoursSinceAttempt >= hoursStage1 && currentStage < 1) due = Math.max(due, 1);
        if (hoursSinceAttempt >= hoursStage2 && currentStage < 2) due = Math.max(due, 2);
        if (hoursSinceAttempt >= hoursStage3 && currentStage < 3) due = Math.max(due, 3);
        if (hoursSinceAttempt >= hoursStage4 && currentStage < 4) due = Math.max(due, 4);
        return due;
    }

    /** Deletes otp_codes rows older than the retention window. */
    public void purgeExpiredOtps() {
        try {
            String cutoff = LocalDateTime.now().minusDays(retentionDays).format(STAMP);
            int purged = otpRepo.deleteOlderThan(cutoff);
            if (purged > 0) {
                LOG.info("Purged " + purged + " stale otp_codes rows");
            }
        } catch (Exception ex) {
            LOG.warning("OTP purge failed: " + ex.getMessage());
        }
    }
}
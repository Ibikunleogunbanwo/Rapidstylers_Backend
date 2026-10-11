package com.macrotel.rapidstylers.common.service;

import com.macrotel.rapidstylers.common.entity.FunnelEventEntity;
import com.macrotel.rapidstylers.common.repo.FunnelEventRepo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The sign-up funnel's tally sheet. Picture a row of turnstiles a newcomer
 * walks through: visit the site, start signing up (ask for the email code),
 * verify the email, create the account, make a first booking. Each turnstile
 * clicks once per person (per day for the first two), and the admin report
 * compares the counts.
 *
 * <p>People are stored only as a SHA-256 of their browser id, email or account
 * id. Recording never fails or slows the caller's work: inside a transaction it
 * waits until that work commits (a rolled-back sign-up is not counted), and a
 * failure is logged and dropped.
 */
@Service
public class FunnelService {

    private static final Logger LOG = Logger.getLogger(FunnelService.class.getName());

    public static final String VISITED = "VISITED";
    public static final String SIGNUP_STARTED = "SIGNUP_STARTED";
    public static final String EMAIL_VERIFIED = "EMAIL_VERIFIED";
    public static final String ACCOUNT_CREATED = "ACCOUNT_CREATED";
    public static final String FIRST_BOOKING = "FIRST_BOOKING";
    /** In funnel order. */
    public static final List<String> STEPS = List.of(VISITED, SIGNUP_STARTED, EMAIL_VERIFIED, ACCOUNT_CREATED, FIRST_BOOKING);

    @Autowired FunnelEventRepo funnelEventRepo;
    @Autowired(required = false) PlatformTransactionManager transactionManager;

    @Value("${app.time-zone:America/Edmonton}")
    String reportTimeZone = "America/Edmonton";

    /** Counts {@code subject} (browser id, email or account id) reaching {@code step}. Never throws. */
    public void record(String step, String role, String subject) {
        if (step == null || subject == null || subject.isBlank()) return;
        String hashed = sha256(subject.trim().toLowerCase(Locale.ROOT));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Runnable write = () -> write(step, role, hashed, now);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }

    /** (step, role, subject hash, day) for every event on these reporting days, inclusive. */
    public List<Object[]> eventsBetween(LocalDate from, LocalDate to) {
        return funnelEventRepo.eventsBetween(from.toString(), to.toString());
    }

    public ZoneId reportZone() {
        try {
            return ZoneId.of(reportTimeZone == null || reportTimeZone.isBlank() ? "America/Edmonton" : reportTimeZone.trim());
        } catch (Exception ex) {
            return ZoneId.of("America/Edmonton");
        }
    }

    private void write(String step, String role, String subject, LocalDateTime nowUtc) {
        try {
            String day = nowUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(reportZone()).toLocalDate().toString();
            // Visits and sign-up starts count once per person per day; the rest once ever.
            boolean daily = VISITED.equals(step) || SIGNUP_STARTED.equals(step);
            String dedupe = step + ":" + (role == null ? "" : role) + ":" + subject + (daily ? ":" + day : "");
            Runnable insert = () -> {
                if (funnelEventRepo.existsByDedupeKey(dedupe)) return;
                FunnelEventEntity e = new FunnelEventEntity();
                e.setStep(step);
                e.setRole(role);
                e.setSubject(subject);
                e.setDayKey(day);
                e.setOccurredAt(nowUtc);
                e.setDedupeKey(dedupe);
                funnelEventRepo.save(e);
            };
            if (transactionManager == null) {
                insert.run();
            } else {
                // Its own transaction: after a commit the caller's is finished, and a
                // duplicate racing in must not spoil anyone else's work.
                TransactionTemplate own = new TransactionTemplate(transactionManager);
                own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                own.executeWithoutResult(status -> insert.run());
            }
        } catch (Exception ex) {
            LOG.fine("Funnel event not recorded (" + step + "): " + ex.getMessage());
        }
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}

package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.ThrottledLog;
import com.macrotel.rapidstylers.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.repo.AdminAccountRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

/**
 * Step-up re-authentication for sensitive admin actions (refunds, admin-account
 * changes). Even with a valid ADMIN session, these actions require the acting
 * admin to re-enter their password, which is re-verified against their stored
 * BCrypt hash — so a session alone (including one that a step-up-conscious
 * attacker holds) is never enough to refund money or alter accounts.
 *
 * Failed attempts are rate-limited just like login to stop password guessing.
 */
@Service
public class StepUpService {

    private static final Logger LOG = Logger.getLogger(StepUpService.class.getName());

    private static final int WINDOW_SECONDS = 900; // 15 min
    private static final int MAX_FAILURES = 5;

    @Autowired
    private AdminAccountRepo adminAccountRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RateLimiterService rateLimiterService;

    /**
     * Verify a presented password against the admin identified by their login
     * subject (the email used at /admin_sign_in). Returns false on missing/blank
     * input, unknown/disabled accounts, wrong password, or lockout.
     */
    public boolean verify(String adminEmail, String presentedPassword) {
        if (adminEmail == null || adminEmail.isBlank()) {
            return false;
        }
        String key = "stepup:" + adminEmail.trim().toLowerCase();
        if (presentedPassword == null || presentedPassword.isBlank()
                || rateLimiterService.isBlocked(key, WINDOW_SECONDS, MAX_FAILURES)) {
            return false;
        }
        AdminAccountEntity admin = adminAccountRepo.findByEmailIgnoreCase(adminEmail).orElse(null);
        if (admin == null || !admin.isEnabled()) {
            recordFailure(key);
            return false;
        }
        boolean valid = passwordEncoder.matches(presentedPassword, admin.getPasswordHash());
        if (valid) {
            rateLimiterService.clear(key);
        } else {
            recordFailure(key);
        }
        return valid;
    }

    private void recordFailure(String key) {
        try {
            rateLimiterService.record(key, WINDOW_SECONDS);
        } catch (Exception ex) {
            ThrottledLog.warnOncePerWindow(LOG, "stepup/record-failure",
                    "Step-up failure could not be recorded: " + ex.getMessage());
        }
    }
}
package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.LoginAttemptEntity;
import com.macrotel.rapidstylers.repo.LoginAttemptRepo;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {

    private final LoginAttemptRepo loginAttemptRepo;

    public LoginAttemptService(LoginAttemptRepo loginAttemptRepo) {
        this.loginAttemptRepo = loginAttemptRepo;
    }

    public void recordSuccess(String accountType, String accountId, String emailAddress,
                              String ipAddress, String userAgent) {
        save(accountType, accountId, emailAddress, ipAddress, userAgent, true, null);
    }

    public void recordFailure(String accountType, String accountId, String emailAddress,
                              String ipAddress, String userAgent, String failureReason) {
        save(accountType, accountId, emailAddress, ipAddress, userAgent, false, failureReason);
    }

    private void save(String accountType, String accountId, String emailAddress, String ipAddress,
                      String userAgent, boolean success, String failureReason) {
        try {
            LoginAttemptEntity attempt = new LoginAttemptEntity();
            attempt.setAccountType(value(accountType, "UNKNOWN"));
            attempt.setAccountId(accountId);
            attempt.setEmailAddress(value(emailAddress, "unknown"));
            attempt.setIpAddress(value(ipAddress, "unknown"));
            attempt.setUserAgent(userAgent);
            attempt.setSuccess(success);
            attempt.setFailureReason(failureReason);
            loginAttemptRepo.save(attempt);
        } catch (Exception ignored) {
            // Audit is important, but it must not break the auth response path.
        }
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

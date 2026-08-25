package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.LoginAttemptEntity;
import com.macrotel.rapidstylers.repo.LoginAttemptRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LoginAttemptServiceTest {

    private LoginAttemptRepo loginAttemptRepo;
    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        loginAttemptRepo = mock(LoginAttemptRepo.class);
        loginAttemptService = new LoginAttemptService(loginAttemptRepo);
    }

    @Test
    void recordsFailedLoginAttemptWithAccountTypeAndReason() {
        loginAttemptService.recordFailure("CUSTOMER", "USER1", "user@example.com", "127.0.0.1",
                "Mozilla/5.0", "INVALID_CREDENTIALS");

        var captor = forClass(LoginAttemptEntity.class);
        verify(loginAttemptRepo).save(captor.capture());
        LoginAttemptEntity attempt = captor.getValue();
        assertEquals("CUSTOMER", attempt.getAccountType());
        assertEquals("USER1", attempt.getAccountId());
        assertEquals("user@example.com", attempt.getEmailAddress());
        assertEquals("127.0.0.1", attempt.getIpAddress());
        assertEquals("Mozilla/5.0", attempt.getUserAgent());
        assertEquals("INVALID_CREDENTIALS", attempt.getFailureReason());
        assertFalse(attempt.getSuccess());
        assertNotNull(attempt.getCreatedAt());
    }
}

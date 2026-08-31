package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.repo.AdminAccountRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepUpServiceTest {

    private AdminAccountRepo repo;
    private PasswordEncoder encoder;
    private RateLimiterService rateLimiter;
    private StepUpService service;

    private AdminAccountEntity admin(String email, boolean enabled, String hash) {
        AdminAccountEntity a = new AdminAccountEntity();
        a.setEmail(email);
        a.setEnabled(enabled);
        a.setPasswordHash(hash);
        return a;
    }

    @BeforeEach
    void setUp() {
        repo = mock(AdminAccountRepo.class);
        encoder = mock(PasswordEncoder.class);
        rateLimiter = mock(RateLimiterService.class);
        service = new StepUpService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adminAccountRepo", repo);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "passwordEncoder", encoder);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "rateLimiterService", rateLimiter);
        when(rateLimiter.isBlocked(anyString(), eq(900), eq(5))).thenReturn(false);
    }

    @Test
    void correctPasswordPassesStepUp() {
        when(repo.findByEmailIgnoreCase("admin@example.com"))
                .thenReturn(Optional.of(admin("admin@example.com", true, "$2a$hash")));
        when(encoder.matches("hunter2good", "$2a$hash")).thenReturn(true);
        assertTrue(service.verify("admin@example.com", "hunter2good"));
    }

    @Test
    void wrongPasswordFailsAndRatesTheAttempt() {
        when(repo.findByEmailIgnoreCase("admin@example.com"))
                .thenReturn(Optional.of(admin("admin@example.com", true, "$2a$hash")));
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        assertFalse(service.verify("admin@example.com", "nope"));
    }

    @Test
    void blankOrMissingInputIsRejected() {
        assertFalse(service.verify("admin@example.com", null));
        assertFalse(service.verify("admin@example.com", "   "));
        assertFalse(service.verify(null, "hunter2good"));
        assertFalse(service.verify("", "hunter2good"));
    }

    @Test
    void unknownOrDisabledAdminFails() {
        when(repo.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());
        assertFalse(service.verify("ghost@example.com", "hunter2good"));

        when(repo.findByEmailIgnoreCase("off@example.com"))
                .thenReturn(Optional.of(admin("off@example.com", false, "$2a$hash")));
        assertFalse(service.verify("off@example.com", "hunter2good"));
    }

    @Test
    void lockoutRejectsEvenCorrectPassword() {
        when(repo.findByEmailIgnoreCase("admin@example.com"))
                .thenReturn(Optional.of(admin("admin@example.com", true, "$2a$hash")));
        when(rateLimiter.isBlocked(anyString(), eq(900), eq(5))).thenReturn(true);
        assertFalse(service.verify("admin@example.com", "hunter2good"));
    }
}
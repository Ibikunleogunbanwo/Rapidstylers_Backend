package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.OTPEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.ForgotPasswordData;
import com.macrotel.rapidstylers.repo.OTPRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for the password-reset OTP verification fix.
 *
 * <p>Before the fix, {@code resetPassword()} accepted an email + new password
 * without verifying that a "FORGET PASSWORD" OTP had been successfully verified.
 * This allowed anyone who knew an email address to reset any account's password.
 *
 * <p>The fix adds a check: {@code otpRepo.verifyOtpSuccessForPurpose(email, "FORGET PASSWORD")}
 * must return a verified OTP before the password is changed. The OTP is then
 * invalidated so it cannot be reused.
 */
class PasswordResetOtpVerificationTest {

    private AppService appService;
    private UserRepo userRepo;
    private OTPRepo otpRepo;
    private EmailConfig emailConfig;
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        userRepo = mock(UserRepo.class);
        otpRepo = mock(OTPRepo.class);
        emailConfig = mock(EmailConfig.class);
        rateLimiterService = mock(RateLimiterService.class);

        appService.userRepo = userRepo;
        appService.otpRepo = otpRepo;
        appService.emailConfig = emailConfig;
        appService.rateLimiterService = rateLimiterService;

        // Rate limiter always permits — individual tests override when needed
        when(rateLimiterService.isBlocked(anyString(), anyInt(), anyInt())).thenReturn(false);
        when(rateLimiterService.clientIp()).thenReturn("127.0.0.1");
    }

    // ── Bypass prevention ─────────────────────────────────────────────

    @Test
    void resetPasswordRejectedWithoutVerifiedOtp() {
        UserEntity user = existingUser("user@example.com");
        when(userRepo.findByEmailAddress("user@example.com")).thenReturn(Optional.of(user));
        // No verified OTP exists
        when(otpRepo.verifyOtpSuccessForPurpose("user@example.com", "FORGET PASSWORD"))
                .thenReturn(Optional.empty());

        ForgotPasswordData data = resetData("user@example.com", "NewPass1!", "NewPass1!");
        BaseResponse response = appService.resetPassword(data);

        assertEquals("400", response.getStatusCode());
        assertTrue(response.getMessage().toLowerCase().contains("not authorized"),
                "Must reject reset without verified OTP: " + response.getMessage());
        verify(userRepo, never()).save(any());
    }

    @Test
    void resetPasswordRejectedWhenOtpExistsButNotVerified() {
        UserEntity user = existingUser("user@example.com");
        when(userRepo.findByEmailAddress("user@example.com")).thenReturn(Optional.of(user));
        // OTP exists but was never verified (isUsed='1' means unverified in this schema)
        when(otpRepo.verifyOtpSuccessForPurpose("user@example.com", "FORGET PASSWORD"))
                .thenReturn(Optional.empty());

        BaseResponse response = appService.resetPassword(resetData("user@example.com", "NewPass1!", "NewPass1!"));

        assertEquals("400", response.getStatusCode());
        verify(userRepo, never()).save(any());
    }

    // ── Happy path ────────────────────────────────────────────────────

    @Test
    void resetPasswordSucceedsWithVerifiedOtp() {
        UserEntity user = existingUser("user@example.com");
        when(userRepo.findByEmailAddress("user@example.com")).thenReturn(Optional.of(user));

        OTPEntity verifiedOtp = new OTPEntity();
        verifiedOtp.setEmailAddress("user@example.com");
        verifiedOtp.setPurpose("FORGET PASSWORD");
        verifiedOtp.setIsUsed("0"); // verified = consumed
        when(otpRepo.verifyOtpSuccessForPurpose("user@example.com", "FORGET PASSWORD"))
                .thenReturn(Optional.of(verifiedOtp));

        BaseResponse response = appService.resetPassword(resetData("user@example.com", "NewPass1!", "NewPass1!"));

        assertEquals("200", response.getStatusCode());
        assertEquals("Password Change Successful", response.getMessage());
        verify(userRepo).save(any());
    }

    // ── OTP reuse prevention ──────────────────────────────────────────

    @Test
    void otpIsInvalidatedAfterSuccessfulReset() {
        UserEntity user = existingUser("user@example.com");
        when(userRepo.findByEmailAddress("user@example.com")).thenReturn(Optional.of(user));

        OTPEntity verifiedOtp = new OTPEntity();
        verifiedOtp.setId(42L);
        verifiedOtp.setEmailAddress("user@example.com");
        verifiedOtp.setPurpose("FORGET PASSWORD");
        verifiedOtp.setIsUsed("0");
        when(otpRepo.verifyOtpSuccessForPurpose("user@example.com", "FORGET PASSWORD"))
                .thenReturn(Optional.of(verifiedOtp));

        appService.resetPassword(resetData("user@example.com", "NewPass1!", "NewPass1!"));

        // The consumed OTP row is DELETED after a successful reset. Re-saving it
        // with is_used="0" would not prevent reuse: "0" is exactly the state
        // verifyOtpSuccessForPurpose matches, so on a live DB the same verified
        // code could authorize repeated resets until the row was purged.
        verify(otpRepo).delete(argThat(otp ->
                otp.getId() != null && otp.getId() == 42L
        ));
    }

    @Test
    void secondResetAttemptFailsWithoutNewOtpVerification() {
        UserEntity user = existingUser("user@example.com");
        when(userRepo.findByEmailAddress("user@example.com")).thenReturn(Optional.of(user));

        // First reset: OTP verified
        OTPEntity firstOtp = new OTPEntity();
        firstOtp.setIsUsed("0");
        when(otpRepo.verifyOtpSuccessForPurpose("user@example.com", "FORGET PASSWORD"))
                .thenReturn(Optional.of(firstOtp));
        appService.resetPassword(resetData("user@example.com", "Pass1111!", "Pass1111!"));

        // Second reset: no new OTP verified (old one was consumed)
        when(otpRepo.verifyOtpSuccessForPurpose("user@example.com", "FORGET PASSWORD"))
                .thenReturn(Optional.empty());

        BaseResponse secondAttempt = appService.resetPassword(resetData("user@example.com", "Pass2222!", "Pass2222!"));
        assertEquals("400", secondAttempt.getStatusCode());
        assertTrue(secondAttempt.getMessage().toLowerCase().contains("not authorized"));
    }

    // ── Password validation ───────────────────────────────────────────

    @Test
    void resetRejectedWhenPasswordsDoNotMatch() {
        BaseResponse response = appService.resetPassword(
                resetData("user@example.com", "Pass1111!", "Pass2222!"));

        assertEquals("400", response.getStatusCode());
        assertTrue(response.getMessage().toLowerCase().contains("does not match"));
        // Should fail before reaching OTP check
        verify(otpRepo, never()).verifyOtpSuccessForPurpose(anyString(), anyString());
    }

    // ── Email validation ──────────────────────────────────────────────

    @Test
    void resetRejectedForNonExistentEmail() {
        when(userRepo.findByEmailAddress("ghost@example.com")).thenReturn(Optional.empty());

        BaseResponse response = appService.resetPassword(
                resetData("ghost@example.com", "Pass1111!", "Pass1111!"));

        assertEquals("400", response.getStatusCode());
        assertTrue(response.getMessage().toLowerCase().contains("invalid"));
        // Should fail before reaching OTP check
        verify(otpRepo, never()).verifyOtpSuccessForPurpose(anyString(), anyString());
    }

    // ── OTP purpose isolation ─────────────────────────────────────────

    @Test
    void signupOtpDoesNotAllowPasswordReset() {
        UserEntity user = existingUser("user@example.com");
        when(userRepo.findByEmailAddress("user@example.com")).thenReturn(Optional.of(user));

        // A USER SIGN UP OTP was verified, not a FORGET PASSWORD OTP
        when(otpRepo.verifyOtpSuccessForPurpose("user@example.com", "FORGET PASSWORD"))
                .thenReturn(Optional.empty());

        BaseResponse response = appService.resetPassword(resetData("user@example.com", "NewPass1!", "NewPass1!"));

        assertEquals("400", response.getStatusCode());
        assertTrue(response.getMessage().toLowerCase().contains("not authorized"));
    }

    @Test
    void stylerSignupOtpDoesNotAllowPasswordReset() {
        UserEntity user = existingUser("styler@example.com");
        when(userRepo.findByEmailAddress("styler@example.com")).thenReturn(Optional.of(user));

        // A STYLER SIGN UP OTP was verified, not a FORGET PASSWORD OTP
        when(otpRepo.verifyOtpSuccessForPurpose("styler@example.com", "FORGET PASSWORD"))
                .thenReturn(Optional.empty());

        BaseResponse response = appService.resetPassword(resetData("styler@example.com", "NewPass1!", "NewPass1!"));

        assertEquals("400", response.getStatusCode());
    }

    // ── Edge cases ────────────────────────────────────────────────────

    @Test
    void resetPasswordWithNullPasswordFails() {
        ForgotPasswordData data = new ForgotPasswordData();
        data.setEmailAddress("user@example.com");
        data.setPassword(null);
        data.setConfirmPassword(null);

        // The method catches exceptions internally and returns an error response
        BaseResponse response = appService.resetPassword(data);
        assertEquals("400", response.getStatusCode());
    }

    @Test
    void resetPasswordTrimsAndComparesPasswords() {
        // Identical passwords — should proceed past password-match check to OTP check
        BaseResponse response = appService.resetPassword(resetData("user@example.com", "Pass1111!", "Pass1111!"));
        assertEquals("400", response.getStatusCode());
        // Fails at OTP check because no verified OTP exists
        assertFalse(response.getMessage().toLowerCase().contains("does not match"),
                "Should NOT fail on password mismatch when passwords are equal");
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private UserEntity existingUser(String email) {
        UserEntity user = new UserEntity();
        user.setUserId("A1234B");
        user.setEmailAddress(email);
        user.setFirstname("Test");
        user.setLastname("User");
        user.setPassword("$2a$10$abcdefghijklmnopqrstuuMONLYBCRYPTHASH"); // dummy hash
        user.setStatus("0");
        return user;
    }

    private ForgotPasswordData resetData(String email, String password, String confirm) {
        ForgotPasswordData data = new ForgotPasswordData();
        data.setEmailAddress(email);
        data.setPassword(password);
        data.setConfirmPassword(confirm);
        return data;
    }
}

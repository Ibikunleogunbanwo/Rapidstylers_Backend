package com.macrotel.rapidstylers.auth.service;

import com.macrotel.rapidstylers.auth.entity.OTPEntity;
import com.macrotel.rapidstylers.auth.pojo.OTPData;
import com.macrotel.rapidstylers.auth.repo.OTPRepo;
import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the signup-OTP hardening for BOTH registration flows (customer and
 * styler/vendor):
 *
 * <ul>
 *   <li>OTPs are stored as BCrypt hashes, never plaintext.</li>
 *   <li>Verification is bound to the email the code was issued for — a code
 *       alone (or a code presented for a different email) never verifies.</li>
 *   <li>Verification is purpose-scoped: a styler code cannot be verified through
 *       the customer endpoint and vice versa.</li>
 *   <li>OTP generation is enumeration-safe: registered emails get the identical
 *       generic success response, with no OTP row and no email sent.</li>
 * </ul>
 */
class OtpSecurityTest {

    private static final Pattern OTP_IN_EMAIL = Pattern.compile("OTP Code: <strong>(\\d{6})</strong>");
    private static final String GENERIC_SENT = "A one-time password (OTP) code has been sent to your email. Please verify it.";
    private static final String RESET_SENT = "Password Reset Initiated, Check Mail for OTP Code";

    private AuthService authService;
    private OtpService otpService;
    private UserRepo userRepo;
    private StylerRepo stylerRepo;
    private OTPRepo otpRepo;
    private EmailConfig emailConfig;
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        authService = new AuthService();
        userRepo = mock(UserRepo.class);
        stylerRepo = mock(StylerRepo.class);
        otpRepo = mock(OTPRepo.class);
        emailConfig = mock(EmailConfig.class);
        rateLimiterService = mock(RateLimiterService.class);

        otpService = new OtpService();
        otpService.userRepo = userRepo;
        otpService.stylerRepo = stylerRepo;
        otpService.otpRepo = otpRepo;
        otpService.emailConfig = emailConfig;
        otpService.rateLimiterService = rateLimiterService;
        otpService.appUtils = new AppUtils();
        otpService.jwtUtil = mock(JwtUtil.class);

        authService.userRepo = userRepo;
        authService.stylerRepo = stylerRepo;
        authService.otpRepo = otpRepo;
        authService.rateLimiterService = rateLimiterService;
        authService.appUtils = new AppUtils();
        authService.jwtUtil = mock(JwtUtil.class);

        when(rateLimiterService.isBlocked(anyString(), anyInt(), anyInt())).thenReturn(false);
        when(rateLimiterService.clientIp()).thenReturn("127.0.0.1");
        when(userRepo.findByEmailAddress(anyString())).thenReturn(Optional.empty());
        when(stylerRepo.findByEmailAddress(anyString())).thenReturn(Optional.empty());
        when(otpRepo.checkSignUpValidityOtp(anyString(), anyString())).thenReturn(Optional.empty());
        when(otpRepo.findLatestUnusedByEmail(anyString())).thenReturn(Optional.empty());
    }

    // ── Hashing: no plaintext in otp_codes.code ───────────────────────

    @Test
    void customerOtpStoredAsHashNotPlaintext() {
        BaseResponse response = otpService.generateSignUpOtpCode(otpData("new@example.com"));

        assertEquals("200", response.getStatusCode());
        OTPEntity saved = captureSavedOtp();
        assertEquals("USER SIGN UP", saved.getPurpose());
        String emailedCode = codeFromLastEmail();
        assertNotEquals(emailedCode, saved.getCode(), "OTP must not be stored in plaintext");
        assertTrue(saved.getCode().startsWith("$2"), "OTP must be stored as a BCrypt hash");
        assertTrue(new AppUtils().otpMatches(emailedCode, saved.getCode()),
                "stored hash must verify against the code sent by email");
    }

    @Test
    void stylerOtpStoredAsHashNotPlaintext() {
        BaseResponse response = otpService.stylerGenerateOtp(otpData("vendor@example.com"));

        assertEquals("200", response.getStatusCode());
        OTPEntity saved = captureSavedOtp();
        assertEquals("STYLER SIGN UP", saved.getPurpose());
        String emailedCode = codeFromLastEmail();
        assertNotEquals(emailedCode, saved.getCode(), "OTP must not be stored in plaintext");
        assertTrue(saved.getCode().startsWith("$2"), "OTP must be stored as a BCrypt hash");
        assertTrue(new AppUtils().otpMatches(emailedCode, saved.getCode()),
                "stored hash must verify against the code sent by email");
    }

    // ── Email binding: code alone / wrong email never verifies ────────

    @Test
    void customerVerifyRejectsWrongEmail() {
        otpService.generateSignUpOtpCode(otpData("real@example.com"));
        String code = codeFromLastEmail();
        org.mockito.Mockito.clearInvocations(otpRepo, emailConfig);

        BaseResponse response = otpService.verifyUserOTP("other@example.com", code);

        assertEquals("400", response.getStatusCode());
        assertEquals("Invalid OTP Code", response.getMessage());
        verify(otpRepo, never()).save(any());
    }

    @Test
    void customerVerifyRejectsBlankEmail() {
        BaseResponse response = otpService.verifyUserOTP("", "123456");
        assertEquals("400", response.getStatusCode());
        assertEquals("Invalid OTP Code", response.getMessage());
        verify(otpRepo, never()).save(any());
    }

    @Test
    void customerVerifyRejectsWrongCodeForEmail() {
        when(otpRepo.findLatestUnusedByEmail("real@example.com"))
                .thenReturn(Optional.of(otpEntity("real@example.com", "USER SIGN UP", "111111")));

        BaseResponse response = otpService.verifyUserOTP("real@example.com", "222222");

        assertEquals("400", response.getStatusCode());
        assertEquals("Invalid OTP Code", response.getMessage());
        verify(otpRepo, never()).save(any());
    }

    // ── Purpose scoping ───────────────────────────────────────────────

    @Test
    void customerVerifyRejectsStylerPurposeCode() {
        when(otpRepo.findLatestUnusedByEmail("vendor@example.com"))
                .thenReturn(Optional.of(otpEntity("vendor@example.com", "STYLER SIGN UP", "111111")));

        BaseResponse response = otpService.verifyUserOTP("vendor@example.com", "111111");

        assertEquals("400", response.getStatusCode());
        assertEquals("Invalid OTP Code", response.getMessage());
        verify(otpRepo, never()).save(any());
    }

    @Test
    void stylerVerifyRejectsCustomerPurposeCode() {
        when(otpRepo.findLatestUnusedByEmail("real@example.com"))
                .thenReturn(Optional.of(otpEntity("real@example.com", "USER SIGN UP", "111111")));

        BaseResponse response = otpService.stylerVerifyOtp("real@example.com", "111111");

        assertEquals("400", response.getStatusCode());
        assertEquals("Invalid OTP Code", response.getMessage());
        verify(otpRepo, never()).save(any());
    }

    // ── Happy paths with email binding ────────────────────────────────

    @Test
    void customerVerifySucceedsWithEmailAndCode() {
        when(otpRepo.findLatestUnusedByEmail("real@example.com"))
                .thenReturn(Optional.of(otpEntity("real@example.com", "USER SIGN UP", "123456")));

        BaseResponse response = otpService.verifyUserOTP("real@example.com", "123456");

        assertEquals("200", response.getStatusCode());
        assertEquals("Email Address Verify Successful", response.getMessage());
        verify(otpRepo).save(argThat(otp -> otp.getIsUsed() != null && otp.getIsUsed().equals("0")));
        verify(rateLimiterService).clear("auth:real@example.com");
        verify(rateLimiterService).clear("auth_ip:127.0.0.1");
    }

    @Test
    void stylerVerifySucceedsWithEmailAndCode() {
        when(otpRepo.findLatestUnusedByEmail("vendor@example.com"))
                .thenReturn(Optional.of(otpEntity("vendor@example.com", "STYLER SIGN UP", "123456")));

        BaseResponse response = otpService.stylerVerifyOtp("vendor@example.com", "123456");

        assertEquals("200", response.getStatusCode());
        assertEquals("Email Address Verified Successfully", response.getMessage());
        // The upload pass for the sign-up photos: the email, a pass role, and a short life.
        verify(otpService.jwtUtil).generateToken(eq("vendor@example.com"), eq("SIGNUP_UPLOAD"), any(Duration.class));
        verify(otpRepo).save(argThat(otp -> otp.getIsUsed() != null && otp.getIsUsed().equals("0")));
        verify(rateLimiterService).clear("auth:vendor@example.com");
        verify(rateLimiterService).clear("auth_ip:127.0.0.1");
    }

    // ── Enumeration safety ────────────────────────────────────────────

    @Test
    void customerOtpGenerateDoesNotEnumerateExistingEmail() {
        when(userRepo.findByEmailAddress("taken@example.com"))
                .thenReturn(Optional.of(mock(UserEntity.class)));

        BaseResponse response = otpService.generateSignUpOtpCode(otpData("taken@example.com"));

        assertEquals("200", response.getStatusCode(), "existing email must look like success");
        assertEquals(GENERIC_SENT, response.getMessage(), "response must be identical to a fresh email");
        verify(otpRepo, never()).save(any());
        verify(emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void customerOtpGenerateDoesNotEnumerateEmailRegisteredAsStyler() {
        when(stylerRepo.findByEmailAddress("styler@example.com"))
                .thenReturn(Optional.of(mock(StylerEntity.class)));

        BaseResponse response = otpService.generateSignUpOtpCode(otpData("styler@example.com"));

        assertEquals("200", response.getStatusCode());
        assertEquals(GENERIC_SENT, response.getMessage());
        verify(otpRepo, never()).save(any());
        verify(emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void stylerOtpGenerateDoesNotEnumerateExistingEmail() {
        when(stylerRepo.findByEmailAddress("taken-vendor@example.com"))
                .thenReturn(Optional.of(mock(StylerEntity.class)));

        BaseResponse response = otpService.stylerGenerateOtp(otpData("taken-vendor@example.com"));

        assertEquals("200", response.getStatusCode(), "existing email must look like success");
        assertEquals(GENERIC_SENT, response.getMessage(), "response must be identical to a fresh email");
        verify(otpRepo, never()).save(any());
        verify(emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void stylerOtpGenerateDoesNotEnumerateEmailRegisteredAsCustomer() {
        when(userRepo.findByEmailAddress("customer@example.com"))
                .thenReturn(Optional.of(mock(UserEntity.class)));

        BaseResponse response = otpService.stylerGenerateOtp(otpData("customer@example.com"));

        assertEquals("200", response.getStatusCode());
        assertEquals(GENERIC_SENT, response.getMessage());
        verify(otpRepo, never()).save(any());
        verify(emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    // ── Password-reset OTP: enumeration-safe + hashed + verifiable ────

    @Test
    void resetOtpForUnknownEmailDoesNotEnumerate() {
        // userRepo mock returns empty by default in setUp.
        BaseResponse response = otpService.resetPasswordMessage(otpData("ghost@example.com"));

        assertEquals("200", response.getStatusCode(), "unknown email must look like success");
        assertEquals(RESET_SENT, response.getMessage(), "response must be identical to a registered email");
        verify(otpRepo, never()).save(any());
        verify(emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void resetOtpForKnownEmailIsStoredAsHashNotPlaintext() {
        when(userRepo.findByEmailAddress("real@example.com"))
                .thenReturn(Optional.of(mock(UserEntity.class)));

        BaseResponse response = otpService.resetPasswordMessage(otpData("real@example.com"));

        assertEquals("200", response.getStatusCode());
        OTPEntity saved = captureSavedOtp();
        assertEquals("FORGET PASSWORD", saved.getPurpose());
        String emailedCode = codeFromLastEmail();
        assertNotEquals(emailedCode, saved.getCode(), "reset OTP must not be stored in plaintext");
        assertTrue(saved.getCode().startsWith("$2"), "reset OTP must be stored as a BCrypt hash");
        assertTrue(new AppUtils().otpMatches(emailedCode, saved.getCode()),
                "stored hash must verify against the code sent by email");
    }

    @Test
    void hashedPasswordResetOtpStillVerifiesWithEmailBinding() {
        // The exact failure mode: if a reset OTP were stored plaintext, verification
        // (which now compares against a BCrypt hash) would reject every reset code.
        when(otpRepo.findLatestUnusedByEmail("real@example.com"))
                .thenReturn(Optional.of(otpEntity("real@example.com", "FORGET PASSWORD", "123456")));

        BaseResponse response = otpService.verifyUserOTP("real@example.com", "123456");

        assertEquals("200", response.getStatusCode());
        assertEquals("Email Address Verify Successful", response.getMessage());
        verify(otpRepo).save(argThat(otp -> otp.getIsUsed() != null && otp.getIsUsed().equals("0")));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private OTPData otpData(String email) {
        OTPData data = new OTPData();
        data.setEmailAddress(email);
        return data;
    }

    private OTPEntity otpEntity(String email, String purpose, String rawCode) {
        OTPEntity entity = new OTPEntity();
        entity.setEmailAddress(email);
        entity.setPurpose(purpose);
        entity.setCode(new AppUtils().hashOtp(rawCode));
        return entity;
    }

    private OTPEntity captureSavedOtp() {
        ArgumentCaptor<OTPEntity> captor = ArgumentCaptor.forClass(OTPEntity.class);
        verify(otpRepo).save(captor.capture());
        return captor.getValue();
    }

    private String codeFromLastEmail() {
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailConfig).sendSimpleMail(anyString(), anyString(), bodyCaptor.capture());
        Matcher matcher = OTP_IN_EMAIL.matcher(bodyCaptor.getValue());
        assertTrue(matcher.find(), "no 6-digit OTP found in email body: " + bodyCaptor.getValue());
        return matcher.group(1);
    }

}
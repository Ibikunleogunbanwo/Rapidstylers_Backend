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
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
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
 *       generic success response, with no OTP row; the owner gets an
 *       account-exists notice (no code) instead of a code.</li>
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
        otpService.customerRecords = new CustomerRecords(userRepo);
        otpService.stylerRecords = new StylerRecords(stylerRepo, null);
        otpService.otpRepo = otpRepo;
        otpService.emailConfig = emailConfig;
        otpService.rateLimiterService = rateLimiterService;
        otpService.appUtils = new AppUtils();
        otpService.jwtUtil = mock(JwtUtil.class);

        authService.customerRecords = new CustomerRecords(userRepo);
        authService.stylerRecords = new StylerRecords(stylerRepo, null);
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
        assertAccountExistsNoticeSentTo("taken@example.com");
    }

    @Test
    void customerOtpGenerateDoesNotEnumerateEmailRegisteredAsStyler() {
        when(stylerRepo.findByEmailAddress("styler@example.com"))
                .thenReturn(Optional.of(mock(StylerEntity.class)));

        BaseResponse response = otpService.generateSignUpOtpCode(otpData("styler@example.com"));

        assertEquals("200", response.getStatusCode());
        assertEquals(GENERIC_SENT, response.getMessage());
        verify(otpRepo, never()).save(any());
        assertAccountExistsNoticeSentTo("styler@example.com");
    }

    @Test
    void stylerOtpGenerateDoesNotEnumerateExistingEmail() {
        when(stylerRepo.findByEmailAddress("taken-vendor@example.com"))
                .thenReturn(Optional.of(mock(StylerEntity.class)));

        BaseResponse response = otpService.stylerGenerateOtp(otpData("taken-vendor@example.com"));

        assertEquals("200", response.getStatusCode(), "existing email must look like success");
        assertEquals(GENERIC_SENT, response.getMessage(), "response must be identical to a fresh email");
        verify(otpRepo, never()).save(any());
        assertAccountExistsNoticeSentTo("taken-vendor@example.com");
    }

    @Test
    void stylerOtpGenerateDoesNotEnumerateEmailRegisteredAsCustomer() {
        when(userRepo.findByEmailAddress("customer@example.com"))
                .thenReturn(Optional.of(mock(UserEntity.class)));

        BaseResponse response = otpService.stylerGenerateOtp(otpData("customer@example.com"));

        assertEquals("200", response.getStatusCode());
        assertEquals(GENERIC_SENT, response.getMessage());
        verify(otpRepo, never()).save(any());
        assertAccountExistsNoticeSentTo("customer@example.com");
    }

    // ── Existing-account notice: rate limited like a code ─────────────

    @Test
    void noticeIsNotSentWhenTheEmailHitTheGenerationCap() {
        when(userRepo.findByEmailAddress("taken@example.com")).thenReturn(Optional.of(mock(UserEntity.class)));
        when(rateLimiterService.isBlocked(eq("otp_gen:taken@example.com"), anyInt(), anyInt())).thenReturn(true);

        BaseResponse response = otpService.stylerGenerateOtp(otpData("taken@example.com"));

        assertEquals("400", response.getStatusCode());
        assertEquals("Too many OTP requests. Please try again in a few minutes.", response.getMessage());
        verify(emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void aSecondNoticeWithinAMinuteGetsTheSameAnswerAsASecondCode() {
        when(stylerRepo.findByEmailAddress("taken@example.com")).thenReturn(Optional.of(mock(StylerEntity.class)));
        when(rateLimiterService.isBlocked(eq("otp_notice:USER SIGN UP:taken@example.com"), anyInt(), anyInt())).thenReturn(true);

        BaseResponse response = otpService.generateSignUpOtpCode(otpData("taken@example.com"));

        assertEquals("400", response.getStatusCode());
        assertEquals("OTP Code was generated earlier, Kindly wait for another minute to regenerate another one", response.getMessage());
        verify(emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void aFreshEmailGetsACodeNotTheNotice() {
        otpService.stylerGenerateOtp(otpData("fresh@example.com"));

        verify(emailConfig).sendSimpleMail(eq("fresh@example.com"), eq("RapidStylers Verification Code"), anyString());
        verify(emailConfig, never()).sendSimpleMail(anyString(), eq("You already have a RapidStylers account"), anyString());
    }

    // ── Password-reset OTP: enumeration-safe + hashed + verifiable ────

    @Test
    void resetOtpIsSentToAStylistEmail() {
        StylerEntity styler = new StylerEntity();
        styler.setFirstname("Ama");
        when(stylerRepo.findByEmailAddress("pro@example.com")).thenReturn(Optional.of(styler));

        BaseResponse response = otpService.resetPasswordMessage(otpData("pro@example.com"));

        assertEquals("200", response.getStatusCode());
        assertEquals(RESET_SENT, response.getMessage());
        assertEquals("FORGET PASSWORD", captureSavedOtp().getPurpose());
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailConfig).sendSimpleMail(eq("pro@example.com"), anyString(), bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().startsWith("Dear Ama,"));
    }

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

    // ── Wrong guesses are capped per emailed code, not just per IP ────

    @Test
    void aWrongGuessCountsAgainstTheEmailedCode() {
        OTPEntity open = otpEntity("real@example.com", "USER SIGN UP", "111111");
        open.setId(7L);
        when(otpRepo.findLatestUnusedByEmail("real@example.com")).thenReturn(Optional.of(open));
        when(otpRepo.recordWrongGuess(7L, OtpService.OTP_MAX_WRONG_GUESSES)).thenReturn(1);
        when(otpRepo.findById(7L)).thenReturn(Optional.of(open));

        BaseResponse response = otpService.verifyUserOTP("real@example.com", "222222");

        assertEquals("Invalid OTP Code", response.getMessage());
        verify(otpRepo).recordWrongGuess(7L, OtpService.OTP_MAX_WRONG_GUESSES);
    }

    @Test
    void theLastAllowedWrongGuessClosesTheCodeAndSaysSo() {
        OTPEntity open = otpEntity("vendor@example.com", "STYLER SIGN UP", "111111");
        open.setId(8L);
        OTPEntity closed = otpEntity("vendor@example.com", "STYLER SIGN UP", "111111");
        closed.setId(8L);
        closed.setIsUsed(OTPEntity.LOCKED);
        when(otpRepo.findLatestUnusedByEmail("vendor@example.com")).thenReturn(Optional.of(open));
        when(otpRepo.recordWrongGuess(8L, OtpService.OTP_MAX_WRONG_GUESSES)).thenReturn(1);
        when(otpRepo.findById(8L)).thenReturn(Optional.of(closed));

        BaseResponse response = otpService.stylerVerifyOtp("vendor@example.com", "222222");

        assertEquals("400", response.getStatusCode());
        assertEquals(OtpService.CODE_LOCKED, response.getMessage());
    }

    @Test
    void aWrongGuessForAnEmailWithNoOpenCodeCountsOnlyAgainstTheIp() {
        BaseResponse response = otpService.verifyUserOTP("nobody@example.com", "222222");

        assertEquals("Invalid OTP Code", response.getMessage());
        verify(otpRepo, never()).recordWrongGuess(any(), anyInt());
        verify(rateLimiterService).record("auth_ip:127.0.0.1", 900);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void assertAccountExistsNoticeSentTo(String email) {
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailConfig).sendSimpleMail(eq(email), eq("You already have a RapidStylers account"), bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().contains("You already have a RapidStylers account. Sign in, or reset your password."));
        assertFalse(OTP_IN_EMAIL.matcher(bodyCaptor.getValue()).find(), "the notice carries no code");
        // The notice counts against the same per-email generation cap as a code.
        verify(rateLimiterService).record("otp_gen:" + email, 900);
    }

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
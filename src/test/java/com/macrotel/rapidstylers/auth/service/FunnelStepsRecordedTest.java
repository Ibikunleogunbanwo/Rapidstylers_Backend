package com.macrotel.rapidstylers.auth.service;

import com.macrotel.rapidstylers.auth.entity.OTPEntity;
import com.macrotel.rapidstylers.auth.pojo.OTPData;
import com.macrotel.rapidstylers.auth.repo.OTPRepo;
import com.macrotel.rapidstylers.common.service.FunnelService;
import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The sign-up code steps tick the funnel: started when a code goes out, verified when it is entered. */
class FunnelStepsRecordedTest {

    private OtpService otpService;
    private UserRepo userRepo;
    private OTPRepo otpRepo;
    private FunnelService funnel;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepo.class);
        StylerRepo stylerRepo = mock(StylerRepo.class);
        otpRepo = mock(OTPRepo.class);
        RateLimiterService limiter = mock(RateLimiterService.class);
        funnel = mock(FunnelService.class);
        otpService = new OtpService();
        otpService.customerRecords = new CustomerRecords(userRepo);
        otpService.stylerRecords = new StylerRecords(stylerRepo, null);
        otpService.otpRepo = otpRepo;
        otpService.emailConfig = mock(EmailConfig.class);
        otpService.rateLimiterService = limiter;
        otpService.appUtils = new AppUtils();
        otpService.jwtUtil = mock(JwtUtil.class);
        otpService.funnelService = funnel;
        when(limiter.isBlocked(anyString(), anyInt(), anyInt())).thenReturn(false);
        when(limiter.clientIp()).thenReturn("127.0.0.1");
        when(userRepo.findByEmailAddress(anyString())).thenReturn(Optional.empty());
        when(stylerRepo.findByEmailAddress(anyString())).thenReturn(Optional.empty());
        when(otpRepo.checkSignUpValidityOtp(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void sendingACodeToANewEmailStartsSignUp() {
        otpService.generateSignUpOtpCode(otpData("new@example.com"));
        otpService.stylerGenerateOtp(otpData("vendor@example.com"));
        verify(funnel).record(FunnelService.SIGNUP_STARTED, "CUSTOMER", "new@example.com");
        verify(funnel).record(FunnelService.SIGNUP_STARTED, "STYLIST", "vendor@example.com");
    }

    @Test
    void aRegisteredEmailDoesNotCount() {
        when(userRepo.findByEmailAddress("old@example.com")).thenReturn(Optional.of(new UserEntity()));
        otpService.generateSignUpOtpCode(otpData("old@example.com"));
        verifyNoInteractions(funnel);
    }

    @Test
    void enteringTheRightCodeVerifiesTheEmail() {
        when(otpRepo.findLatestUnusedByEmail("real@example.com"))
                .thenReturn(Optional.of(otp("real@example.com", "USER SIGN UP", "123456")));
        otpService.verifyUserOTP("real@example.com", "123456");
        verify(funnel).record(FunnelService.EMAIL_VERIFIED, "CUSTOMER", "real@example.com");

        when(otpRepo.findLatestUnusedByEmail("vendor@example.com"))
                .thenReturn(Optional.of(otp("vendor@example.com", "STYLER SIGN UP", "654321")));
        otpService.stylerVerifyOtp("vendor@example.com", "654321");
        verify(funnel).record(FunnelService.EMAIL_VERIFIED, "STYLIST", "vendor@example.com");
    }

    @Test
    void aWrongCodeOrAPasswordResetDoesNotCount() {
        when(otpRepo.findLatestUnusedByEmail("real@example.com"))
                .thenReturn(Optional.of(otp("real@example.com", "USER SIGN UP", "123456")));
        otpService.verifyUserOTP("real@example.com", "000000");
        when(otpRepo.findLatestUnusedByEmail("reset@example.com"))
                .thenReturn(Optional.of(otp("reset@example.com", "FORGET PASSWORD", "123456")));
        otpService.verifyUserOTP("reset@example.com", "123456");
        verifyNoInteractions(funnel);
    }

    private static OTPData otpData(String email) {
        OTPData data = new OTPData();
        data.setEmailAddress(email);
        return data;
    }

    private static OTPEntity otp(String email, String purpose, String rawCode) {
        OTPEntity entity = new OTPEntity();
        entity.setEmailAddress(email);
        entity.setPurpose(purpose);
        entity.setCode(new AppUtils().hashOtp(rawCode));
        return entity;
    }
}

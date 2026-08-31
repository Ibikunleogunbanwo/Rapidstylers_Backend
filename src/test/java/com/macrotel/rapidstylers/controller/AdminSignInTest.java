package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.SignInData;
import com.macrotel.rapidstylers.repo.AdminAccountRepo;
import com.macrotel.rapidstylers.security.JwtUtil;
import com.macrotel.rapidstylers.service.RateLimiterService;
import com.macrotel.rapidstylers.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminSignInTest {

    private AdminAuthController controller;
    private AdminAccountRepo adminAccountRepo;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;

    private static final String ADMIN_EMAIL = "admin@rapidstylers.com";
    private static final String ADMIN_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"; // bcrypt("secret")

    @BeforeEach
    void setUp() {
        controller = new AdminAuthController();
        adminAccountRepo = mock(AdminAccountRepo.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtil = mock(JwtUtil.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);

        when(rateLimiterService.clientIp()).thenReturn("127.0.0.1");
        when(rateLimiterService.isBlocked(anyString(), anyInt(), anyInt())).thenReturn(false);

        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        when(refreshTokenService.issue(anyString(), anyString())).thenReturn("admin-refresh");

        ReflectionTestUtils.setField(controller, "adminAccountRepo", adminAccountRepo);
        ReflectionTestUtils.setField(controller, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(controller, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(controller, "rateLimiterService", rateLimiterService);
        ReflectionTestUtils.setField(controller, "refreshTokenService", refreshTokenService);
    }

    private AdminAccountEntity account(boolean enabled) {
        AdminAccountEntity a = new AdminAccountEntity();
        a.setEmail(ADMIN_EMAIL);
        a.setPasswordHash(ADMIN_HASH);
        a.setEnabled(enabled);
        a.setRole("ADMIN");
        return a;
    }

    private SignInData credentials(String email, String password) {
        SignInData d = new SignInData();
        d.setEmailAddress(email);
        d.setPassword(password);
        return d;
    }

    @Test
    void validCredentialsIssueAdminToken() {
        when(adminAccountRepo.findByEmailIgnoreCase(ADMIN_EMAIL)).thenReturn(Optional.of(account(true)));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtUtil.generateToken(anyString(), anyString())).thenReturn("admin-jwt");

        ResponseEntity<BaseResponse> res = controller.adminSignIn(credentials(ADMIN_EMAIL, "secret"));
        assertEquals("200", res.getBody().getStatusCode());
        assertEquals("admin-jwt", res.getBody().getToken());
        assertEquals("admin-refresh", res.getBody().getRefreshToken());
    }

    @Test
    void wrongPasswordRejected() {
        when(adminAccountRepo.findByEmailIgnoreCase(ADMIN_EMAIL)).thenReturn(Optional.of(account(true)));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        ResponseEntity<BaseResponse> res = controller.adminSignIn(credentials(ADMIN_EMAIL, "wrong"));
        assertEquals("400", res.getBody().getStatusCode());
        assertNull(res.getBody().getToken());
    }

    @Test
    void disabledAdminRejected() {
        when(adminAccountRepo.findByEmailIgnoreCase(ADMIN_EMAIL)).thenReturn(Optional.of(account(false)));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        ResponseEntity<BaseResponse> res = controller.adminSignIn(credentials(ADMIN_EMAIL, "secret"));
        assertEquals("400", res.getBody().getStatusCode());
        assertNull(res.getBody().getToken());
    }

    @Test
    void unknownAdminRejected() {
        when(adminAccountRepo.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        ResponseEntity<BaseResponse> res = controller.adminSignIn(credentials("nobody@example.com", "secret"));
        assertEquals("400", res.getBody().getStatusCode());
        assertNotNull(res.getBody());
    }
}
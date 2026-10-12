package com.macrotel.rapidstylers.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.admin.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.admin.repo.AdminAccountRepo;
import com.macrotel.rapidstylers.auth.pojo.SignInData;
import com.macrotel.rapidstylers.auth.service.RefreshTokenService;
import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.security.verifier.TurnstileVerifier;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSignInTest {

    private AdminAuthController controller;
    private AdminAccountRepo adminAccountRepo;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private RateLimiterService rateLimiterService;
    private RefreshTokenService refreshTokenService;

    private static final String ADMIN_EMAIL = "admin@rapidstylers.com";
    private static final String ADMIN_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"; // bcrypt("secret")

    @BeforeEach
    void setUp() {
        controller = new AdminAuthController();
        adminAccountRepo = mock(AdminAccountRepo.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtil = mock(JwtUtil.class);
        rateLimiterService = mock(RateLimiterService.class);

        when(rateLimiterService.clientIp()).thenReturn("127.0.0.1");
        when(rateLimiterService.isBlocked(anyString(), anyInt(), anyInt())).thenReturn(false);

        refreshTokenService = mock(RefreshTokenService.class);
        when(refreshTokenService.issue(anyString(), anyString())).thenReturn("admin-refresh");

        ReflectionTestUtils.setField(controller, "adminAccountRepo", adminAccountRepo);
        ReflectionTestUtils.setField(controller, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(controller, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(controller, "rateLimiterService", rateLimiterService);
        ReflectionTestUtils.setField(controller, "refreshTokenService", refreshTokenService);
        // Bot protection disabled (no secret), matching a deployment that has not
        // configured Turnstile yet. The enabled case is covered below.
        ReflectionTestUtils.setField(controller, "turnstileVerifier", new TurnstileVerifier(
                "", "http://127.0.0.1:1/siteverify", new ObjectMapper()));
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
    void ownerTokenAndRefreshCarryAdmin() {
        when(adminAccountRepo.findByEmailIgnoreCase(ADMIN_EMAIL)).thenReturn(Optional.of(account(true)));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtUtil.generateToken(ADMIN_EMAIL, "ADMIN")).thenReturn("owner-jwt");

        ResponseEntity<BaseResponse> res = controller.adminSignIn(credentials(ADMIN_EMAIL, "secret"));

        assertEquals("owner-jwt", res.getBody().getToken());
        assertEquals("ADMIN", ((java.util.Map<?, ?>) res.getBody().getData()).get("role"));
        verify(refreshTokenService).issue(ADMIN_EMAIL, "ADMIN");
    }

    @Test
    void managerTokenAndRefreshCarryManager() {
        AdminAccountEntity manager = account(true);
        manager.setRole("MANAGER");
        when(adminAccountRepo.findByEmailIgnoreCase(ADMIN_EMAIL)).thenReturn(Optional.of(manager));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtUtil.generateToken(ADMIN_EMAIL, "MANAGER")).thenReturn("manager-jwt");

        ResponseEntity<BaseResponse> res = controller.adminSignIn(credentials(ADMIN_EMAIL, "secret"));

        assertEquals("200", res.getBody().getStatusCode());
        assertEquals("manager-jwt", res.getBody().getToken());
        assertEquals("MANAGER", ((java.util.Map<?, ?>) res.getBody().getData()).get("role"));
        // The refresh token keeps the role, so a renewed access token is still MANAGER.
        verify(refreshTokenService).issue(ADMIN_EMAIL, "MANAGER");
        verify(jwtUtil, never()).generateToken(ADMIN_EMAIL, "ADMIN");
    }

    @Test
    void meReportsTheAccountsRole() {
        AdminAccountEntity manager = account(true);
        manager.setRole("MANAGER");
        when(adminAccountRepo.findByEmailIgnoreCase(ADMIN_EMAIL)).thenReturn(Optional.of(manager));
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute("accountId", ADMIN_EMAIL);
        request.setAttribute("role", "MANAGER");

        ResponseEntity<BaseResponse> res = controller.adminMe(request);

        assertEquals("200", res.getBody().getStatusCode());
        assertEquals("MANAGER", ((java.util.Map<?, ?>) res.getBody().getData()).get("role"));
        assertEquals(ADMIN_EMAIL, ((java.util.Map<?, ?>) res.getBody().getData()).get("email"));
    }

    @Test
    void meRefusesNonAdminAndDisabledAccounts() {
        org.springframework.mock.web.MockHttpServletRequest customer = new org.springframework.mock.web.MockHttpServletRequest();
        customer.setAttribute("accountId", "c1");
        customer.setAttribute("role", "CUSTOMER");
        assertEquals(401, controller.adminMe(customer).getStatusCodeValue());

        when(adminAccountRepo.findByEmailIgnoreCase(ADMIN_EMAIL)).thenReturn(Optional.of(account(false)));
        org.springframework.mock.web.MockHttpServletRequest disabled = new org.springframework.mock.web.MockHttpServletRequest();
        disabled.setAttribute("accountId", ADMIN_EMAIL);
        disabled.setAttribute("role", "ADMIN");
        assertEquals(401, controller.adminMe(disabled).getStatusCodeValue());
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

    /**
     * With bot protection configured, a failed challenge stops the attempt before
     * the password check — and, importantly, before the failed-attempt counters,
     * so a bot cannot lock out a real admin by guessing and failing.
     */
    @Test
    void failedChallengeRejectedBeforeThePasswordCheck() {
        ReflectionTestUtils.setField(controller, "turnstileVerifier", new TurnstileVerifier(
                "secret-key", "http://127.0.0.1:1/siteverify", new ObjectMapper()));

        ResponseEntity<BaseResponse> res = controller.adminSignIn(credentials(ADMIN_EMAIL, "secret"));

        assertEquals("400", res.getBody().getStatusCode());
        assertNull(res.getBody().getToken());
        // Never reached the credential store or the lockout counters.
        verify(adminAccountRepo, never()).findByEmailIgnoreCase(anyString());
        verify(rateLimiterService, never()).record(anyString(), anyInt());
    }

    @Test
    void unknownAdminRejected() {
        when(adminAccountRepo.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        ResponseEntity<BaseResponse> res = controller.adminSignIn(credentials("nobody@example.com", "secret"));
        assertEquals("400", res.getBody().getStatusCode());
        assertNotNull(res.getBody());
    }
}
package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.SignInData;
import com.macrotel.rapidstylers.repo.AdminAccountRepo;
import com.macrotel.rapidstylers.security.JwtUtil;
import com.macrotel.rapidstylers.service.LoginAttemptService;
import com.macrotel.rapidstylers.service.RateLimiterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static com.macrotel.rapidstylers.config.AppConstants.ERROR_STATUS_CODE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_MESSAGE;

/**
 * Admin authentication, backed by admin_accounts rows (BCrypt password hashes,
 * seeded from ADMIN_EMAIL/ADMIN_PASSWORD on first boot). A successful sign-in
 * issues a JWT with the ADMIN role, which JwtAuthFilter requires on admin-only
 * endpoints and method-level @PreAuthorize also enforces.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class AdminAuthController {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private AdminAccountRepo adminAccountRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final int AUTH_WINDOW_SECONDS = 900;   // 15 min
    private static final int AUTH_MAX_FAILURES = 5;       // per email
    private static final int AUTH_IP_MAX_FAILURES = 20;   // per IP

    @PostMapping("/admin_sign_in")
    public ResponseEntity<BaseResponse> adminSignIn(@Valid @RequestBody SignInData signInData) {
        BaseResponse response = new BaseResponse();
        String emailAddress = signInData.getEmailAddress();
        String ip = rateLimiterService.clientIp();
        // Shared global lockout — failed logins anywhere count against this email/IP.
        if (rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                || rateLimiterService.isBlocked("auth_ip:" + ip, AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Too many failed attempts. Please try again later.");
            response.setData(new Object[0]);
            recordLoginFailure(emailAddress, ip, "LOCKED_OUT");
            return ResponseEntity.ok(response);
        }
        Optional<AdminAccountEntity> existing = adminAccountRepo.findByEmailIgnoreCase(emailAddress);
        boolean valid = existing.isPresent()
                && existing.get().isEnabled()
                && passwordEncoder.matches(signInData.getPassword(), existing.get().getPasswordHash());
        if (!valid) {
            rateLimiterService.record("auth:" + emailAddress, AUTH_WINDOW_SECONDS);
            rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
            recordLoginFailure(emailAddress, ip, "INVALID_CREDENTIALS");
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Invalid admin credentials");
            response.setData(new Object[0]);
            return ResponseEntity.ok(response);
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("role", "ADMIN");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setToken(jwtUtil.generateToken(existing.get().getEmail(), "ADMIN"));
            response.setData(data);
            rateLimiterService.clear("auth:" + emailAddress);
            rateLimiterService.clear("auth_ip:" + ip);
            if (loginAttemptService != null) {
                loginAttemptService.recordSuccess("ADMIN", existing.get().getEmail(), emailAddress, ip, RateLimiterService.userAgent());
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new BaseResponse(true));
        }
        return ResponseEntity.ok(response);
    }

    private void recordLoginFailure(String emailAddress, String ip, String reason) {
        if (loginAttemptService != null) {
            loginAttemptService.recordFailure("ADMIN", "admin", emailAddress, ip, RateLimiterService.userAgent(), reason);
        }
    }
}

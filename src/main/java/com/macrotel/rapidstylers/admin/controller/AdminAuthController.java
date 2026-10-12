package com.macrotel.rapidstylers.admin.controller;

import com.macrotel.rapidstylers.admin.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.admin.repo.AdminAccountRepo;
import com.macrotel.rapidstylers.auth.pojo.SignInData;
import com.macrotel.rapidstylers.auth.service.LoginAttemptService;
import com.macrotel.rapidstylers.auth.service.RefreshTokenService;
import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.security.access.Roles;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.security.verifier.TurnstileVerifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.macrotel.rapidstylers.config.AppConstants.ERROR_STATUS_CODE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_MESSAGE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * Admin authentication, backed by admin_accounts rows (BCrypt password hashes,
 * seeded from ADMIN_EMAIL/ADMIN_PASSWORD on first boot). A successful sign-in
 * issues a JWT with the account's role: ADMIN (an owner, every admin route) or
 * MANAGER (the day-to-day routes in Routes.STAFF). The Routes rules and
 * method-level @PreAuthorize enforce the difference.
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

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private TurnstileVerifier turnstileVerifier;

    private static final int AUTH_WINDOW_SECONDS = 900;   // 15 min
    private static final int AUTH_MAX_FAILURES = 5;       // per email
    private static final int AUTH_IP_MAX_FAILURES = 20;   // per IP

    @PostMapping("/admin_sign_in")
    public ResponseEntity<BaseResponse> adminSignIn(@Valid @RequestBody SignInData signInData) {
        BaseResponse response = new BaseResponse();
        String emailAddress = signInData.getEmailAddress();
        String ip = rateLimiterService.clientIp();
        // Bot protection first: the admin console is the highest-value target,
        // and the challenge keeps automated attempts from spending the lockout
        // budget below. A no-op unless TURNSTILE_SECRET_KEY is configured.
        if (!turnstileVerifier.verify(signInData.getCaptchaToken(), ip)) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Please complete the verification challenge and try again.");
            response.setData(new Object[0]);
            return ApiResponses.respond(response);
        }
        // Shared global lockout — failed logins anywhere count against this email/IP.
        if (rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                || rateLimiterService.isBlocked("auth_ip:" + ip, AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Too many failed attempts. Please try again later.");
            response.setData(new Object[0]);
            recordLoginFailure(emailAddress, ip, "LOCKED_OUT");
            return ApiResponses.respond(response);
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
            return ApiResponses.respond(response);
        }
        try {
            // The token carries the account's own role: ADMIN (an owner) or MANAGER.
            String role = Roles.adminAccountRole(existing.get().getRole());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("role", role);
            data.put("email", existing.get().getEmail());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setToken(jwtUtil.generateToken(existing.get().getEmail(), role));
            // DB-admin sessions get a rotating refresh token too, so the server-side
            // idle (30 min) + absolute (8h) admin caps apply the same way they do
            // for customers/stylers instead of a blunt 15-min hard logout.
            response.setRefreshToken(refreshTokenService.issue(existing.get().getEmail(), role));
            response.setData(data);
            rateLimiterService.clear("auth:" + emailAddress);
            rateLimiterService.clear("auth_ip:" + ip);
            if (loginAttemptService != null) {
                loginAttemptService.recordSuccess("ADMIN", existing.get().getEmail(), emailAddress, ip, RateLimiterService.userAgent());
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new BaseResponse(true));
        }
        return ApiResponses.respond(response);
    }

    /**
     * Who is signed in to the admin console, so the website can show an owner or
     * a manager the screens they may use. The role is the account's current one;
     * a role change signs the account out everywhere, so it matches the token.
     */
    @GetMapping("/admin/me")
    public ResponseEntity<BaseResponse> adminMe(HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        AdminAccountEntity account = adminAccountRepo.findByEmailIgnoreCase(adminId)
                .filter(AdminAccountEntity::isEnabled).orElse(null);
        if (account == null) return CurrentAccount.unauthorized();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", account.getEmail());
        data.put("role", Roles.adminAccountRole(account.getRole()));
        BaseResponse response = new BaseResponse();
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return ApiResponses.respond(response);
    }

    private void recordLoginFailure(String emailAddress, String ip, String reason) {
        if (loginAttemptService != null) {
            loginAttemptService.recordFailure("ADMIN", "admin", emailAddress, ip, RateLimiterService.userAgent(), reason);
        }
    }
}

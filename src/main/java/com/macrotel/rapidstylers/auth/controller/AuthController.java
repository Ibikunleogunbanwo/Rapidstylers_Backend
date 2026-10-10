package com.macrotel.rapidstylers.auth.controller;

import com.macrotel.rapidstylers.auth.pojo.ForgotPasswordData;
import com.macrotel.rapidstylers.auth.pojo.OTPData;
import com.macrotel.rapidstylers.auth.pojo.SignInData;
import com.macrotel.rapidstylers.auth.service.AuthService;
import com.macrotel.rapidstylers.auth.service.RefreshTokenService;
import com.macrotel.rapidstylers.auth.service.SessionActivityService;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.pojo.UserData;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.security.verifier.TurnstileVerifier;
import com.macrotel.rapidstylers.common.service.RateLimiterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.Map;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Sign-up, sign-in, passwords and sessions. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class AuthController {
    @Autowired
    AuthService authService;
    @Autowired
    RefreshTokenService refreshTokenService;
    @Autowired
    SessionActivityService sessionActivityService;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    TurnstileVerifier turnstileVerifier;
    @Autowired
    RateLimiterService rateLimiterService;

    /**
     * Bot-protection gate shared by every sign-in endpoint. Returns null when
     * the request may proceed, or the rejection to send back when the Turnstile
     * challenge failed. Centralised deliberately: a new sign-in endpoint should
     * look wrong without it, rather than silently skipping bot protection.
     *
     * A no-op when TURNSTILE_SECRET_KEY is unset — see TurnstileVerifier for why
     * enforcement is configuration-driven rather than a boot guard.
     */
    private ResponseEntity<BaseResponse> botCheckRejection(String captchaToken) {
        if (turnstileVerifier.verify(captchaToken, rateLimiterService.clientIp())) {
            return null;
        }
        BaseResponse rejected = new BaseResponse();
        rejected.setStatusCode(ERROR_STATUS_CODE);
        rejected.setMessage("Please complete the verification challenge and try again.");
        rejected.setData(EMPTY_DATA);
        return new ResponseEntity<>(rejected, HttpStatus.BAD_REQUEST);
    }

    @PostMapping("/generate_sign_up_otp_code")
    public ResponseEntity <BaseResponse> generateSignUpOtpCode(@Valid @RequestBody OTPData otpData){
        BaseResponse baseResponse = authService.generateSignUpOtpCode(otpData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/verify_otp_code")
    public ResponseEntity <BaseResponse> verifyOtpCode(@RequestBody Map<String, String> body){
        String emailAddress = body.getOrDefault("emailAddress", "");
        String otpCode = body.getOrDefault("otpCode", "");
        BaseResponse baseResponse = authService.verifyUserOTP(emailAddress, otpCode);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/create_user_account")
    public ResponseEntity <BaseResponse> createUserAccount(@Valid @RequestBody UserData userData){
        BaseResponse baseResponse = authService.userSignUp(userData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    /**
     * The unified sign-in used by the site's login page for all three roles —
     * the endpoint a credential-stuffing bot actually reaches — so it carries
     * the bot-protection gate, paired with the Turnstile widget on that page.
     */
    @PostMapping("/sign_in")
    public ResponseEntity <BaseResponse> signIn(@Valid @RequestBody SignInData signInData){
        ResponseEntity<BaseResponse> rejected = botCheckRejection(signInData.getCaptchaToken());
        if (rejected != null) {
            return rejected;
        }
        BaseResponse baseResponse = authService.signIn(signInData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/google_sign_in")
    public ResponseEntity<BaseResponse> googleSignIn(@RequestBody Map<String, String> body){
        String idToken = body.getOrDefault("idToken", "");
        if(idToken.trim().isEmpty()){
            BaseResponse bad = new BaseResponse();
            bad.setStatusCode(ERROR_STATUS_CODE);
            bad.setMessage("Google ID token is required");
            bad.setData(EMPTY_DATA);
            return new ResponseEntity<>(bad, HttpStatus.BAD_REQUEST);
        }
        // Deliberately NOT bot-gated. This path requires a Google ID token whose
        // signature, issuer, audience and email_verified are verified in
        // GoogleTokenVerifier, so the caller is already cryptographically
        // attested — a challenge here would add friction to real sign-ins
        // without removing any bot traffic that password guessing represents.
        BaseResponse baseResponse = authService.signInWithGoogle(idToken);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/generate_reset_password_token")
    public ResponseEntity <BaseResponse> resetPasswordToken(@Valid @RequestBody OTPData otpData){
        BaseResponse baseResponse = authService.resetPasswordMessage(otpData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/reset_user_password")
    public ResponseEntity <BaseResponse> resetUserPassword(@Valid @RequestBody ForgotPasswordData forgotPasswordData){
        BaseResponse baseResponse = authService.resetPassword(forgotPasswordData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/update_user_password")
    public ResponseEntity <BaseResponse> updateUserPassword(@Valid @RequestBody ForgotPasswordData forgotPasswordData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = authService.updateUserPassword(forgotPasswordData, accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/styler_generate_otp")
    public ResponseEntity<BaseResponse> stylerGenerateOtp(@Valid @RequestBody OTPData otpData){
        BaseResponse baseResponse = authService.stylerGenerateOtp(otpData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/styler_verify_otp")
    public ResponseEntity<BaseResponse> stylerVerifyOtp(@RequestBody Map<String, String> body){
        String emailAddress = body.getOrDefault("emailAddress", "");
        String otpCode = body.getOrDefault("otpCode", "");
        BaseResponse baseResponse = authService.stylerVerifyOtp(emailAddress, otpCode);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler_sign_out")
    public ResponseEntity <BaseResponse> stylerSignOut(HttpServletRequest request){
        String stylerId = CurrentAccount.id(request);
        if(stylerId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = authService.stylerLogOut(stylerId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<BaseResponse> refreshToken(@RequestBody Map<String, String> body) {
        BaseResponse response = new BaseResponse();
        String refreshTokenValue = body.get("refreshToken");
        if (refreshTokenValue == null || refreshTokenValue.trim().isEmpty()) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Refresh token is required");
            return ResponseEntity.badRequest().body(response);
        }
        // Validate the old token first. A known-but-revoked token (rotated out or
        // logged out) must never be presented again — replaying one is the classic
        // stolen-session signal, so burn its entire family before rejecting. Unknown
        // or merely-expired tokens just get the plain rejection below.
        var tokenEntity = refreshTokenService.validate(refreshTokenValue);
        if (tokenEntity == null) {
            refreshTokenService.burnFamilyForReplayedToken(refreshTokenValue);
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Invalid or expired refresh token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
        // Server-side session policy, enforced at the re-auth boundary so it can't
        // be bypassed by silent access-token renewal: (1) idle past the role window,
        // or (2) the session outlived its absolute lifetime (admin's hard cap). In
        // either case terminate every tab/device by revoking the whole refresh-token
        // family and return 401 so the client re-prompts login.
        if (sessionActivityService.isIdle(tokenEntity.getAccountId(), tokenEntity.getRole())
                || sessionActivityService.absoluteExpired(tokenEntity.getAccountId(), tokenEntity.getRole())) {
            sessionActivityService.clear(tokenEntity.getAccountId());
            refreshTokenService.revokeAllForAccount(tokenEntity.getAccountId(), tokenEntity.getRole());
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Session expired. Please sign in again.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
        // Rotate: revoke old, issue new in same family
        String newRefreshToken = refreshTokenService.rotate(refreshTokenValue);
        if (newRefreshToken == null) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Refresh token has been revoked");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
        // Issue a new short-lived access token
        String newAccessToken = jwtUtil.generateToken(tokenEntity.getAccountId(), tokenEntity.getRole());
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setToken(newAccessToken);
        response.setRefreshToken(newRefreshToken);
        return ApiResponses.respond(response);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<BaseResponse> logout(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String accountId = CurrentAccount.id(request);
        String role = CurrentAccount.role(request);
        BaseResponse response = new BaseResponse();
        if (accountId == null) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Authentication required");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
        String refreshTokenValue = body.get("refreshToken");
        if (refreshTokenValue != null && !refreshTokenValue.trim().isEmpty()) {
            refreshTokenService.revoke(refreshTokenValue);
        }
        refreshTokenService.revokeAllForAccount(accountId, role);
        if (accountId != null) {
            sessionActivityService.clear(accountId);
        }
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage("Logged out successfully");
        return ApiResponses.respond(response);
    }
}

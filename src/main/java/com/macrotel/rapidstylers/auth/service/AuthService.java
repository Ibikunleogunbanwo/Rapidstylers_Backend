package com.macrotel.rapidstylers.auth.service;

import com.macrotel.rapidstylers.auth.entity.OTPEntity;
import com.macrotel.rapidstylers.auth.pojo.ForgotPasswordData;
import com.macrotel.rapidstylers.auth.pojo.SignInData;
import com.macrotel.rapidstylers.auth.repo.OTPRepo;
import com.macrotel.rapidstylers.common.service.DTOService;
import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.pojo.UserData;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.engagement.entity.NotificationEntity;
import com.macrotel.rapidstylers.engagement.service.EngagementRecords;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.payment.entity.CardDetailsEntity;
import com.macrotel.rapidstylers.payment.service.CardRecords;
import com.macrotel.rapidstylers.security.access.Roles;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.security.verifier.GoogleTokenVerifier;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Who you are: customer sign-up, sign-in (password and Google), password reset
 * and change, and stylist sign-out. The email codes (sign-up and reset) live in
 * OtpService.
 */
@Service
public class AuthService {
    AppUtils appUtils = new AppUtils();
    private static final Logger LOG = Logger.getLogger(AuthService.class.getName());
    @Autowired
    OTPRepo otpRepo;
    @Autowired
    CustomerRecords customerRecords;
    @Autowired
    StylerRecords stylerRecords;
    @Autowired
    CardRecords cardRecords;
    @Autowired
    EngagementRecords engagementRecords;
    @Autowired
    DTOService dtoService;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    RefreshTokenService refreshTokenService;
    @Autowired
    GoogleTokenVerifier googleTokenVerifier;
    @Autowired
    RateLimiterService rateLimiterService;
    @Autowired
    LoginAttemptService loginAttemptService;
    @Autowired
    OutboxEventService outboxEventService;
    @Autowired
    AccountIds accountIds;

    // Global rate-limit budgets (shared across OTP generation, OTP verification
    // and login so failures on one surface lock out the others).
    private static final int AUTH_WINDOW_SECONDS = 900;   // 15 min
    private static final int AUTH_MAX_FAILURES = 5;       // per email
    private static final int AUTH_IP_MAX_FAILURES = 20;   // per IP

    /**
     * Too many failed sign-ins or code checks for this email, or from this
     * caller's IP: the account flows refuse until the window passes.
     */
    public boolean isLockedOut(String emailAddress) {
        return rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                || rateLimiterService.isBlocked("auth_ip:" + rateLimiterService.clientIp(), AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES);
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse userSignUp(UserData userData){
        BaseResponse response = new BaseResponse(true);
        try{
            if (!userData.isAgreeToTerms()) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You must agree to the Terms and Conditions");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Locked-out email (failed logins/verifies) cannot create an account.
            if (rateLimiterService.isBlocked("auth:" + userData.getEmailAddress(), AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                    || rateLimiterService.isBlocked("auth_ip:" + rateLimiterService.clientIp(), AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Verify if user verify email address or not
            Optional<OTPEntity> verifyUserEmail = otpRepo.verifyOtpSuccess(userData.getEmailAddress());
            if(verifyUserEmail.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Email Address is yet to be verified");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Check if the email already exist in EITHER table
            Optional<UserEntity> isUserExist = customerRecords.findByEmailAddress(userData.getEmailAddress());
            Optional<StylerEntity> isStylerExist = stylerRecords.findByEmailAddress(userData.getEmailAddress());
            if(isUserExist.isPresent() || isStylerExist.isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Email Address already exists, Kindly choose another email address");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Phone uniqueness is only relevant when a phone was provided (nullable now).
            String signupPhone = userData.getPhoneNumber();
            if (signupPhone != null && !signupPhone.isBlank()) {
                Optional<UserEntity> isPhoneExist = customerRecords.findByPhoneNumber(signupPhone);
                Optional<StylerEntity> isStylerPhoneExist = stylerRecords.findByPhoneNumber(signupPhone);
                if(isPhoneExist.isPresent() || isStylerPhoneExist.isPresent()){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Phone number already registered");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            //userId is derived from names when present; falls back to a stable
            //U<random> token so a minimal (email+password) account still gets an id.
            String fname = nullSafeName(userData.getFirstname());
            String lname = nullSafeName(userData.getLastname());
            String userId = accountIds.newCustomerId(fname, lname);
            UserEntity userEntity = new UserEntity(userData);
            userEntity.setUserId(userId);
            userEntity.setRegistrationMethod("EMAIL");
            customerRecords.save(userEntity);

            //Create a space for user in the card_details table
            CardDetailsEntity cardDetailsEntity = new CardDetailsEntity();
            cardDetailsEntity.setUserId(userId);
            cardRecords.save(cardDetailsEntity);

            //Welcome notification (in-app) that a fresh account is ready to book.
            engagementRecords.saveNotification(new NotificationEntity(userId, "", "WELCOME",
                    "Welcome to RapidStylers",
                    "Your account is ready. Complete your profile and book your first appointment."));
            //Welcome email fired through the outbox -> Kafka -> email worker.
            outboxEventService.welcomeEmail(userEntity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Account created successful");
            // Signed in straight away, in the same shape /sign_in answers with, so the
            // site needs no second sign-in call (which would need its own bot check).
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("role", Roles.CUSTOMER);
            session.put("account", dtoService.userAccountDTO(userEntity));
            response.setToken(jwtUtil.generateToken(userId, Roles.CUSTOMER));
            response.setRefreshToken(refreshTokenService.issue(userId, Roles.CUSTOMER));
            response.setData(session);
            recordLoginSuccess(Roles.CUSTOMER, userId, userData.getEmailAddress(), rateLimiterService.clientIp());
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Sign-in for stylists and customers, behind the bot check. Tries styler,
     * then customer; returns the account data with its role and a role-scoped
     * JWT so the frontend can route to the right dashboard.
     */
    public BaseResponse signIn(SignInData signInData){
        BaseResponse response = new BaseResponse(true); // local — never leak stale state on error
        try{
            String emailAddress = signInData.getEmailAddress();
            String password = signInData.getPassword();
            String ip = rateLimiterService.clientIp();

            // Global lockout: failed logins/verifies (email or IP) block sign-in.
            if (rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                    || rateLimiterService.isBlocked("auth_ip:" + ip, AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                recordLoginFailure("UNKNOWN", null, emailAddress, ip, "LOCKED_OUT");
                return response;
            }

            // Admins never sign in here: they use /admin_sign_in, which checks the
            // admin_accounts table (hashed passwords, disabled accounts, step-up).

            // 1. Styler (vendor)
            Optional<StylerEntity> styler = stylerRecords.findByEmailAddress(emailAddress);
            if(styler.isPresent() && appUtils.passwordMatches(password, styler.get().getPassword())){
                StylerEntity stylerEntity = styler.get();
                // Verification gate — rejected/suspended professionals cannot sign in.
                if(VERIFICATION_REJECTED.equals(stylerEntity.getVerificationStatus())){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Your professional account was not approved. Please contact support.");
                    response.setData(EMPTY_DATA);
                    recordLoginFailure("STYLER", stylerEntity.getStylerId(), emailAddress, ip, "ACCOUNT_REJECTED");
                    return response;
                }
                if(VERIFICATION_SUSPENDED.equals(stylerEntity.getVerificationStatus())){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Your professional account is suspended. Please contact support.");
                    response.setData(EMPTY_DATA);
                    recordLoginFailure("STYLER", stylerEntity.getStylerId(), emailAddress, ip, "ACCOUNT_SUSPENDED");
                    return response;
                }
                if(stylerEntity.getPassword().matches("^[0-9a-fA-F]{32}$")){
                    stylerEntity.setPassword(appUtils.encryptPassword(password));
                }
                stylerEntity.setIsOnline("0");
                stylerRecords.save(stylerEntity);
                Map<String, Object> stylerData = new LinkedHashMap<>();
                stylerData.put("role", "STYLER");
                stylerData.put("account", dtoService.stylerAccountDTO(stylerEntity));
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage(SUCCESS_MESSAGE);
                response.setToken(jwtUtil.generateToken(stylerEntity.getStylerId(), "STYLER"));
                response.setRefreshToken(refreshTokenService.issue(stylerEntity.getStylerId(), "STYLER"));
                response.setData(stylerData);
                rateLimiterService.clear("auth:" + emailAddress);
                rateLimiterService.clear("auth_ip:" + ip);
                recordLoginSuccess("STYLER", stylerEntity.getStylerId(), emailAddress, ip);
                return response;
            }

            // 2. Customer
            Optional<UserEntity> user = customerRecords.findByEmailAddress(emailAddress);
            if(user.isPresent() && "0".equals(user.get().getStatus())){
                UserEntity userEntity = user.get();
                // Provider binding: a Google-created account has no usable
                // password and must sign in through Google, not email/password.
                if ("GOOGLE".equals(userEntity.getRegistrationMethod())) {
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("This account uses Google sign-in. Please continue with Google.");
                    response.setData(EMPTY_DATA);
                    return response;
                }
                if (appUtils.passwordMatches(password, userEntity.getPassword())){
                if(userEntity.getPassword().matches("^[0-9a-fA-F]{32}$")){
                    userEntity.setPassword(appUtils.encryptPassword(password));
                    customerRecords.save(userEntity);
                }
                Map<String, Object> userData = new LinkedHashMap<>();
                userData.put("role", "CUSTOMER");
                userData.put("account", dtoService.userAccountDTO(userEntity));
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage(SUCCESS_MESSAGE);
                response.setToken(jwtUtil.generateToken(userEntity.getUserId(), "CUSTOMER"));
                response.setRefreshToken(refreshTokenService.issue(userEntity.getUserId(), "CUSTOMER"));
                response.setData(userData);
                rateLimiterService.clear("auth:" + emailAddress);
                rateLimiterService.clear("auth_ip:" + ip);
                recordLoginSuccess("CUSTOMER", userEntity.getUserId(), emailAddress, ip);
                    return response;
                }
            }

            // Failed login — count toward the shared lockout budget.
            rateLimiterService.record("auth:" + emailAddress, AUTH_WINDOW_SECONDS);
            rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
            recordLoginFailure(accountType(styler, user), accountId(styler, user), emailAddress, ip, "INVALID_CREDENTIALS");
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Invalid Email Address or Password");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Sign-in with a Google ID token. Verifies the token (signature, issuer,
     * audience, email_verified), then routes to the matching existing account
     * (styler or customer) or auto-creates a minimal customer when no account
     * with that verified email exists. Transactional so the welcome outbox
     * event (Propagation.MANDATORY) can be written in the same unit as the
     * account row.
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse signInWithGoogle(String idToken){
        BaseResponse response = new BaseResponse(true);
        try{
            io.jsonwebtoken.Claims claims = googleTokenVerifier.verify(idToken);
            String email = String.valueOf(claims.get("email"));
            String ip = rateLimiterService.clientIp();
            if (rateLimiterService.isBlocked("auth:" + email, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                    || rateLimiterService.isBlocked("auth_ip:" + ip, AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                recordLoginFailure("UNKNOWN", null, email, ip, "LOCKED_OUT");
                return response;
            }
            Optional<StylerEntity> styler = stylerRecords.findByEmailAddress(email);
            Optional<UserEntity> user = customerRecords.findByEmailAddress(email);

            // Google sign-in is customer-only for now. A professional email must
            // use the styler email + password sign-in surface.
            if (styler.isPresent() && user.isEmpty()) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This Google account belongs to a professional profile. Please sign in with your email and password.");
                response.setData(EMPTY_DATA);
                recordLoginFailure("STYLER", styler.get().getStylerId(), email, ip, "GOOGLE_CUSTOMER_ONLY");
                return response;
            }

            // Existing (active) customer account -> route as CUSTOMER.
            if (user.isPresent() && "0".equals(user.get().getStatus())) {
                UserEntity userEntity = user.get();
                // Provider binding: an email/password-created customer must
                // keep signing in with their email and password, not Google.
                if ("EMAIL".equals(userEntity.getRegistrationMethod())) {
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("This account was created with email and password. Please sign in with your email and password.");
                    response.setData(EMPTY_DATA);
                    return response;
                }
                rateLimiterService.clear("auth:" + email);
                rateLimiterService.clear("auth_ip:" + ip);
                Map<String, Object> userData = new LinkedHashMap<>();
                userData.put("role", "CUSTOMER");
                userData.put("account", dtoService.userAccountDTO(userEntity));
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage(SUCCESS_MESSAGE);
                response.setToken(jwtUtil.generateToken(userEntity.getUserId(), "CUSTOMER"));
                response.setRefreshToken(refreshTokenService.issue(userEntity.getUserId(), "CUSTOMER"));
                response.setData(userData);
                recordLoginSuccess("CUSTOMER", userEntity.getUserId(), email, ip);
                return response;
            }

            // A customer row in any other state (disabled, suspended) is still that
            // person's account: refuse, rather than create a second account with the
            // same email that walks around the block.
            if (user.isPresent()) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This account is not active. Please contact support.");
                response.setData(EMPTY_DATA);
                recordLoginFailure("CUSTOMER", user.get().getUserId(), email, ip, "ACCOUNT_INACTIVE");
                return response;
            }

            // No account yet -> auto-create a minimal customer from verified Google claims.
            UserData signupData = new UserData();
            signupData.setEmailAddress(email);
            signupData.setFirstname(claims.get("given_name") != null ? String.valueOf(claims.get("given_name")) : null);
            signupData.setLastname(claims.get("family_name") != null ? String.valueOf(claims.get("family_name")) : null);
            signupData.setAgreeToTerms(true);
            signupData.setPassword(appUtils.randomAlphanumeric(24)); // encrypted by the UserEntity(UserData) constructor
            UserEntity created = createMinimalCustomer(signupData);
            rateLimiterService.clear("auth:" + email);
            rateLimiterService.clear("auth_ip:" + ip);
            Map<String, Object> newUserData = new LinkedHashMap<>();
            newUserData.put("role", "CUSTOMER");
            newUserData.put("account", dtoService.userAccountDTO(created));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setToken(jwtUtil.generateToken(created.getUserId(), "CUSTOMER"));
            response.setRefreshToken(refreshTokenService.issue(created.getUserId(), "CUSTOMER"));
            response.setData(newUserData);
            recordLoginSuccess("CUSTOMER", created.getUserId(), email, ip);
            return response;
        }
        catch (GoogleTokenVerifier.IdTokenInvalidException e){
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage(e.getMessage());
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning("google sign-in error: " + ex.getMessage());
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Google sign-in failed. Please try again.");
            response.setData(EMPTY_DATA);
        }
        return response;
    }

    private UserEntity createMinimalCustomer(UserData signupData){
        String fname = nullSafeName(signupData.getFirstname());
        String lname = nullSafeName(signupData.getLastname());
        String userId = accountIds.newCustomerId(fname, lname);
        UserEntity userEntity = new UserEntity(signupData);
        userEntity.setUserId(userId);
        userEntity.setRegistrationMethod("GOOGLE");
        customerRecords.save(userEntity);
        CardDetailsEntity cardDetailsEntity = new CardDetailsEntity();
        cardDetailsEntity.setUserId(userId);
        cardRecords.save(cardDetailsEntity);
        engagementRecords.saveNotification(new NotificationEntity(userId, "", "WELCOME",
                "Welcome to RapidStylers",
                "Your account is ready. Complete your profile and book your first appointment."));
        outboxEventService.welcomeEmail(userEntity);
        return userEntity;
    }

    public BaseResponse resetPassword(ForgotPasswordData forgotPasswordData){
        BaseResponse response = new BaseResponse(true);
        try{
            String password = forgotPasswordData.getPassword();
            String confirmPassword = forgotPasswordData.getConfirmPassword();
            String emailAddress = forgotPasswordData.getEmailAddress();
            if(!password.equals(confirmPassword)){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("The entered password does not match the confirmed password. Please ensure both passwords are identical.");
                response.setData(EMPTY_DATA);
                return response;
            }
            String encryptPassword = appUtils.encryptPassword(password);
            Optional<UserEntity> getUserData = customerRecords.findByEmailAddress(emailAddress);
            if(getUserData.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Email Address, Kindly create account");
                response.setData(EMPTY_DATA);
                return response;
            }

            // SECURITY: Require a verified "FORGET PASSWORD" OTP before allowing the reset.
            // Without this check, anyone who knows an email address could reset the password.
            Optional<OTPEntity> verifiedOtp = otpRepo.verifyOtpSuccessForPurpose(emailAddress, "FORGET PASSWORD");
            if(verifiedOtp.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Password reset not authorized. Please verify the OTP code sent to your email first.");
                response.setData(EMPTY_DATA);
                return response;
            }

            UserEntity userPrevData = getUserData.get();
            userPrevData.setPassword(encryptPassword);
            customerRecords.save(userPrevData);
            // A reset is how someone takes their account back: sign out every
            // device, so a stolen session cannot keep renewing itself.
            refreshTokenService.revokeAllForAccount(userPrevData.getUserId(), Roles.CUSTOMER);

            // Invalidate the OTP so it cannot authorize another reset. Re-saving
            // it with is_used='0' is NOT enough: '0' is exactly the state that
            // verifyOtpSuccessForPurpose matches, so on a live DB the same verified
            // code could reset the password repeatedly until the row was purged.
            // Delete the consumed row instead.
            otpRepo.delete(verifiedOtp.get());

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Password Change Successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateUserPassword(ForgotPasswordData forgotPasswordData, String accountId){
        BaseResponse response = new BaseResponse(true);
        try{
            String oldPassword = forgotPasswordData.getOldPassword();
            String newPassword = forgotPasswordData.getPassword();
            String confirmPassword = forgotPasswordData.getConfirmPassword();
            if(oldPassword.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Old Password cannot be empty");
                response.setData(EMPTY_DATA);
                return response;
            }
            if(!newPassword.equals(confirmPassword)){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("The entered password does not match the confirmed password. Please ensure both passwords are identical.");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Ownership: the password being changed belongs to the authenticated token subject.
            Optional<UserEntity> getUserData = customerRecords.findByUserId(accountId);
            if(getUserData.isEmpty() || !appUtils.passwordMatches(oldPassword, getUserData.get().getPassword())){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Old Password");
                response.setData(EMPTY_DATA);
                return response;
            }
            newPassword = appUtils.encryptPassword(newPassword);
            UserEntity userPrevData = getUserData.get();
            userPrevData.setPassword(newPassword);
            customerRecords.save(userPrevData);
            // Sign out every other device, and hand this one a fresh session.
            refreshTokenService.revokeAllForAccount(accountId, Roles.CUSTOMER);
            response.setToken(jwtUtil.generateToken(accountId, Roles.CUSTOMER));
            response.setRefreshToken(refreshTokenService.issue(accountId, Roles.CUSTOMER));

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Password Change Successfully");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse stylerLogOut(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> getStylerData = stylerRecords.findByStylerId(stylerId);
            if(getStylerData.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            StylerEntity stylerEntity = getStylerData.get();
            stylerEntity.setIsOnline("1");
            stylerRecords.save(stylerEntity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Styler is currently offline");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    private void recordLoginSuccess(String accountType, String accountId, String emailAddress, String ipAddress) {
        if (loginAttemptService != null) {
            loginAttemptService.recordSuccess(accountType, accountId, emailAddress, ipAddress, RateLimiterService.userAgent());
        }
    }

    private void recordLoginFailure(String accountType, String accountId, String emailAddress,
                                    String ipAddress, String reason) {
        if (loginAttemptService != null) {
            loginAttemptService.recordFailure(accountType, accountId, emailAddress, ipAddress,
                    RateLimiterService.userAgent(), reason);
        }
    }

    private String accountType(Optional<StylerEntity> styler, Optional<UserEntity> user) {
        if (styler.isPresent()) {
            return "STYLER";
        }
        if (user.isPresent()) {
            return "CUSTOMER";
        }
        return "UNKNOWN";
    }

    private String accountId(Optional<StylerEntity> styler, Optional<UserEntity> user) {
        if (styler.isPresent()) {
            return styler.get().getStylerId();
        }
        return user.map(UserEntity::getUserId).orElse(null);
    }

    /** Returns the trimmed name, or "" when blank/null — lets userId generation
     *  (and emails) work for the minimal email+password registration. */
    private String nullSafeName(String name){
        return name == null ? "" : name.trim();
    }
}

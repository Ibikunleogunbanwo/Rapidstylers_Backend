package com.macrotel.rapidstylers.auth.service;

import com.macrotel.rapidstylers.engagement.entity.NotificationEntity;
import com.macrotel.rapidstylers.engagement.repo.NotificationRepo;
import com.macrotel.rapidstylers.auth.entity.OTPEntity;
import com.macrotel.rapidstylers.auth.pojo.ForgotPasswordData;
import com.macrotel.rapidstylers.auth.pojo.OTPData;
import com.macrotel.rapidstylers.auth.pojo.SignInData;
import com.macrotel.rapidstylers.auth.repo.OTPRepo;
import com.macrotel.rapidstylers.service.DTOService;
import com.macrotel.rapidstylers.service.RateLimiterService;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.*;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.repo.*;
import com.macrotel.rapidstylers.security.access.Roles;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.security.verifier.GoogleTokenVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Who you are: sign-up (customer and stylist email codes), sign-in (password
 * and Google), password reset and change, and stylist sign-out.
 *
 * <p>Moved out of {@link AppService} unchanged; the endpoints and their answers
 * are the same.
 */
@Service
public class AuthService {
    AppUtils appUtils = new AppUtils();
    private static final Logger LOG = Logger.getLogger(AuthService.class.getName());
    @Autowired
    OTPRepo otpRepo;
    @Autowired
    UserRepo userRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    CardDetailsRepo cardDetailsRepo;
    @Autowired
    NotificationRepo notificationRepo;
    @Autowired
    EmailConfig emailConfig;
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
    // Long enough to fill in the rest of the stylist sign-up after the email code.
    private static final Duration SIGNUP_UPLOAD_TTL = Duration.ofHours(2);
    private static final int AUTH_MAX_FAILURES = 5;       // per email
    private static final int AUTH_IP_MAX_FAILURES = 20;   // per IP
    private static final int OTP_GEN_WINDOW_SECONDS = 900;
    private static final int OTP_GEN_MAX = 3;             // per email
    private static final int OTP_VERIFY_WINDOW_SECONDS = 900;
    private static final int OTP_VERIFY_MAX = 10;         // per IP

    /**
     * Too many failed sign-ins or code checks for this email, or from this
     * caller's IP: the account flows refuse until the window passes.
     */
    public boolean isLockedOut(String emailAddress) {
        return rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                || rateLimiterService.isBlocked("auth_ip:" + rateLimiterService.clientIp(), AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES);
    }

    public BaseResponse generateSignUpOtpCode(OTPData otpData){
        BaseResponse response = new BaseResponse(true);
        try{
            String email = otpData.getEmailAddress();
            String ip = rateLimiterService.clientIp();
            // Global rate limiting: failed login/verify attempts block further
            // OTP sends for this email, and OTP generation itself is capped.
            if (rateLimiterService.isBlocked("auth:" + email, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                    || rateLimiterService.isBlocked("auth_ip:" + ip, AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            if (rateLimiterService.isBlocked("otp_gen:" + email, OTP_GEN_WINDOW_SECONDS, OTP_GEN_MAX)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many OTP requests. Please try again in a few minutes.");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Enumeration-safe: an email already registered in EITHER table gets
            // the same generic success response as a fresh one — no OTP is issued
            // and no mail is sent, so valid and invalid addresses are indistinguishable.
            Optional<UserEntity> isEmailExist = userRepo.findByEmailAddress(email);
            Optional<StylerEntity> isStylerEmailExist = stylerRepo.findByEmailAddress(email);
            if(isEmailExist.isPresent() || isStylerEmailExist.isPresent()){
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("A one-time password (OTP) code has been sent to your email. Please verify it.");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Check if user has requested for OTPCode in the past 1 minute;
            Optional<OTPEntity> getPreviousOtp = otpRepo.checkSignUpValidityOtp(email, "USER SIGN UP");
            if(getPreviousOtp.isPresent()){
                OTPEntity previousOtp = getPreviousOtp.get();
                String previousTimer = previousOtp.getInsertedDt();
                LocalDateTime previousTime = LocalDateTime.parse(previousTimer, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
                LocalDateTime currentTime = LocalDateTime.now();
                long minutesDifference = ChronoUnit.MINUTES.between(previousTime, currentTime);
                if (minutesDifference < 1) {
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("OTP Code was generated earlier, Kindly wait for another minute to regenerate another one");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            rateLimiterService.record("otp_gen:" + email, OTP_GEN_WINDOW_SECONDS);
            String otpCode = appUtils.randomDigit(6);
            OTPEntity otpEntity = new OTPEntity();
            otpEntity.setEmailAddress(email);
            otpEntity.setPurpose("USER SIGN UP");
            otpEntity.setCode(appUtils.hashOtp(otpCode));
            otpRepo.save(otpEntity);

            //Send Mail to user (shared, purpose-neutral OTP email — see sendOtpEmail)
            sendOtpEmail(email, appUtils.extractUsername(email), otpCode);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("A one-time password (OTP) code has been sent to your email. Please verify it.");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse verifyUserOTP(String emailAddress, String otpCode){
        BaseResponse response = new BaseResponse(true);
        String ip = rateLimiterService.clientIp();
        try{
            // Per-IP brute-force cap — applies to every attempt, valid or not.
            if (rateLimiterService.isBlocked("otp_verify:" + ip, OTP_VERIFY_WINDOW_SECONDS, OTP_VERIFY_MAX)
                    || rateLimiterService.isBlocked("auth_ip:" + ip, AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many verification attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            rateLimiterService.record("otp_verify:" + ip, OTP_VERIFY_WINDOW_SECONDS);
            if (emailAddress == null || emailAddress.isBlank() || otpCode == null || otpCode.isBlank()) {
                rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid OTP Code");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Codes are bound to the email they were issued for: look up by email
            // (never by code), then compare the code against its stored hash.
            Optional<OTPEntity> isOTPExist = otpRepo.findLatestUnusedByEmail(emailAddress);
            if(isOTPExist.isEmpty() || !appUtils.otpMatches(otpCode, isOTPExist.get().getCode())){
                // Wrong code or unknown email — count failures against the IP.
                rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid OTP Code");
                response.setData(EMPTY_DATA);
                return response;
            }
            OTPEntity otpData = isOTPExist.get();
            String otpPurpose = otpData.getPurpose();
            // Purpose-scoped: this endpoint only verifies signup / password-reset codes.
            if(!otpPurpose.equals("USER SIGN UP") && !otpPurpose.equals("FORGET PASSWORD")){
                rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid OTP Code");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Lockout: failed logins/verifies for this email block verification too.
            if (rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            String previousOtpTime =  otpData.getInsertedDt();
            LocalDateTime previousTime = LocalDateTime.parse(previousOtpTime, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
            LocalDateTime currentTime = LocalDateTime.now();
            long minutesDifference = ChronoUnit.MINUTES.between(previousTime, currentTime);
            if (minutesDifference > 10) {
                OTPData newOtpData = new OTPData();
                newOtpData.setEmailAddress(emailAddress);
                if(otpPurpose.equals("USER SIGN UP")){
                    this.generateSignUpOtpCode(newOtpData);
                } else if (otpPurpose.equals("FORGET PASSWORD")) {
                    this.resetPasswordMessage(newOtpData);
                }
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("OTP Code has expired, Kindly check mail for another OTP Code");
                response.setData(EMPTY_DATA);
            } else {
                HashMap<String, String> otpValue = new HashMap<>();
                otpValue.put("emailAddress", emailAddress);
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("Email Address Verify Successful");
                response.setData(otpValue);
                // Successful verification clears the failure budget for this email/IP.
                rateLimiterService.clear("auth:" + emailAddress);
                rateLimiterService.clear("auth_ip:" + ip);
            }
            otpData.setIsUsed("0");
            otpRepo.save(otpData);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
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
            Optional<UserEntity> isUserExist = userRepo.findByEmailAddress(userData.getEmailAddress());
            Optional<StylerEntity> isStylerExist = stylerRepo.findByEmailAddress(userData.getEmailAddress());
            if(isUserExist.isPresent() || isStylerExist.isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Email Address already exists, Kindly choose another email address");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Phone uniqueness is only relevant when a phone was provided (nullable now).
            String signupPhone = userData.getPhoneNumber();
            if (signupPhone != null && !signupPhone.isBlank()) {
                Optional<UserEntity> isPhoneExist = userRepo.findByPhoneNumber(signupPhone);
                Optional<StylerEntity> isStylerPhoneExist = stylerRepo.findByPhoneNumber(signupPhone);
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
            userRepo.save(userEntity);

            //Create a space for user in the card_details table
            CardDetailsEntity cardDetailsEntity = new CardDetailsEntity();
            cardDetailsEntity.setUserId(userId);
            cardDetailsRepo.save(cardDetailsEntity);

            //Welcome notification (in-app) that a fresh account is ready to book.
            notificationRepo.save(new NotificationEntity(userId, "", "WELCOME",
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
            Optional<StylerEntity> styler = stylerRepo.findByEmailAddress(emailAddress);
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
                stylerRepo.save(stylerEntity);
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
            Optional<UserEntity> user = userRepo.findByEmailAddress(emailAddress);
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
                    userRepo.save(userEntity);
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
            Optional<StylerEntity> styler = stylerRepo.findByEmailAddress(email);
            Optional<UserEntity> user = userRepo.findByEmailAddress(email);

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
        userRepo.save(userEntity);
        CardDetailsEntity cardDetailsEntity = new CardDetailsEntity();
        cardDetailsEntity.setUserId(userId);
        cardDetailsRepo.save(cardDetailsEntity);
        notificationRepo.save(new NotificationEntity(userId, "", "WELCOME",
                "Welcome to RapidStylers",
                "Your account is ready. Complete your profile and book your first appointment."));
        outboxEventService.welcomeEmail(userEntity);
        return userEntity;
    }

    public BaseResponse resetPasswordMessage(OTPData otpData){
        BaseResponse response = new BaseResponse(true);
        try{
            String emailAddress = otpData.getEmailAddress();
            // Same global budget as signup OTPs: lockouts and caps apply here too.
            String ip = rateLimiterService.clientIp();
            if (rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                    || rateLimiterService.isBlocked("auth_ip:" + ip, AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            if (rateLimiterService.isBlocked("otp_gen:" + emailAddress, OTP_GEN_WINDOW_SECONDS, OTP_GEN_MAX)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many OTP requests. Please try again in a few minutes.");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Enumeration-safe: an unknown email gets the identical generic success
            // response as a registered one — no OTP is issued and no mail is sent,
            // so valid and invalid addresses are indistinguishable.
            Optional<UserEntity> isEmailExist = userRepo.findByEmailAddress(emailAddress);
            if(isEmailExist.isEmpty()){
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("Password Reset Initiated, Check Mail for OTP Code");
                response.setData(EMPTY_DATA);
                return response;
            }
            rateLimiterService.record("otp_gen:" + emailAddress, OTP_GEN_WINDOW_SECONDS);
            UserEntity userEntity = isEmailExist.get();
            String firstname = userEntity.getFirstname();
            String otpCode = appUtils.randomDigit(6);
            OTPEntity otpEntity = new OTPEntity();

            otpEntity.setEmailAddress(emailAddress);
            otpEntity.setCode(appUtils.hashOtp(otpCode));
            otpEntity.setPurpose("FORGET PASSWORD");
            otpRepo.save(otpEntity);

            //Email Message (shared, purpose-neutral OTP email — see sendOtpEmail)
            sendOtpEmail(emailAddress,
                    firstname == null || firstname.isBlank() ? appUtils.extractUsername(emailAddress) : firstname,
                    otpCode);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Password Reset Initiated, Check Mail for OTP Code");
            response.setData(EMPTY_DATA);
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Sends the ONE shared OTP email used by every flow (customer sign-up,
     * styler sign-up, password reset). Same subject and identical markup every
     * time, with no flow-specific wording, so a message cannot fingerprint
     * which endpoint produced it and mail content / mail-server behavior
     * cannot be used to tell registration probes apart.
     */
    private void sendOtpEmail(String emailAddress, String recipientName, String otpCode) {
        String emailSubject = "RapidStylers Verification Code";
        String emailBody = "Dear " + recipientName + ",<br><br>"
                + "Your RapidStylers verification code is:<br><br>"
                + "OTP Code: <strong>" + otpCode + "</strong><br><br>"
                + "This code expires 10 minutes after it was sent. "
                + "If you did not request it, you can safely ignore this email.<br><br>"
                + "Thank you,<br>The Rapid Stylers Team";
        emailConfig.sendSimpleMail(emailAddress, emailSubject, emailBody);
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
            Optional<UserEntity> getUserData = userRepo.findByEmailAddress(emailAddress);
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
            userRepo.save(userPrevData);
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
            Optional<UserEntity> getUserData = userRepo.findByUserId(accountId);
            if(getUserData.isEmpty() || !appUtils.passwordMatches(oldPassword, getUserData.get().getPassword())){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Old Password");
                response.setData(EMPTY_DATA);
                return response;
            }
            newPassword = appUtils.encryptPassword(newPassword);
            UserEntity userPrevData = getUserData.get();
            userPrevData.setPassword(newPassword);
            userRepo.save(userPrevData);
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

    /**
     * Send OTP for stylist registration — checks the STYLER table (not user table).
     */
    public BaseResponse stylerGenerateOtp(OTPData otpData){
        BaseResponse response = new BaseResponse(true);
        try{
            String emailAddress = otpData.getEmailAddress();
            String ip = rateLimiterService.clientIp();
            // Global rate limiting (shared with login/verify failures and the
            // customer flow): lockouts block further sends, generation is capped.
            if (rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                    || rateLimiterService.isBlocked("auth_ip:" + ip, AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            if (rateLimiterService.isBlocked("otp_gen:" + emailAddress, OTP_GEN_WINDOW_SECONDS, OTP_GEN_MAX)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many OTP requests. Please try again in a few minutes.");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Enumeration-safe: an email already registered in EITHER table gets
            // the same generic success response as a fresh one — no OTP is issued
            // and no mail is sent, so valid and invalid addresses are indistinguishable.
            Optional<StylerEntity> isStylerEmailExist = stylerRepo.findByEmailAddress(emailAddress);
            Optional<UserEntity> isUserEmailExist = userRepo.findByEmailAddress(emailAddress);
            if(isStylerEmailExist.isPresent() || isUserEmailExist.isPresent()){
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("A one-time password (OTP) code has been sent to your email. Please verify it.");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Rate limit: 1 OTP per minute (purpose-scoped so this actually fires)
            Optional<OTPEntity> getPreviousOtp = otpRepo.checkSignUpValidityOtp(emailAddress, "STYLER SIGN UP");
            if(getPreviousOtp.isPresent()){
                OTPEntity previousOtp = getPreviousOtp.get();
                String previousTimer = previousOtp.getInsertedDt();
                LocalDateTime previousTime = LocalDateTime.parse(previousTimer, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
                LocalDateTime currentTime = LocalDateTime.now();
                long minutesDifference = ChronoUnit.MINUTES.between(previousTime, currentTime);
                if (minutesDifference < 1) {
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("OTP Code was generated earlier, Kindly wait for another minute to regenerate another one");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            rateLimiterService.record("otp_gen:" + emailAddress, OTP_GEN_WINDOW_SECONDS);
            String otpCode = appUtils.randomDigit(6);
            OTPEntity otpEntity = new OTPEntity();
            otpEntity.setEmailAddress(emailAddress);
            otpEntity.setPurpose("STYLER SIGN UP");
            otpEntity.setCode(appUtils.hashOtp(otpCode));
            otpRepo.save(otpEntity);

            // Send email (shared, purpose-neutral OTP email — see sendOtpEmail)
            sendOtpEmail(emailAddress, appUtils.extractUsername(emailAddress), otpCode);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("A one-time password (OTP) code has been sent to your email. Please verify it.");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Verify stylist OTP — same logic as user OTP but with STYLER SIGN UP purpose.
     */
    public BaseResponse stylerVerifyOtp(String emailAddress, String otpCode){
        BaseResponse response = new BaseResponse(true);
        String ip = rateLimiterService.clientIp();
        try{
            // Per-IP brute-force cap — applies to every attempt, valid or not.
            if (rateLimiterService.isBlocked("otp_verify:" + ip, OTP_VERIFY_WINDOW_SECONDS, OTP_VERIFY_MAX)
                    || rateLimiterService.isBlocked("auth_ip:" + ip, AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many verification attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            rateLimiterService.record("otp_verify:" + ip, OTP_VERIFY_WINDOW_SECONDS);
            if (emailAddress == null || emailAddress.isBlank() || otpCode == null || otpCode.isBlank()) {
                rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid OTP Code");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Codes are bound to the email they were issued for: look up by email
            // (never by code), then compare the code against its stored hash.
            Optional<OTPEntity> isOTPExist = otpRepo.findLatestUnusedByEmail(emailAddress);
            if(isOTPExist.isEmpty() || !appUtils.otpMatches(otpCode, isOTPExist.get().getCode())){
                // Wrong code or unknown email — count failures against the IP.
                rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid OTP Code");
                response.setData(EMPTY_DATA);
                return response;
            }
            OTPEntity otpData = isOTPExist.get();
            String otpPurpose = otpData.getPurpose();
            // Purpose-scoped: this endpoint only verifies styler sign-up codes.
            if(!otpPurpose.equals("STYLER SIGN UP")){
                rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid OTP Code");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Lockout: failed logins/verifies for this email block verification too.
            if (rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            String previousOtpTime = otpData.getInsertedDt();
            LocalDateTime previousTime = LocalDateTime.parse(previousOtpTime, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
            LocalDateTime currentTime = LocalDateTime.now();
            long minutesDifference = ChronoUnit.MINUTES.between(previousTime, currentTime);
            if (minutesDifference > 10) {
                // Auto-regenerate
                OTPData newOtpData = new OTPData();
                newOtpData.setEmailAddress(emailAddress);
                if(otpPurpose.equals("STYLER SIGN UP")){
                    this.stylerGenerateOtp(newOtpData);
                }
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("OTP Code has expired, Kindly check mail for another OTP Code");
                response.setData(EMPTY_DATA);
            } else {
                HashMap<String, String> otpValue = new HashMap<>();
                otpValue.put("emailAddress", emailAddress);
                // The stylist uploads their photos before the account exists; this
                // pass is what lets them ask for an upload signature.
                otpValue.put("uploadToken", jwtUtil.generateToken(emailAddress, Roles.SIGNUP_UPLOAD, SIGNUP_UPLOAD_TTL));
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("Email Address Verified Successfully");
                response.setData(otpValue);
                // Successful verification clears the failure budget for this email/IP.
                rateLimiterService.clear("auth:" + emailAddress);
                rateLimiterService.clear("auth_ip:" + ip);
            }
            otpData.setIsUsed("0");
            otpRepo.save(otpData);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse stylerLogOut(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> getStylerData = stylerRepo.findByStylerId(stylerId);
            if(getStylerData.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            StylerEntity stylerEntity = getStylerData.get();
            stylerEntity.setIsOnline("1");
            stylerRepo.save(stylerEntity);

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

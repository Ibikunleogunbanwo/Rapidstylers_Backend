package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.config.EncryptionConfig;
import com.macrotel.rapidstylers.security.JwtUtil;
import com.macrotel.rapidstylers.security.GoogleTokenVerifier;
import com.macrotel.rapidstylers.entity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.outbox.OutboxEventRepo;
import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.repo.*;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Balance;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.*;
import java.util.logging.Logger;
import java.time.Duration;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@Service
public class AppService {
    AppUtils appUtils = new AppUtils();
    @Autowired
    DTOService dtoService;
    private static final Logger LOG = Logger.getLogger(AppService.class.getName());
    @Autowired
    OTPRepo otpRepo;
    @Autowired
    EmailConfig emailConfig;
    @Autowired
    UserRepo userRepo;
    @Autowired
    IdentificationRepo identificationRepo;
    @Autowired
    ServiceRepo serviceRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    SubServiceRepo subServiceRepo;
    @Autowired
    StylerPortfolioRepo stylerPortfolioRepo;
    @Autowired
    ReviewRepo reviewRepo;
    @Autowired
    SavedStylistRepo savedStylistRepo;
    @Autowired
    NotificationRepo notificationRepo;
    @Autowired
    SupportTicketRepo supportTicketRepo;
    @Autowired
    AuditLogRepo auditLogRepo;
    @Autowired
    LoyaltyAccountRepo loyaltyAccountRepo;
    @Autowired
    ReferralRepo referralRepo;
    @Autowired
    BookAppointmentRepo bookAppointmentRepo;
    @Autowired
    FeedBackRepo feedBackRepo;
    @Autowired
    CardDetailsRepo cardDetailsRepo;
    @Autowired
    BlogPostRepo blogPostRepo;
    @Autowired
    AvailabilityRepo availabilityRepo;
    @Autowired
    AvailabilityExceptionRepo availabilityExceptionRepo;
    @Autowired
    BookingSlotLockRepo bookingSlotLockRepo;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    StripeService stripeService;
    @Value("${app.stripe.commission-percent:10}")
    private double stripeCommissionPercent;
    @Autowired
    PlatformSettingRepo platformSettingRepo;
    // Runtime commission (admin-configurable). null = use the @Value default.
    private volatile Double cachedCommissionPercent;
    @Autowired
    GeocodingService geocodingService;
    @Autowired
    LocationCacheService locationCacheService;
    @Autowired
    LocationService locationService;
    @Autowired
    RateLimiterService rateLimiterService;
    @Autowired
    LoginAttemptService loginAttemptService;
    @Autowired
    OutboxEventService outboxEventService;
    @Autowired
    EncryptionConfig encryptionConfig;
    @Autowired
    IdempotencyService idempotencyService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    OutboxEventRepo outboxEventRepo;
    @Autowired
    RefreshTokenService refreshTokenService;
    @Autowired
    GoogleTokenVerifier googleTokenVerifier;
    @Autowired
    RefundRepo refundRepo;
    @Autowired
    PayoutReversalService payoutReversalService;

    /** Ops email address for payment dispute / reconciliation alerts (empty = disabled). */
    @Value("${app.admin.alert-email:}")
    private String adminAlertEmail;

    // Global rate-limit budgets (shared across OTP generation, OTP verification
    // and login so failures on one surface lock out the others).
    private static final int AUTH_WINDOW_SECONDS = 900;   // 15 min
    private static final int AUTH_MAX_FAILURES = 5;       // per email
    private static final int AUTH_IP_MAX_FAILURES = 20;   // per IP
    private static final int OTP_GEN_WINDOW_SECONDS = 900;
    private static final int OTP_GEN_MAX = 3;             // per email
    private static final int OTP_VERIFY_WINDOW_SECONDS = 900;
    private static final int OTP_VERIFY_MAX = 10;         // per IP
    // Booking starts and slot holds use 15-minute granularity; each service's
    // configured duration determines how much of the stylist's calendar is blocked.
    private static final int SLOT_GRANULARITY_MINUTES = 15;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Value("${app.time-zone:America/Edmonton}")
    private String appTimeZone;

    @Value("${app.booking.completion-grace-minutes:0}")
    private long completionGraceMinutes;

    @Value("${app.booking.payment-authorization-window-days:7}")
    private long paymentAuthorizationWindowDays;

    @Value("${app.booking.payment-authorization-lead-hours:48}")
    private long paymentAuthorizationLeadHours;

    /** Stylists may cancel a completed booking only within this window (hours since completion). */
    @Value("${app.booking.styler-cancel-window-hours:24}")
    private long stylerCancelWindowHours;

    public BaseResponse testing(){
        BaseResponse response = new BaseResponse(true);
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage("API is working well");
        response.setData(EMPTY_DATA);
        return response;
    }

    public BaseResponse generateSignUpOtpCode(OTPData otpData){
        BaseResponse response = new BaseResponse(true);
        try{
            // Check email across BOTH tables
            Optional<UserEntity> isEmailExist = userRepo.findByEmailAddress(otpData.getEmailAddress());
            Optional<StylerEntity> isStylerEmailExist = stylerRepo.findByEmailAddress(otpData.getEmailAddress());
            if(isEmailExist.isPresent() || isStylerEmailExist.isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Email Address already exists, Kindly choose another email");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Global rate limiting: failed login/verify attempts block further
            // OTP sends for this email, and OTP generation itself is capped.
            String email = otpData.getEmailAddress();
            String ip = rateLimiterService.clientIp();
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
            otpEntity.setCode(otpCode);
            otpRepo.save(otpEntity);

            //Send Mail to user
            String emailSubject = "Rapid Stylers! Email Confirmation";
            String emailBody = "Dear " + appUtils.extractUsername( otpData.getEmailAddress()) + ",<br><br>"
                    + "Welcome to Rapid Stylers! Your account has been created successfully."
                    + "<br>To verify your email, please use the following OTP code:<br><br>"
                    + "OTP Code: <strong>" + otpCode  + "</strong><br><br>"
                    + "Please enter this OTP code to complete your account registration process.<br><br>"
                    + "Thank you,<br>The Rapid Stylers Team";
            emailConfig.sendSimpleMail(otpData.getEmailAddress(),emailSubject,emailBody);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("A one-time password (OTP) code has been sent to your email. Please verify it.");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse verifyUserOTP(String otpCode){
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
            Optional<OTPEntity> isOTPExist = otpRepo.checkUserCode(otpCode);
            if(isOTPExist.isEmpty()){
                // Email is unknown for a bad code — count failures against the IP.
                rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid OTP Code");
                response.setData(EMPTY_DATA);
                return response;
            }
            OTPEntity otpData = isOTPExist.get();
            String emailAddress = otpData.getEmailAddress();
            // Lockout: failed logins/verifies for this email block verification too.
            if (rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            String previousOtpTime =  otpData.getInsertedDt();
            String otpPurpose = otpData.getPurpose();
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
            String userId;
            if (!fname.isEmpty() && !lname.isEmpty()) {
                userId = fname.charAt(0) + appUtils.randomDigit(4) + lname.charAt(0);
            } else {
                userId = "U" + appUtils.randomDigit(4);
            }
            UserEntity userEntity = new UserEntity(userData);
            userEntity.setUserId(userId);
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
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Unified sign-in for all three roles. Tries admin (env-configured), then
     * styler, then customer; returns the account data with its role and a
     * role-scoped JWT so the frontend can route to the right dashboard.
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

            // 1. Admin (environment-configured credentials)
            if(adminEmail != null && !adminEmail.isEmpty()
                    && adminEmail.equalsIgnoreCase(emailAddress)
                    && adminPassword.equals(password)){
                Map<String, Object> adminData = new LinkedHashMap<>();
                adminData.put("role", "ADMIN");
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage(SUCCESS_MESSAGE);
                response.setToken(jwtUtil.generateToken("admin", "ADMIN"));
                response.setRefreshToken(refreshTokenService.issue("admin", "ADMIN"));
                response.setData(adminData);
                rateLimiterService.clear("auth:" + emailAddress);
                rateLimiterService.clear("auth_ip:" + ip);
                recordLoginSuccess("ADMIN", "admin", emailAddress, ip);
                return response;
            }

            // 2. Styler (vendor)
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

            // 3. Customer
            Optional<UserEntity> user = userRepo.findByEmailAddress(emailAddress);
            if(user.isPresent() && "0".equals(user.get().getStatus())
                    && appUtils.passwordMatches(password, user.get().getPassword())){
                UserEntity userEntity = user.get();
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

    public BaseResponse userSignIn(SignInData signInData){
        BaseResponse response = new BaseResponse(true);
        try{
            //Validate UserSign In — fetch by email, verify in Java (BCrypt with legacy MD5 fallback)
            String emailAddress = signInData.getEmailAddress();
            String password = signInData.getPassword();
            String ip = rateLimiterService.clientIp();
            // Global lockout: failed logins/verifies (email or IP) block sign-in.
            if (rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                    || rateLimiterService.isBlocked("auth_ip:" + ip, AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                recordLoginFailure("CUSTOMER", null, emailAddress, ip, "LOCKED_OUT");
                return response;
            }
            Optional<UserEntity> userSignIn = userRepo.findByEmailAddress(emailAddress);
            if(userSignIn.isEmpty()
                    || !"0".equals(userSignIn.get().getStatus())
                    || !appUtils.passwordMatches(password, userSignIn.get().getPassword())){
                rateLimiterService.record("auth:" + emailAddress, AUTH_WINDOW_SECONDS);
                rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
                recordLoginFailure("CUSTOMER", userSignIn.map(UserEntity::getUserId).orElse(null),
                        emailAddress, ip, "INVALID_CREDENTIALS");
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Email Address or Password");
                response.setData(EMPTY_DATA);
                return response;
            }
            UserEntity userEntity = userSignIn.get();
            // Upgrade a legacy MD5 hash to BCrypt on first successful login
            if(userEntity.getPassword().matches("^[0-9a-fA-F]{32}$")){
                userEntity.setPassword(appUtils.encryptPassword(password));
                userRepo.save(userEntity);
            }
            rateLimiterService.clear("auth:" + emailAddress);
            rateLimiterService.clear("auth_ip:" + ip);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setToken(jwtUtil.generateToken(userEntity.getUserId(), "CUSTOMER"));
            response.setData(dtoService.userAccountDTO(userEntity));
            recordLoginSuccess("CUSTOMER", userEntity.getUserId(), emailAddress, ip);
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
        String userId;
        if (!fname.isEmpty() && !lname.isEmpty()) {
            userId = fname.charAt(0) + appUtils.randomDigit(4) + lname.charAt(0);
        } else {
            userId = "U" + appUtils.randomDigit(4);
        }
        UserEntity userEntity = new UserEntity(signupData);
        userEntity.setUserId(userId);
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

    public BaseResponse singleUserData(String userId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<UserEntity> isUserExist = userRepo.findByUserId(userId);
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id, Kindly create account");
                response.setData(EMPTY_DATA);
                return response;
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(dtoService.userDataDTO(userId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateUserData(UpdateData updateData, String accountId){
        BaseResponse response = new BaseResponse(true);
        try{
            // Ownership: the account being edited is the authenticated token subject, never the request body.
            Optional<UserEntity> isUserExist = userRepo.findByUserId(accountId);
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id, Kindly create an account");
                response.setData(EMPTY_DATA);
                return response;
            }
            UserEntity userEntity = isUserExist.get();
            userEntity.setFirstname(updateData.getFirstname().isEmpty() ? userEntity.getFirstname() : updateData.getFirstname());
            userEntity.setLastname(updateData.getLastname().isEmpty() ? userEntity.getLastname() : updateData.getLastname());
            userEntity.setAddress(updateData.getAddress().isEmpty() ? userEntity.getAddress() : updateData.getAddress());
            userEntity.setCountry(updateData.getCountry().isEmpty() ? userEntity.getCountry() : updateData.getCountry());
            userEntity.setPhoneNumber(updateData.getPhoneNumber().isEmpty() ? userEntity.getPhoneNumber() : updateData.getPhoneNumber());
            userEntity.setState(updateData.getState().isEmpty() ?  userEntity.getState() : updateData.getState());
            userRepo.save(userEntity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Account Updated Successful");
            response.setData(EMPTY_DATA);

        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse resetPasswordMessage(OTPData otpData){
        BaseResponse response = new BaseResponse(true);
        try{
            String emailAddress = otpData.getEmailAddress();
            Optional<UserEntity> isEmailExist = userRepo.findByEmailAddress(emailAddress);
            if(isEmailExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Email Address, Kindly create an account");
                response.setData(EMPTY_DATA);
                return response;
            }
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
            rateLimiterService.record("otp_gen:" + emailAddress, OTP_GEN_WINDOW_SECONDS);
            UserEntity userEntity = isEmailExist.get();
            String firstname = userEntity.getFirstname();
            String otpCode = appUtils.randomDigit(6);
            OTPEntity otpEntity = new OTPEntity();

            otpEntity.setEmailAddress(emailAddress);
            otpEntity.setCode(otpCode);
            otpEntity.setPurpose("FORGET PASSWORD");
            otpRepo.save(otpEntity);

            //Email Message
            String emailSubject = "RapidStylers Password Reset Request";
            String emailBody = "Dear " + firstname + ",<br><br>"
                    + "We received a request to reset your RapidStylers account password."
                    + "<br>To proceed with the password reset, please use the following:<br><br>"
                    + "OTP Code: <strong>" + otpCode  + "</strong><br><br>"
                    + "Enter this OTP code to complete the password reset process. If you didn't make this request, you can safely ignore this email."
                    + "<br><br>Thank you,<br>The Rapid Stylers Team";
            emailConfig.sendSimpleMail(emailAddress,emailSubject,emailBody);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Password Reset Initiated, Check Mail for OTP Code");
            response.setData(EMPTY_DATA);
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
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

            // Invalidate the OTP so it cannot be reused
            OTPEntity usedOtp = verifiedOtp.get();
            usedOtp.setIsUsed("0");
            otpRepo.save(usedOtp);

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

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Password Change Successfully");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
    public BaseResponse createIdentificationType (IdentificationData identificationData){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<IdentificationEntity> isIdNameExist = identificationRepo.findByIdentificationName(identificationData.getIdentificationName());
            if(isIdNameExist.isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Identification already exist");
                response.setData(EMPTY_DATA);
                return response;
            }
            IdentificationEntity identificationEntity = new IdentificationEntity();
            identificationEntity.setIdentificationName(identificationData.getIdentificationName());
            identificationRepo.save(identificationEntity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(identificationData.getIdentificationName()+ " successfully added to list of identification");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listIdentification(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<IdentificationEntity> getAllIdentification = identificationRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(IdentificationEntity identificationEntity : getAllIdentification){
                HashMap<String, String> idMap = new HashMap<>();
                idMap.put("id", String.valueOf(identificationEntity.getId()));
                idMap.put("identificationName", identificationEntity.getIdentificationName());
                idMap.put("status", identificationEntity.getStatus());
                idMap.put("dateCreated", identificationEntity.getInsertedDate());
                result.add(idMap);
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateIdentification(IdentificationData identificationData){
        BaseResponse response = new BaseResponse(true);
        try{
            //Get Identifications
            if(identificationData.getId().isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Identification Id cannot be empty");
                response.setData(EMPTY_DATA);
                return response;
            }
            Optional<IdentificationEntity> isIdentificationExist = identificationRepo.findById(Long.parseLong(identificationData.getId()));
            if(isIdentificationExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No Identification available for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            IdentificationEntity identificationEntity = isIdentificationExist.get();
            identificationEntity.setIdentificationName(identificationData.getIdentificationName());
            identificationRepo.save(identificationEntity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Identification Updated Successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse deleteIdentification(String id){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<IdentificationEntity> getIdentification = identificationRepo.findById(Long.parseLong(id));
            if(getIdentification.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No Identification available for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            IdentificationEntity identificationEntity = getIdentification.get();
            identificationRepo.delete(identificationEntity);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Identification deleted successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
    public BaseResponse createServiceType (ServiceTypeData serviceTypeData){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<ServiceEntity> isServiceNameExist = serviceRepo.findByServiceName(serviceTypeData.getServiceName());
            if(isServiceNameExist.isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Service Name already exist");
                response.setData(EMPTY_DATA);
                return response;
            }
            ServiceEntity serviceEntity = new ServiceEntity();
            serviceEntity.setServiceName(AppUtils.sanitizeText(serviceTypeData.getServiceName()));
            serviceEntity.setServiceImageUrl(serviceTypeData.getImageUrl());
            serviceEntity.setDescription(AppUtils.sanitizeText(serviceTypeData.getDescription()));
            serviceRepo.save(serviceEntity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(serviceTypeData.getServiceName()+ " successfully added to list of service");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
    public BaseResponse listService(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<ServiceEntity> getAllService = serviceRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(ServiceEntity serviceEntity : getAllService){
                HashMap<String, String> serviceMap = new HashMap<>();
                serviceMap.put("id", String.valueOf(serviceEntity.getId()));
                serviceMap.put("serviceName", serviceEntity.getServiceName());
                serviceMap.put("status", serviceEntity.getStatus());
                serviceMap.put("dateCreated", serviceEntity.getInsertedDt());
                serviceMap.put("imageUrl", serviceEntity.getServiceImageUrl());
                serviceMap.put("description", serviceEntity.getDescription());
                result.add(serviceMap);
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
    public BaseResponse updateService(ServiceTypeData serviceTypeData){
        BaseResponse response = new BaseResponse(true);
        try{
            //Get Identifications
            if(serviceTypeData.getId().isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Service Id cannot be empty");
                response.setData(EMPTY_DATA);
                return response;
            }
            Optional<ServiceEntity> isServiceExist = serviceRepo.findById(Long.parseLong(serviceTypeData.getId()));
            if(isServiceExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No Service exist for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            ServiceEntity serviceEntity = isServiceExist.get();
            serviceEntity.setServiceName(AppUtils.sanitizeText(serviceTypeData.getServiceName()));
            serviceEntity.setServiceImageUrl(serviceTypeData.getImageUrl());
            serviceEntity.setDescription(AppUtils.sanitizeText(serviceTypeData.getDescription()));
            serviceRepo.save(serviceEntity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Service Updated Successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
    public BaseResponse deleteService(String id){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<ServiceEntity> getService = serviceRepo.findById(Long.parseLong(id));
            if(getService.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No Service available for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            ServiceEntity serviceEntity = getService.get();
            serviceRepo.delete(serviceEntity);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Service deleted successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
    public BaseResponse listBlogPosts(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<BlogPostEntity> allPosts = blogPostRepo.findAll();
            allPosts.sort(Comparator.comparing(BlogPostEntity::getId).reversed());
            List<Object> result = new ArrayList<>();
            for(BlogPostEntity post : allPosts){
                HashMap<String, String> postMap = new HashMap<>();
                postMap.put("id", String.valueOf(post.getId()));
                postMap.put("title", post.getTitle());
                postMap.put("category", post.getCategory());
                postMap.put("imageUrl", post.getImageUrl());
                postMap.put("author", post.getAuthor());
                postMap.put("dateCreated", post.getInsertedDt());
                result.add(postMap);
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
    public BaseResponse singleBlogPost(String id){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<BlogPostEntity> post = blogPostRepo.findById(Long.parseLong(id));
            if(post.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No blog article found for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            BlogPostEntity blogPost = post.get();
            HashMap<String, String> postMap = new HashMap<>();
            postMap.put("id", String.valueOf(blogPost.getId()));
            postMap.put("title", blogPost.getTitle());
            postMap.put("category", blogPost.getCategory());
            postMap.put("content", blogPost.getContent());
            postMap.put("imageUrl", blogPost.getImageUrl());
            postMap.put("author", blogPost.getAuthor());
            postMap.put("dateCreated", blogPost.getInsertedDt());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(postMap);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
    public BaseResponse createBlogPost(BlogPostData blogPostData){
        BaseResponse response = new BaseResponse(true);
        try{
            if(blogPostData.getTitle() == null || blogPostData.getTitle().isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Blog title cannot be empty");
                response.setData(EMPTY_DATA);
                return response;
            }
            BlogPostEntity post = new BlogPostEntity();
            post.setTitle(AppUtils.sanitizeText(blogPostData.getTitle()));
            post.setCategory(AppUtils.sanitizeText(blogPostData.getCategory()));
            post.setContent(AppUtils.sanitizeText(blogPostData.getContent()));
            post.setImageUrl(blogPostData.getImageUrl());
            post.setAuthor(blogPostData.getAuthor() == null || blogPostData.getAuthor().isEmpty()
                    ? "RapidStylers Team" : AppUtils.sanitizeText(blogPostData.getAuthor()));
            blogPostRepo.save(post);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Blog article created successfully");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
    public BaseResponse updateBlogPost(BlogPostData blogPostData){
        BaseResponse response = new BaseResponse(true);
        try{
            if(blogPostData.getId() == null || blogPostData.getId().isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Blog article id cannot be empty");
                response.setData(EMPTY_DATA);
                return response;
            }
            Optional<BlogPostEntity> isPostExist = blogPostRepo.findById(Long.parseLong(blogPostData.getId()));
            if(isPostExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No blog article exist for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            BlogPostEntity post = isPostExist.get();
            post.setTitle(AppUtils.sanitizeText(blogPostData.getTitle()));
            post.setCategory(AppUtils.sanitizeText(blogPostData.getCategory()));
            post.setContent(AppUtils.sanitizeText(blogPostData.getContent()));
            post.setImageUrl(blogPostData.getImageUrl());
            if(blogPostData.getAuthor() != null && !blogPostData.getAuthor().isEmpty()){
                post.setAuthor(AppUtils.sanitizeText(blogPostData.getAuthor()));
            }
            blogPostRepo.save(post);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Blog article updated successfully");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
    public BaseResponse deleteBlogPost(String id){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<BlogPostEntity> getPost = blogPostRepo.findById(Long.parseLong(id));
            if(getPost.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No blog article available for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            blogPostRepo.delete(getPost.get());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Blog article deleted successfully");
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
            // Check email across BOTH tables
            Optional<StylerEntity> isStylerEmailExist = stylerRepo.findByEmailAddress(otpData.getEmailAddress());
            Optional<UserEntity> isUserEmailExist = userRepo.findByEmailAddress(otpData.getEmailAddress());
            if(isStylerEmailExist.isPresent() || isUserEmailExist.isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Email Address already exists, Kindly choose another email");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Global rate limiting (shared with login/verify failures and the
            // customer flow): lockouts block further sends, generation is capped.
            String emailAddress = otpData.getEmailAddress();
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
            otpEntity.setCode(otpCode);
            otpRepo.save(otpEntity);

            // Send email
            String emailSubject = "RapidStylers! Stylist Email Verification";
            String emailBody = "Dear Stylist,<br><br>"
                    + "Thank you for registering with RapidStylers!"
                    + "<br>To verify your email, please use the following OTP code:<br><br>"
                    + "OTP Code: <strong>" + otpCode  + "</strong><br><br>"
                    + "Please enter this OTP code to complete your registration.<br><br>"
                    + "Thank you,<br>The Rapid Stylers Team";
            emailConfig.sendSimpleMail(otpData.getEmailAddress(), emailSubject, emailBody);

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
    public BaseResponse stylerVerifyOtp(String otpCode){
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
            Optional<OTPEntity> isOTPExist = otpRepo.checkUserCode(otpCode);
            if(isOTPExist.isEmpty()){
                // Email is unknown for a bad code — count failures against the IP.
                rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid OTP Code");
                response.setData(EMPTY_DATA);
                return response;
            }
            OTPEntity otpData = isOTPExist.get();
            String emailAddress = otpData.getEmailAddress();
            // Lockout: failed logins/verifies for this email block verification too.
            if (rateLimiterService.isBlocked("auth:" + emailAddress, AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            String previousOtpTime = otpData.getInsertedDt();
            String otpPurpose = otpData.getPurpose();
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

    public BaseResponse createStyler(StylerData stylerData){
        BaseResponse response = new BaseResponse(true);
        try{
            if (!stylerData.isAgreeToTerms()) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You must agree to the Terms and Conditions");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Check email across BOTH tables
            Optional<StylerEntity> isStylerEmailExist = stylerRepo.findByEmailAddress(stylerData.getEmailAddress());
            Optional<UserEntity> isUserEmailExist = userRepo.findByEmailAddress(stylerData.getEmailAddress());
            if(isStylerEmailExist.isPresent() || isUserEmailExist.isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Email Address already exists, Kindly choose another email");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Locked-out email (failed logins/verifies) cannot create an account.
            if (rateLimiterService.isBlocked("auth:" + stylerData.getEmailAddress(), AUTH_WINDOW_SECONDS, AUTH_MAX_FAILURES)
                    || rateLimiterService.isBlocked("auth_ip:" + rateLimiterService.clientIp(), AUTH_WINDOW_SECONDS, AUTH_IP_MAX_FAILURES)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many failed attempts. Please try again later.");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Enforce email verification: a STYLER SIGN UP OTP must have been verified.
            Optional<OTPEntity> verifiedOtp = otpRepo.verifyOtpSuccessForPurpose(stylerData.getEmailAddress(), "STYLER SIGN UP");
            if(verifiedOtp.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Email Address is yet to be verified");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Check phone number across both tables
            Optional<StylerEntity> isStylerPhoneExist = stylerRepo.findByPhoneNumber(stylerData.getPhoneNumber());
            Optional<UserEntity> isUserPhoneExist = userRepo.findByPhoneNumber(stylerData.getPhoneNumber());
            if(isStylerPhoneExist.isPresent() || isUserPhoneExist.isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Phone number already registered");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Check if identificationId and ServiceId exist
            Optional<IdentificationEntity> isIdentificationExist = identificationRepo.findById(Long.parseLong(stylerData.getIdentificationTypeId()));
            if(isIdentificationExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Identification Type, Contact Admin");
                response.setData(EMPTY_DATA);
                return response;
            }
            Optional<ServiceEntity> isServiceTypeExist = serviceRepo.findById(Long.parseLong(stylerData.getServiceTypeId()));
            if(isServiceTypeExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Service Type, Contact Admin");
                response.setData(EMPTY_DATA);
                return response;
            }
            StylerEntity stylerEntity = new StylerEntity(stylerData);

            // Geocode address → lat/lng if not already provided
            if(stylerEntity.getLatitude() == null || stylerEntity.getLongitude() == null){
                String addressToGeocode = buildAddress(stylerData);
                java.util.Map<String, Object> geo = geocodingService.geocode(addressToGeocode);
                if(geo != null){
                    stylerEntity.setLatitude((Double) geo.get("latitude"));
                    stylerEntity.setLongitude((Double) geo.get("longitude"));
                    if(stylerEntity.getCity() == null || stylerEntity.getCity().isEmpty()){
                        stylerEntity.setCity((String) geo.getOrDefault("city", ""));
                    }
                }
            }

            stylerRepo.save(stylerEntity);

            // Index in Redis geospatial cache for fast radius search
            if(stylerEntity.getLatitude() != null && stylerEntity.getLongitude() != null){
                locationCacheService.indexStyler(stylerEntity.getStylerId(), stylerEntity.getLongitude(), stylerEntity.getLatitude());
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Stylers Account Created Successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
    public BaseResponse stylerLogin(SignInData signInData){
        BaseResponse response = new BaseResponse(true);
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
                recordLoginFailure("STYLER", null, emailAddress, ip, "LOCKED_OUT");
                return response;
            }
            Optional<StylerEntity> stylerSignIn = stylerRepo.findByEmailAddress(emailAddress);
            if(stylerSignIn.isEmpty()
                    || !appUtils.passwordMatches(password, stylerSignIn.get().getPassword())){
                rateLimiterService.record("auth:" + emailAddress, AUTH_WINDOW_SECONDS);
                rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
                recordLoginFailure("STYLER", stylerSignIn.map(StylerEntity::getStylerId).orElse(null),
                        emailAddress, ip, "INVALID_CREDENTIALS");
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Email Address or Password");
                response.setData(EMPTY_DATA);
                return response;
            }
            StylerEntity stylerEntity = stylerSignIn.get();
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
            // Upgrade a legacy MD5 hash to BCrypt on first successful login
            if(stylerEntity.getPassword().matches("^[0-9a-fA-F]{32}$")){
                stylerEntity.setPassword(appUtils.encryptPassword(password));
                stylerRepo.save(stylerEntity);
            }
            stylerEntity.setIsOnline("0");
            stylerRepo.save(stylerEntity);
            rateLimiterService.clear("auth:" + emailAddress);
            rateLimiterService.clear("auth_ip:" + ip);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setToken(jwtUtil.generateToken(stylerEntity.getStylerId(), "STYLER"));
            response.setData(dtoService.stylerAccountDTO(stylerEntity));
            recordLoginSuccess("STYLER", stylerEntity.getStylerId(), emailAddress, ip);
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
    // ── Professional verification workflow (admin) ─────────────────────

    private boolean isApprovedStyler(StylerEntity styler){
        return VERIFICATION_APPROVED.equals(styler.getVerificationStatus());
    }

    private String stylerServiceName(String serviceTypeId){
        if(serviceTypeId == null || serviceTypeId.isEmpty()) return "";
        try{
            Optional<ServiceEntity> svc = serviceRepo.findById(Long.valueOf(serviceTypeId));
            return svc.map(ServiceEntity::getServiceName).orElse("");
        }
        catch (NumberFormatException e){
            return "";
        }
    }

    public BaseResponse getStylerVerificationQueue(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<StylerEntity> allStylers = stylerRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(StylerEntity styler : allStylers){
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("stylerId", styler.getStylerId());
                entry.put("firstname", styler.getFirstname());
                entry.put("lastname", styler.getLastname());
                entry.put("emailAddress", styler.getEmailAddress());
                entry.put("phoneNumber", styler.getPhoneNumber());
                entry.put("businessName", styler.getBusinessName());
                entry.put("serviceTypeId", styler.getServiceTypeId());
                entry.put("serviceTypeName", stylerServiceName(styler.getServiceTypeId()));
                entry.put("city", styler.getCity());
                entry.put("province", styler.getProvince());
                entry.put("identificationId", styler.getIdentificationId());
                entry.put("identificationImageUrl", styler.getIdentificationImageUrl());
                entry.put("profileImageUrl", styler.getProfileImageUrl());
                entry.put("verificationStatus", styler.getVerificationStatus());
                entry.put("dateRegistered", styler.getInsertedDt());
                result.add(entry);
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateStylerVerification(VerificationActionData verificationActionData){
        BaseResponse response = new BaseResponse(true);
        try{
            String stylerId = verificationActionData.getStylerId();
            String action = verificationActionData.getAction() == null ? "" : verificationActionData.getAction().trim().toUpperCase();
            // Accept both the verb (APPROVE) and the state (APPROVED) forms.
            if(VERIFICATION_APPROVED.equals(action) || "APPROVE".equals(action)){
                action = VERIFICATION_APPROVED;
            } else if(VERIFICATION_REJECTED.equals(action) || "REJECT".equals(action)){
                action = VERIFICATION_REJECTED;
            } else if(VERIFICATION_SUSPENDED.equals(action) || "SUSPEND".equals(action)){
                action = VERIFICATION_SUSPENDED;
            } else {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid verification action. Use APPROVE, REJECT or SUSPEND");
                response.setData(EMPTY_DATA);
                return response;
            }
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            String pastTense = action.equals(VERIFICATION_APPROVED) ? "approved" : action.equals(VERIFICATION_REJECTED) ? "rejected" : "suspended";
            StylerEntity styler = stylerOpt.get();
            String previousStatus = styler.getVerificationStatus();
            styler.setVerificationStatus(action);
            stylerRepo.save(styler);
            if(!Objects.equals(previousStatus, action)){
                notifySavedCustomers(stylerId, "VERIFICATION", "Professional verification updated",
                        "The verification status for " + (styler.getBusinessName() == null ? "your saved professional" : styler.getBusinessName())
                                + " is now " + action.toLowerCase(Locale.ROOT) + ".");
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Styler " + pastTense + " successfully");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listAllStylers(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<StylerEntity> getAllStylers = stylerRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(StylerEntity stylerEntity : getAllStylers){
                if(isApprovedStyler(stylerEntity)){
                    result.add(dtoService.stylerAccountDTO(stylerEntity));
                }
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse searchStyler(String businessName){
        BaseResponse response = new BaseResponse(true);
        try{
            List<StylerEntity> getStylerByName = stylerRepo.searchStyler(businessName);
            List<Object> result = new ArrayList<>();
            for(StylerEntity stylerEntity : getStylerByName){
                if(isApprovedStyler(stylerEntity)){
                    result.add(dtoService.stylerAccountDTO(stylerEntity));
                }
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse searchStylerByProvince(String province){
        BaseResponse response = new BaseResponse(true);
        try{
            // Trim defensively so padded values (" Alberta ") still match.
            province = province == null ? "" : province.trim();
            List<StylerEntity> getStylerByProvince = stylerRepo.findByProvinceIgnoreCase(province);
            List<Object> result = new ArrayList<>();
            for(StylerEntity stylerEntity : getStylerByProvince){
                if(isApprovedStyler(stylerEntity)){
                    result.add(dtoService.stylerAccountDTO(stylerEntity));
                }
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse createSubService(SubServiceData subServiceData){
        BaseResponse response = new BaseResponse(true);
        try{
           //Check if styler account
           Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(subServiceData.getStylerId()) ;
           if(isStylerExist.isEmpty()){
               response.setStatusCode(ERROR_STATUS_CODE);
               response.setMessage("Invalid STyler Id");
               response.setData(EMPTY_DATA);
               return response;
           }
           //CHeck if sub service around exist with styler
            Optional<SubServiceEntity> isSubServiceExist = subServiceRepo.isServiceExist(subServiceData.getStylerId(), subServiceData.getName());
           if(isSubServiceExist.isPresent()){
               response.setStatusCode(ERROR_STATUS_CODE);
               response.setMessage("Sub Service name already exit for styler");
               response.setData(EMPTY_DATA);
               return response;
           }
           int durationMinutes = subServiceData.getDurationMinutes();
           if(durationMinutes % SLOT_GRANULARITY_MINUTES != 0){
               return errorResponse(response, "Duration must be in 15-minute increments");
           }
           SubServiceEntity subServiceEntity = new SubServiceEntity(subServiceData);
           subServiceRepo.save(subServiceEntity);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Sub Service created successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Updates a service owned by the authenticated stylist and notifies saved customers when its price changes. */
    public BaseResponse updateSubService(String stylerId, ServiceUpdateData data){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<SubServiceEntity> existing = subServiceRepo.isServiceExistById(stylerId, data.getId());
            if(existing.isEmpty()) return errorResponse(response, "Service not found");
            int duration = data.getDurationMinutes() == null ? DEFAULT_SERVICE_DURATION_MINUTES : data.getDurationMinutes();
            if(duration < MIN_SERVICE_DURATION_MINUTES || duration > MAX_SERVICE_DURATION_MINUTES || duration % SLOT_GRANULARITY_MINUTES != 0){
                return errorResponse(response, "Duration must be in 15-minute increments between 15 and 480 minutes");
            }
            SubServiceEntity service = existing.get();
            String previousPrice = service.getPrice();
            service.setName(AppUtils.sanitizeText(data.getName().trim()));
            service.setPrice(appUtils.currencyFormat(data.getPrice()));
            service.setDurationMinutes(duration);
            subServiceRepo.save(service);
            if(!Objects.equals(previousPrice, service.getPrice())){
                notifySavedCustomers(stylerId, "PRICE", "Saved professional price changed",
                        "The price for " + service.getName() + " is now $" + service.getPrice() + ".");
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Service updated successfully");
            response.setData(dtoService.subServiceDTO(service));
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listOwnStylerSubService(String stylerId){
        return listStylerSubService(stylerId);
    }

    public BaseResponse listStylerSubService(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if styler account exist
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(stylerId) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<SubServiceEntity> getStylerSubService = subServiceRepo.findByStylerId(stylerId);
            List<Object> result = new ArrayList<>();
            for(SubServiceEntity subServiceEntity : getStylerSubService){
                result.add(dtoService.subServiceDTO(subServiceEntity));
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse createStylerPortfolio(StylerPortfolioData stylerPortfolioData){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if styler account
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(stylerPortfolioData.getStylerId()) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid STyler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Category must be a gallery category (normalized to lowercase).
            String category = stylerPortfolioData.getCategory() == null ? "" : stylerPortfolioData.getCategory().trim().toLowerCase();
            if(!GALLERY_CATEGORIES.contains(category)){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid category. Choose one of: " + String.join(", ", GALLERY_CATEGORIES));
                response.setData(EMPTY_DATA);
                return response;
            }
            // Skip exact duplicate uploads (same image URL).
            List<StylerPortfolioEntity> existing = stylerPortfolioRepo.findByStylerId(stylerPortfolioData.getStylerId());
            for(StylerPortfolioEntity item : existing){
                if(item.getImageUrl() != null && item.getImageUrl().equals(stylerPortfolioData.getImageUrl())){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("This image is already in your portfolio");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            // Cap portfolio size so the gallery can grow organically without flooding.
            if(existing.size() >= MAX_STYLER_PORTFOLIO_IMAGES){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Portfolio limit reached (max " + MAX_STYLER_PORTFOLIO_IMAGES + " images)");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Name is optional — default to the category name.
            String name = stylerPortfolioData.getName() == null || stylerPortfolioData.getName().isBlank()
                    ? category : stylerPortfolioData.getName().trim();
            stylerPortfolioData.setName(name);
            stylerPortfolioData.setCategory(category);
            StylerPortfolioEntity stylerPortfolioEntity = new StylerPortfolioEntity(stylerPortfolioData);
            stylerPortfolioRepo.save(stylerPortfolioEntity);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Work added to your portfolio");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Portfolio of the authenticated stylist (identity from the token). */
    public BaseResponse listOwnStylerPortfolio(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(stylerId) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<StylerPortfolioEntity> portfolio = stylerPortfolioRepo.findByStylerId(stylerId);
            Collections.reverse(portfolio);
            List<Object> result = new ArrayList<>();
            for(StylerPortfolioEntity entity : portfolio){
                result.add(dtoService.stylerPortfolioDTO(entity));
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listStylerPortfolio(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if styler account exist
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(stylerId) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<StylerPortfolioEntity> getStylerPortfolio = stylerPortfolioRepo.findByStylerId(stylerId);
            List<Object> result = new ArrayList<>();
            for(StylerPortfolioEntity stylerPortfolioEntity : getStylerPortfolio){
                result.add(dtoService.stylerPortfolioDTO(stylerPortfolioEntity));
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Every portfolio image across all stylists, for the admin moderation view,
     * decorated with the owning stylist's identity.
     */
    public BaseResponse listAllPortfolios(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<StylerPortfolioEntity> portfolios = stylerPortfolioRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(StylerPortfolioEntity item : portfolios){
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", item.getId());
                entry.put("stylerId", item.getStylerId());
                entry.put("imageUrl", item.getImageUrl());
                entry.put("name", item.getName());
                entry.put("category", item.getCategory());
                entry.put("createdAt", item.getCreatedAt());
                Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(item.getStylerId());
                if(stylerOpt.isPresent()){
                    StylerEntity styler = stylerOpt.get();
                    entry.put("businessName", styler.getBusinessName());
                    entry.put("firstname", styler.getFirstname());
                    entry.put("lastname", styler.getLastname());
                    entry.put("emailAddress", styler.getEmailAddress());
                    entry.put("verificationStatus", styler.getVerificationStatus());
                }
                result.add(entry);
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Admin deletes a stylist's gallery image and emails the stylist so they
     * know their work was removed and why.
     */
    public BaseResponse adminDeletePortfolioImage(Long portfolioId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerPortfolioEntity> itemOpt = stylerPortfolioRepo.findById(portfolioId);
            if(itemOpt.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid portfolio image");
                response.setData(EMPTY_DATA);
                return response;
            }
            StylerPortfolioEntity item = itemOpt.get();
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(item.getStylerId());
            String stylerEmail = null;
            String stylerName = "Stylist";
            if(stylerOpt.isPresent()){
                stylerEmail = stylerOpt.get().getEmailAddress();
                stylerName = (stylerOpt.get().getFirstname() + " " + stylerOpt.get().getLastname()).trim();
            }
            String category = item.getCategory() == null ? "" : item.getCategory();
            stylerPortfolioRepo.delete(item);
            // Notify the stylist their work was removed (best effort — never fail the delete on mail).
            if(stylerEmail != null && !stylerEmail.isBlank()){
                try{
                    String subject = "Your " + category + " photo was removed from the gallery";
                    String body = "Dear " + stylerName + ",<br><br>"
                            + "One of your portfolio photos" + (category.isBlank() ? "" : " (category: " + category + ")")
                            + " has been removed from the RapidStylers gallery by our moderation team."
                            + "<br><br>If you believe this was done in error, please reach out to support."
                            + "<br><br>Thank you,<br>The RapidStylers Team";
                    emailConfig.sendSimpleMail(stylerEmail, subject, body);
                } catch (Exception mailEx){
                    LOG.warning("Portfolio delete mail failed: " + mailEx.getMessage());
                }
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Image removed from the gallery and the stylist has been notified");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Stylist removes one of their own gallery images. Ownership is enforced
     * from the authenticated token — a stylist can never delete another
     * stylist's work.
     */
    public BaseResponse deleteOwnPortfolioImage(String stylerId, Long portfolioId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerPortfolioEntity> itemOpt = stylerPortfolioRepo.findById(portfolioId);
            if(itemOpt.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid portfolio image");
                response.setData(EMPTY_DATA);
                return response;
            }
            StylerPortfolioEntity item = itemOpt.get();
            // Only the owner can remove their own work.
            if(!item.getStylerId().equals(stylerId)){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You can only remove your own work");
                response.setData(EMPTY_DATA);
                return response;
            }
            stylerPortfolioRepo.delete(item);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Photo removed from your portfolio");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Emails both parties about an appointment event with role-appropriate
     * wording. Uses the appointment's stored user/styler ids to look up
     * current addresses and names.
     */
    private void sendAppointmentNotification(BookAppointmentEntity appointment, String eventLabel,
                                             String customerHeadline, String customerDetail,
                                             String stylerHeadline, String stylerDetail){
        try{
            if(outboxEventService != null){
                outboxEventService.appointmentNotification(appointment, eventLabel, customerHeadline,
                        customerDetail, stylerHeadline, stylerDetail);
                return;
            }
            String serviceName = "Service";
            if(appointment.getSubServiceId() != null && !appointment.getSubServiceId().isEmpty()){
                try{
                    Optional<SubServiceEntity> sub = subServiceRepo.isServiceExistById(appointment.getStylerId(), Long.parseLong(appointment.getSubServiceId()));
                    if(sub.isPresent() && sub.get().getName() != null){
                        serviceName = sub.get().getName();
                    }
                } catch (Exception ignored){}
            }
            String when = (appointment.getAppointmentDate() == null ? "" : appointment.getAppointmentDate())
                    + (appointment.getArrivalTime() == null || appointment.getArrivalTime().isBlank() ? "" : " at " + appointment.getArrivalTime());
            String price = appointment.getPrice() == null ? "" : appointment.getPrice();
            String servicePrice = appointment.getServicePrice() == null ? price : appointment.getServicePrice();
            String travelFee = appointment.getTravelFee() == null ? "0.00" : appointment.getTravelFee();

            String customerName = "there";
            Optional<UserEntity> userOpt = userRepo.findByUserId(appointment.getUserId());
            if(userOpt.isPresent()){
                customerName = (userOpt.get().getFirstname() + " " + userOpt.get().getLastname()).trim();
                if(customerName.isBlank()) customerName = "there";
            }
            String stylistName = "Stylist";
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(appointment.getStylerId());
            if(stylerOpt.isPresent()){
                stylistName = (stylerOpt.get().getFirstname() + " " + stylerOpt.get().getLastname()).trim();
                if(stylistName.isBlank()) stylistName = stylerOpt.get().getBusinessName();
            }

            String subject = "RapidStylers — Appointment " + eventLabel;
            String details = "<p>Service: " + serviceName + "<br>"
                    + "Date: " + when + "<br>"
                    + "Service price: $" + (servicePrice.isBlank() ? "—" : servicePrice) + "<br>"
                    + "Travel fee: $" + travelFee + "<br>"
                    + "Total: $" + (price.isBlank() ? "—" : price) + "<br>"
                    + "Appointment ref: " + appointment.getAppointmentId() + "</p>"
                    + "<p>Thank you,<br>The RapidStylers Team</p>";

            String customerEmail = userOpt.map(UserEntity::getEmailAddress).orElse(null);
            if(customerEmail != null && !customerEmail.isBlank()){
                String body = "<p>Dear " + customerName + ",</p><p><strong>" + customerHeadline + "</strong></p>"
                        + "<p>" + customerDetail + "</p>" + details;
                emailConfig.sendSimpleMail(customerEmail, subject, body);
            }
            String stylerEmail = stylerOpt.map(StylerEntity::getEmailAddress).orElse(null);
            if(stylerEmail != null && !stylerEmail.isBlank()){
                String body = "<p>Dear " + stylistName + ",</p><p><strong>" + stylerHeadline + "</strong></p>"
                        + "<p>" + stylerDetail + "</p>" + details;
                emailConfig.sendSimpleMail(stylerEmail, subject, body);
            }
        }
        catch (Exception ex){
            LOG.warning("Appointment notification mail failed: " + ex.getMessage());
        }
    }

    /**
     * Sends payment receipts to the customer and stylist when a PaymentIntent
     * is captured. Goes through the outbox (Kafka -> NotificationEventConsumer)
     * when available, falling back to a direct email for tests.
     */
    private void sendPaymentReceipt(BookAppointmentEntity appointment){
        try{
            if(outboxEventService != null){
                outboxEventService.paymentSucceeded(appointment);
                return;
            }
            String serviceName = "Service";
            if(appointment.getSubServiceId() != null && !appointment.getSubServiceId().isEmpty()){
                try{
                    Optional<SubServiceEntity> sub = subServiceRepo.isServiceExistById(appointment.getStylerId(), Long.parseLong(appointment.getSubServiceId()));
                    if(sub.isPresent() && sub.get().getName() != null){
                        serviceName = sub.get().getName();
                    }
                } catch (Exception ignored){}
            }
            String when = (appointment.getAppointmentDate() == null ? "" : appointment.getAppointmentDate())
                    + (appointment.getArrivalTime() == null || appointment.getArrivalTime().isBlank() ? "" : " at " + appointment.getArrivalTime());
            String paid = appointment.getPaymentAmount() == null
                    ? (appointment.getPrice() == null ? "—" : appointment.getPrice()) : appointment.getPaymentAmount();
            String details = "<p>Service: " + serviceName + "<br>"
                    + "Date: " + when + "<br>"
                    + "Total paid: $" + paid + "<br>"
                    + "Appointment ref: " + appointment.getAppointmentId() + "</p>"
                    + "<p>Thank you,<br>The RapidStylers Team</p>";
            String subject = "RapidStylers — Payment receipt";

            Optional<UserEntity> userOpt = userRepo.findByUserId(appointment.getUserId());
            String customerEmail = userOpt.map(UserEntity::getEmailAddress).orElse(null);
            if(customerEmail != null && !customerEmail.isBlank()){
                String name = userOpt.map(u -> (u.getFirstname() + " " + u.getLastname()).trim()).orElse("there");
                emailConfig.sendSimpleMail(customerEmail, subject,
                        "<p>Dear " + (name.isBlank() ? "there" : name) + ",</p>"
                                + "<p><strong>Payment received</strong> — thank you for your business.</p>" + details);
            }
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(appointment.getStylerId());
            String stylerEmail = stylerOpt.map(StylerEntity::getEmailAddress).orElse(null);
            if(stylerEmail != null && !stylerEmail.isBlank()){
                String name = stylerOpt.map(s -> (s.getFirstname() + " " + s.getLastname()).trim()).orElse("Stylist");
                if(name.isBlank()) name = stylerOpt.map(StylerEntity::getBusinessName).orElse("Stylist");
                emailConfig.sendSimpleMail(stylerEmail, subject,
                        "<p>Dear " + name + ",</p>"
                                + "<p><strong>Payment received</strong> — the client's payment has been received.</p>" + details);
            }
        }
        catch (Exception ex){
            LOG.warning("Payment receipt mail failed: " + ex.getMessage());
        }
    }

    @Transactional
    public BaseResponse createStylerReview(ReviewData reviewData){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if user account exist
            Optional<UserEntity> isUserExist= userRepo.findByUserId(reviewData.getUserId()) ;
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Check if styler account exist
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(reviewData.getStylerId()) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Reviews are only valid for a COMPLETED booking with this stylist,
            // and the booking must belong to the authenticated customer.
            Optional<BookAppointmentEntity> bookingOpt = bookAppointmentRepo.findByAppointmentId(reviewData.getBookingId());
            if(bookingOpt.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid booking reference");
                response.setData(EMPTY_DATA);
                return response;
            }
            BookAppointmentEntity booking = bookingOpt.get();
            if(!booking.getUserId().equals(reviewData.getUserId())){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You can only review your own bookings");
                response.setData(EMPTY_DATA);
                return response;
            }
            if(!booking.getStylerId().equals(reviewData.getStylerId())){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This booking is not with this stylist");
                response.setData(EMPTY_DATA);
                return response;
            }
            if(!"0".equals(booking.getStatus())){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You can only review a completed appointment");
                response.setData(EMPTY_DATA);
                return response;
            }
            if(reviewRepo.findByBookingId(reviewData.getBookingId()).isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You have already reviewed this booking");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Get User data
            UserEntity userEntity = isUserExist.get();
            String userName = userEntity.getFirstname() +" " + userEntity.getLastname();
            ReviewEntity reviewEntity = new ReviewEntity();
            reviewEntity.setStylerId(reviewData.getStylerId());
            reviewEntity.setUserId(reviewData.getUserId());
            reviewEntity.setBookingId(reviewData.getBookingId());
            reviewEntity.setUserName(userName);
            int rating = Integer.parseInt(reviewData.getRatingScore());
            if(rating < 1 || rating > 5){
                return errorResponse(response, "Rating Score must be between 1 and 5");
            }
            reviewEntity.setMessage(AppUtils.sanitizeText(reviewData.getReviewMessage()));
            reviewEntity.setModerationStatus("PENDING");
            reviewEntity.setRatingScore(rating);
            reviewRepo.save(reviewEntity);
            audit("" + reviewData.getUserId(), "CUSTOMER", "CREATE_REVIEW", "REVIEW", reviewData.getBookingId(), "Review submitted");

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Rating Submitted Successful");
            response.setData(EMPTY_DATA);
        }
        catch (DataIntegrityViolationException ex){
            throw ex;
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse getReviewModerationQueue(){
        BaseResponse response = new BaseResponse(true);
        try {
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(reviewRepo.findByModerationStatusOrderByCreatedAtDesc("PENDING"));
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse updateReviewModeration(Long reviewId, String action, String adminId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(reviewId == null){
                return errorResponse(response, "Review id is required");
            }
            Optional<ReviewEntity> review = reviewRepo.findById(reviewId);
            String status = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
            if(review.isEmpty() || !Arrays.asList("APPROVED", "REJECTED").contains(status)) return errorResponse(response, "Invalid review or moderation action");
            review.get().setModerationStatus(status);
            reviewRepo.save(review.get());
            audit(adminId, "ADMIN", "MODERATE_REVIEW", "REVIEW", String.valueOf(reviewId), status);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Review moderation updated");
            response.setData(EMPTY_DATA);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse listStylerReviews(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if styler account exist
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(stylerId) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Get Reviews
            List<ReviewEntity> getStylerReviews = reviewRepo.findByStylerIdAndModerationStatus(stylerId, "APPROVED");
            List<Object> result = new ArrayList<>();
            for(ReviewEntity reviewEntity : getStylerReviews){
                result.add(dtoService.stylerReviewDTO(reviewEntity));
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** True when no active booking overlaps the requested service duration. */
    private boolean isWindowFree(String stylerId, String appointmentDate, String arrivalTime, int requestedDurationMinutes){
        final LocalDate requestedDate;
        final LocalTime proposed;
        try {
            requestedDate = parseBookingDate(appointmentDate);
            proposed = parseBookingTime(arrivalTime);
        } catch (DateTimeParseException ex) {
            return false; // fail closed for service-layer callers that bypass DTO validation
        }

        // Canonical rows are the source of truth. The legacy query keeps old
        // rows visible until their temporal columns are backfilled.
        List<BookAppointmentEntity> dayBookings = new ArrayList<>();
        List<BookAppointmentEntity> canonical = bookAppointmentRepo.findByStylerIdAndAppointmentDateValue(stylerId, requestedDate);
        List<BookAppointmentEntity> legacy = bookAppointmentRepo.findByStylerIdAndAppointmentDate(stylerId, appointmentDate);
        if(canonical != null) dayBookings.addAll(canonical);
        if(legacy != null){
            for(BookAppointmentEntity booking : legacy){
                if(dayBookings.stream().noneMatch(existing -> Objects.equals(existing.getId(), booking.getId()))){
                    dayBookings.add(booking);
                }
            }
        }
        if(dayBookings.isEmpty()) return true;
        for(BookAppointmentEntity booking : dayBookings){
            String status = booking.getStatus();
            // Cancelled (4) and rejected (2) free the slot; pending/accepted/completed block it.
            if("2".equals(status) || "4".equals(status)) continue;
            LocalTime start = booking.getAppointmentStartTime();
            if(start == null){
                try {
                    start = parseBookingTime(booking.getArrivalTime());
                } catch (DateTimeParseException ex) {
                    return false; // an active malformed legacy row must never be bypassed
                }
            }
            int bookingDuration = booking.getDurationMinutes() == null
                    ? DEFAULT_SERVICE_DURATION_MINUTES : booking.getDurationMinutes();
            LocalTime end = start.plusMinutes(bookingDuration);
            if(proposed.isBefore(end) && proposed.plusMinutes(requestedDurationMinutes).isAfter(start)){
                return false;
            }
        }
        return true;
    }

    /** True when the complete requested service fits inside weekly hours. */
    private boolean timeWithinAvailability(String stylerId, String appointmentDate, String arrivalTime, int requestedDurationMinutes){
        List<AvailabilityEntity> rows = availabilityRepo.findByStylerId(stylerId);
        if(rows == null || rows.isEmpty()){
            return true; // no hours set — the stylist confirms each request manually
        }
        try{
            int jsWeekday = LocalDate.parse(appointmentDate).getDayOfWeek().getValue() % 7; // 0 = Sunday
            String targetDay = String.valueOf(jsWeekday);
            LocalTime requested = parseBookingTime(arrivalTime);
            for(AvailabilityEntity row : rows){
                if(targetDay.equals(row.getDayOfWeek())){
                    LocalTime start = parseAvailabilityTime(row.getStartTime());
                    LocalTime end = parseAvailabilityTime(row.getEndTime());
                    if(!start.isBefore(end)) return false;
                    if(!requested.isBefore(start) && requested.plusMinutes(requestedDurationMinutes).compareTo(end) <= 0){
                        return true;
                    }
                }
            }
            return false;
        }
        catch (DateTimeParseException ex){
            return false; // fail closed: malformed date/time or hours can never bypass availability
        }
    }

    private LocalDate parseBookingDate(String value){
        return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Canonical booking/search times are 24-hour HH:mm (aligned with the
     * availability API). The 12-hour h:mm a fallback only tolerates legacy
     * rows written before the format alignment so they never hard-block
     * availability/conflict checks.
     */
    private LocalTime parseBookingTime(String value){
        String normalized = value.trim().toUpperCase(Locale.ENGLISH);
        try {
            return LocalTime.parse(normalized, DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH));
        } catch (DateTimeParseException ex) {
            return LocalTime.parse(normalized, DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));
        }
    }

    private LocalTime parseAvailabilityTime(String value){
        return LocalTime.parse(value.trim(), DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH));
    }

    /** Returns the stylist's weekly availability as [{dayOfWeek, startTime, endTime}] sorted by day. */
    public BaseResponse stylerAvailability(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(availabilitySlots(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Replaces the stylist's weekly availability with the supplied slots (delete-all + insert). */
    public BaseResponse updateStylerAvailability(String stylerId, List<AvailabilityData> slots){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            if(slots == null || slots.isEmpty()){
                availabilityRepo.deleteByStylerId(stylerId);
                notifySavedCustomers(stylerId, "AVAILABILITY", "Saved professional availability changed",
                        "The working hours for your saved professional have been cleared. Open their profile to view the latest schedule.");
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("Availability cleared — no working hours set");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Validate every slot before touching the DB. Element-level bean
            // constraints do not cascade into a @Valid List, so enforce them
            // here: day 0-6, HH:mm 24-hour times, end strictly after start.
            for(AvailabilityData slot : slots){
                String day = slot.getDayOfWeek();
                String start = slot.getStartTime();
                String end = slot.getEndTime();
                if(day == null || !day.trim().matches("[0-6]")){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Day of week must be 0 (Sunday) to 6 (Saturday)");
                    response.setData(EMPTY_DATA);
                    return response;
                }
                if(start == null || end == null
                        || !start.matches("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")
                        || !end.matches("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Start and end times must be in HH:mm 24-hour format");
                    response.setData(EMPTY_DATA);
                    return response;
                }
                if(start.compareTo(end) >= 0){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("End time must be after start time for every working day");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            availabilityRepo.deleteByStylerId(stylerId);
            List<AvailabilityEntity> toSave = new ArrayList<>();
            for(AvailabilityData slot : slots){
                toSave.add(new AvailabilityEntity(stylerId, slot));
            }
            availabilityRepo.saveAll(toSave);
            notifySavedCustomers(stylerId, "AVAILABILITY", "Saved professional availability changed",
                    "The working hours for your saved professional have been updated. Open their profile to view the latest schedule.");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Availability updated successfully");
            response.setData(availabilitySlots(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Returns the stylist's date-based exceptions [{blockedDate, reason}] sorted by date. */
    public BaseResponse stylerAvailabilityExceptions(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(exceptionSlots(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Adds a date-based exception (vacation, sick day) for the stylist. */
    public BaseResponse addAvailabilityException(String stylerId, ExceptionData exceptionData){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Check for duplicate date
            if(availabilityExceptionRepo.findByStylerIdAndBlockedDate(stylerId, exceptionData.getBlockedDate()).isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This date is already marked as unavailable");
                response.setData(EMPTY_DATA);
                return response;
            }
            AvailabilityExceptionEntity entity = new AvailabilityExceptionEntity(stylerId, exceptionData);
            availabilityExceptionRepo.save(entity);
            notifySavedCustomers(stylerId, "AVAILABILITY", "Saved professional availability changed",
                    "Your saved professional marked " + exceptionData.getBlockedDate() + " as unavailable.");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Date marked as unavailable");
            response.setData(exceptionSlots(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Removes a date-based exception (restores availability for that date). */
    public BaseResponse deleteAvailabilityException(String stylerId, Long exceptionId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            availabilityExceptionRepo.deleteByStylerIdAndId(stylerId, exceptionId);
            notifySavedCustomers(stylerId, "AVAILABILITY", "Saved professional availability changed",
                    "A previously unavailable date was restored for your saved professional.");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Date restored to available");
            response.setData(exceptionSlots(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** True when the requested date is not blocked by a vacation/sick exception. */
    private boolean isDateNotException(String stylerId, String appointmentDate){
        return !availabilityExceptionRepo.findByStylerIdAndBlockedDate(stylerId, appointmentDate).isPresent();
    }

    /** Shared helper: stylist's exception dates as a sorted list of maps. */
    private List<Object> exceptionSlots(String stylerId){
        List<AvailabilityExceptionEntity> rows = availabilityExceptionRepo.findByStylerId(stylerId);
        List<Object> result = new ArrayList<>();
        if(rows == null || rows.isEmpty()){
            return result;
        }
        rows.sort(Comparator.comparing(AvailabilityExceptionEntity::getBlockedDate));
        for(AvailabilityExceptionEntity row : rows){
            HashMap<String, String> slot = new HashMap<>();
            slot.put("id", String.valueOf(row.getId()));
            slot.put("blockedDate", row.getBlockedDate());
            slot.put("reason", row.getReason());
            result.add(slot);
        }
        return result;
    }

    /** Shared helper: stylist's weekly slots as a sorted list of maps, or empty list when none set. */
    private List<Object> availabilitySlots(String stylerId){
        List<AvailabilityEntity> rows = availabilityRepo.findByStylerId(stylerId);
        List<Object> result = new ArrayList<>();
        if(rows == null || rows.isEmpty()){
            return result;
        }
        rows.sort(Comparator.comparing(AvailabilityEntity::getDayOfWeek));
        for(AvailabilityEntity row : rows){
            HashMap<String, String> slot = new HashMap<>();
            slot.put("dayOfWeek", row.getDayOfWeek());
            slot.put("startTime", row.getStartTime());
            slot.put("endTime", row.getEndTime());
            result.add(slot);
        }
        return result;
    }

    public BaseResponse getStylerDetails(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try {
            //Check if styler account exist
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(stylerId) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Only approved professionals are publicly visible
            if(!isApprovedStyler(isStylerExist.get())){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This professional is not yet available");
                response.setData(EMPTY_DATA);
                return response;
            }
            int ratingPercentage= 0;
            //Get Styler Information
            StylerEntity stylerEntity = isStylerExist.get();
            //Get Styler Sub service information
            List<SubServiceEntity> getStylerSubService = subServiceRepo.findByStylerId(stylerId);
            List<Object> subServiceResult = new ArrayList<>();
            for(SubServiceEntity subServiceEntity : getStylerSubService){
                subServiceResult.add(dtoService.subServiceDTO(subServiceEntity));
            }
            //Get Styler Portfolio information
            List<StylerPortfolioEntity> getStylerPortfolio = stylerPortfolioRepo.findByStylerId(stylerId);
            List<Object> stylerPortfolioResult = new ArrayList<>();
            for(StylerPortfolioEntity stylerPortfolioEntity : getStylerPortfolio){
                stylerPortfolioResult.add(dtoService.stylerPortfolioDTO(stylerPortfolioEntity));
            }
            //Get Styler Portfolio reviews
            List<ReviewEntity> getStylerReviews = reviewRepo.findByStylerIdAndModerationStatus(stylerId, "APPROVED");
            List<Object> stylerReviewResult = new ArrayList<>();
            int totalRating = 0;
            double ratingCount = 0;
            for(ReviewEntity reviewEntity : getStylerReviews){
                 ratingCount += 1;
                 totalRating += reviewEntity.getRatingScore();
                stylerReviewResult.add(dtoService.stylerReviewDTO(reviewEntity));
            }
            ratingPercentage = (int) ((totalRating/(ratingCount * 5)) *100);
            Collections.reverse(stylerReviewResult);
            Collections.reverse(stylerPortfolioResult);
            Collections.reverse(subServiceResult);
            HashMap<String, Object> stylerInformationMap = new HashMap<>();
            stylerInformationMap.put("stylerInformation", dtoService.stylerAccountDTO(stylerEntity));
            stylerInformationMap.put("stylerSubService" , subServiceResult);
            stylerInformationMap.put("stylerPortfolio" , stylerPortfolioResult);
            stylerInformationMap.put("stylerReviews" , stylerReviewResult);
            stylerInformationMap.put("ratingPercentage", String.valueOf(ratingPercentage));
            // Real appointment tally for the profile stats — no more hardcoded "500".
            List<BookAppointmentEntity> stylerAppointments = bookAppointmentRepo.findByStylerId(stylerId);
            stylerInformationMap.put("appointmentCount", String.valueOf(stylerAppointments == null ? 0 : stylerAppointments.size()));
            // Weekly availability so the booking modal can show/enforce real working hours.
            stylerInformationMap.put("availability", availabilitySlots(stylerId));
            // Date-based exceptions (vacation, sick day) so the modal can gray out blocked days.
            stylerInformationMap.put("exceptions", exceptionSlots(stylerId));
            // Active bookings (date + arrival time) so the modal can gray out taken
            // windows. Cancelled (4) and rejected (2) appointments free the slot.
            List<Object> bookedSlots = new ArrayList<>();
            if(stylerAppointments != null){
                for(BookAppointmentEntity appointment : stylerAppointments){
                    if("2".equals(appointment.getStatus()) || "4".equals(appointment.getStatus())) continue;
                    HashMap<String, String> slot = new HashMap<>();
                    slot.put("appointmentDate", appointment.getAppointmentDate());
                    slot.put("arrivalTime", appointment.getArrivalTime());
                    slot.put("status", appointment.getStatus());
                    slot.put("durationMinutes", String.valueOf(appointment.getDurationMinutes() == null
                            ? DEFAULT_SERVICE_DURATION_MINUTES : appointment.getDurationMinutes()));
                    bookedSlots.add(slot);
                }
            }
            stylerInformationMap.put("bookedSlots", bookedSlots);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(stylerInformationMap);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Saves an approved stylist for the authenticated customer. */
    public BaseResponse saveStylist(String userId, String stylerId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(userRepo.findByUserId(userId).isEmpty()){
                return errorResponse(response, "Invalid User Id");
            }
            Optional<StylerEntity> styler = stylerRepo.findByStylerId(stylerId);
            if(styler.isEmpty() || !isApprovedStyler(styler.get())){
                return errorResponse(response, "This professional is not available");
            }
            if(savedStylistRepo.findByUserIdAndStylerId(userId, stylerId).isEmpty()){
                savedStylistRepo.save(new SavedStylistEntity(userId, stylerId));
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Professional saved");
            response.setData(Collections.singletonMap("saved", true));
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Removes a saved stylist only from the authenticated customer's collection. */
    public BaseResponse removeSavedStylist(String userId, String stylerId){
        BaseResponse response = new BaseResponse(true);
        try {
            savedStylistRepo.deleteByUserIdAndStylerId(userId, stylerId);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Professional removed from saved list");
            response.setData(Collections.singletonMap("saved", false));
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Returns the authenticated customer's saved stylists with live profile DTOs. */
    public BaseResponse listSavedStylists(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(userRepo.findByUserId(userId).isEmpty()){
                return errorResponse(response, "Invalid User Id");
            }
            List<Object> result = new ArrayList<>();
            for(SavedStylistEntity saved : savedStylistRepo.findByUserIdOrderByCreatedAtDesc(userId)){
                stylerRepo.findByStylerId(saved.getStylerId())
                        .filter(this::isApprovedStyler)
                        .ifPresent(styler -> result.add(dtoService.stylerAccountDTO(styler)));
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Returns customer notifications and the unread count for the authenticated account. */
    public BaseResponse listNotifications(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(userRepo.findByUserId(userId).isEmpty()){
                return errorResponse(response, "Invalid User Id");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for(NotificationEntity notification : notificationRepo.findByUserIdOrderByCreatedAtDesc(userId)){
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", notification.getId());
                item.put("stylerId", notification.getStylerId());
                item.put("type", notification.getType());
                item.put("title", notification.getTitle());
                item.put("message", notification.getMessage());
                item.put("read", notification.isRead());
                item.put("createdAt", notification.getCreatedAt());
                result.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", result);
            data.put("unreadCount", notificationRepo.countByUserIdAndReadFalse(userId));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Marks one notification as read, scoped to the authenticated customer. */
    public BaseResponse markNotificationRead(String userId, Long notificationId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<NotificationEntity> notification = notificationRepo.findByIdAndUserId(notificationId, userId);
            if(notification.isEmpty()) return errorResponse(response, "Notification not found");
            notification.get().setRead(true);
            notificationRepo.save(notification.get());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Notification marked as read");
            response.setData(Collections.emptyMap());
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Marks all notifications read for the authenticated customer. */
    public BaseResponse markAllNotificationsRead(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            List<NotificationEntity> notifications = notificationRepo.findByUserIdOrderByCreatedAtDesc(userId);
            notifications.forEach(notification -> notification.setRead(true));
            notificationRepo.saveAll(notifications);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Notifications marked as read");
            response.setData(Collections.emptyMap());
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Reads and updates saved-stylist notification preferences for one customer. */
    public BaseResponse getNotificationPreferences(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<UserEntity> user = userRepo.findByUserId(userId);
            if(user.isEmpty()) return errorResponse(response, "Invalid User Id");
            Map<String, Boolean> data = new LinkedHashMap<>();
            data.put("availability", !Boolean.FALSE.equals(user.get().getNotifySavedAvailability()));
            data.put("price", !Boolean.FALSE.equals(user.get().getNotifySavedPrice()));
            data.put("verification", !Boolean.FALSE.equals(user.get().getNotifySavedVerification()));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateNotificationPreferences(String userId, NotificationPreferencesData data){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<UserEntity> user = userRepo.findByUserId(userId);
            if(user.isEmpty()) return errorResponse(response, "Invalid User Id");
            UserEntity entity = user.get();
            if(data.getAvailability() != null) entity.setNotifySavedAvailability(data.getAvailability());
            if(data.getPrice() != null) entity.setNotifySavedPrice(data.getPrice());
            if(data.getVerification() != null) entity.setNotifySavedVerification(data.getVerification());
            userRepo.save(entity);
            return getNotificationPreferences(userId);
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Creates an in-app event and sends optional email for every saved customer. */
    private void notifySavedCustomers(String stylerId, String type, String title, String message){
        if(notificationRepo == null || savedStylistRepo == null) return;
        try {
            List<SavedStylistEntity> allSaved = savedStylistRepo.findAll();
            for(SavedStylistEntity saved : allSaved){
                if(!stylerId.equals(saved.getStylerId())) continue;
                Optional<UserEntity> user = userRepo.findByUserId(saved.getUserId());
                if(user.isEmpty()) continue;
                notificationRepo.save(new NotificationEntity(saved.getUserId(), stylerId, type, title, message));
                boolean emailEnabled = ("AVAILABILITY".equals(type) && !Boolean.FALSE.equals(user.get().getNotifySavedAvailability()))
                        || ("PRICE".equals(type) && !Boolean.FALSE.equals(user.get().getNotifySavedPrice()))
                        || ("VERIFICATION".equals(type) && !Boolean.FALSE.equals(user.get().getNotifySavedVerification()));
                if(emailEnabled && user.get().getEmailAddress() != null && !user.get().getEmailAddress().isBlank()){
                    try {
                        emailConfig.sendSimpleMail(user.get().getEmailAddress(), title, "Dear "
                                + user.get().getFirstname() + ",<br><br>" + message
                                + "<br><br>The RapidStylers Team");
                    } catch (Exception mailEx) {
                        LOG.warning("Saved stylist notification email failed: " + mailEx.getMessage());
                    }
                }
            }
        } catch (Exception ex) {
            LOG.warning("Saved stylist notification failed: " + ex.getMessage());
        }
    }

    public BaseResponse createSupportTicket(String userId, SupportTicketData data){
        BaseResponse response = new BaseResponse(true);
        try {
            if(userRepo.findByUserId(userId).isEmpty()) return errorResponse(response, "Invalid User Id");
            SupportTicketEntity ticket = supportTicketRepo.save(new SupportTicketEntity(userId,
                    AppUtils.sanitizeText(data.getSubject().trim()), AppUtils.sanitizeText(data.getMessage().trim())));
            audit(userId, "CUSTOMER", "CREATE_SUPPORT_TICKET", "SUPPORT_TICKET", String.valueOf(ticket.getId()), ticket.getSubject());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Support ticket created");
            response.setData(ticket.getId());
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse listSupportTickets(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(userRepo.findByUserId(userId).isEmpty()) return errorResponse(response, "Invalid User Id");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(supportTicketRepo.findByUserIdOrderByUpdatedAtDesc(userId));
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse listAllSupportTickets(){
        BaseResponse response = new BaseResponse(true);
        try {
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(supportTicketRepo.findAll());
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse updateSupportTicket(SupportTicketActionData data, String adminId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<SupportTicketEntity> ticketOpt = supportTicketRepo.findById(data.getTicketId());
            String status = data.getStatus().trim().toUpperCase(Locale.ROOT);
            if(ticketOpt.isEmpty() || !Arrays.asList("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED").contains(status)) return errorResponse(response, "Invalid support ticket or status");
            SupportTicketEntity ticket = ticketOpt.get();
            ticket.setStatus(status);
            ticket.setAdminResponse(AppUtils.sanitizeText(data.getAdminResponse()));
            ticket.setUpdatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            supportTicketRepo.save(ticket);
            audit(adminId, "ADMIN", "UPDATE_SUPPORT_TICKET", "SUPPORT_TICKET", String.valueOf(ticket.getId()), status);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Support ticket updated");
            response.setData(ticket);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse getCommissionSetting(String adminId){
        BaseResponse response = new BaseResponse(true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commissionPercent", effectiveCommissionPercent());
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return response;
    }

    public BaseResponse updateCommissionSetting(String adminId, double percent){
        BaseResponse response = new BaseResponse(true);
        try{
            if(percent < 0 || percent > 100){
                return errorResponse(response, "Commission must be between 0 and 100");
            }
            PlatformSettingEntity setting = platformSettingRepo.findBySettingKey(COMMISSION_SETTING_KEY)
                    .orElseGet(() -> new PlatformSettingEntity(COMMISSION_SETTING_KEY, "10"));
            setting.setSettingValue(String.valueOf(percent));
            platformSettingRepo.save(setting);
            cachedCommissionPercent = percent;
            audit(adminId, "ADMIN", "UPDATE_COMMISSION", "SETTINGS", COMMISSION_SETTING_KEY, String.valueOf(percent));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Commission updated");
            response.setData(EMPTY_DATA);
        } catch(Exception ex){
            LOG.warning("Commission update failed: " + ex.getMessage());
            return errorResponse(response, "Could not update commission");
        }
        return response;
    }

    /** Admin-only: sends a test email through the same EmailConfig path used for real notifications. */
    public BaseResponse sendTestEmail(String adminId, String recipient){
        BaseResponse response = new BaseResponse(true);
        try{
            if(recipient == null || recipient.isBlank()
                    || !recipient.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")){
                return errorResponse(response, "Enter a valid recipient email address");
            }
            String result = emailConfig.sendSimpleMail(recipient, "RapidStylers test email",
                    "<h2>RapidStylers</h2><p>This is a test email sent from the admin console to verify email delivery.</p>");
            if("Mail Sent Successfully...".equals(result)){
                audit(adminId, "ADMIN", "SEND_TEST_EMAIL", "EMAIL", recipient, "sent");
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("Test email sent to " + recipient);
                response.setData(EMPTY_DATA);
            } else {
                return errorResponse(response, "Email provider rejected the send — check the backend log");
            }
        } catch(Exception ex){
            LOG.warning("Test email failed: " + ex.getMessage());
            return errorResponse(response, "Could not send test email");
        }
        return response;
    }

    public BaseResponse getAdminKpis(){
        BaseResponse response = new BaseResponse(true);
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("customers", userRepo.count());
            data.put("stylists", stylerRepo.count());
            data.put("approvedStylists", stylerRepo.findAll().stream().filter(this::isApprovedStyler).count());
            data.put("appointments", bookAppointmentRepo.count());
            data.put("pendingAppointments", bookAppointmentRepo.findAll().stream().filter(a -> "1".equals(a.getStatus())).count());
            data.put("completedAppointments", bookAppointmentRepo.findAll().stream().filter(a -> "0".equals(a.getStatus())).count());
            data.put("openSupportTickets", supportTicketRepo.countByStatus("OPEN"));
            data.put("reviews", reviewRepo.count());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse listAuditLogs(){
        BaseResponse response = new BaseResponse(true);
        try {
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(auditLogRepo.findTop100ByOrderByCreatedAtDesc());
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse getLoyaltyAccount(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(userRepo.findByUserId(userId).isEmpty()) return errorResponse(response, "Invalid User Id");
            LoyaltyAccountEntity account = loyaltyAccountRepo.findByUserId(userId).orElseGet(() -> new LoyaltyAccountEntity(userId, "RS-" + appUtils.randomAlphanumeric(8).toUpperCase(Locale.ROOT)));
            if(account.getId() == null) loyaltyAccountRepo.save(account);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(account);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse createReferral(String userId, String referralCode){
        BaseResponse response = new BaseResponse(true);
        try {
            if(referralCode == null || referralCode.trim().isEmpty()){
                return errorResponse(response, "Referral code cannot be empty");
            }
            Optional<LoyaltyAccountEntity> referrer = loyaltyAccountRepo.findByReferralCode(referralCode.trim().toUpperCase(Locale.ROOT));
            if(referrer.isEmpty() || referrer.get().getUserId().equals(userId)) return errorResponse(response, "Invalid referral code");
            if(referralRepo.findByReferredUserId(userId).isPresent()) return errorResponse(response, "Referral already applied");
            ReferralEntity referral = referralRepo.save(new ReferralEntity(referrer.get().getUserId(), userId, referralCode.trim().toUpperCase(Locale.ROOT)));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Referral applied");
            response.setData(referral);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse getStylerByService(String serviceId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<ServiceEntity> isServiceIdExist = serviceRepo.findById(Long.valueOf(serviceId));
            if(isServiceIdExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid service Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<StylerEntity> getStylerData = stylerRepo.findByServiceTypeId(serviceId);
            List<Object> result = new ArrayList<>();
            for(StylerEntity stylerEntity : getStylerData){
                if(isApprovedStyler(stylerEntity)){
                    result.add(dtoService.stylerAccountDTO(stylerEntity));
                }
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse estimateBooking(BookAppointmentData bookAppointmentData){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<StylerEntity> styler = stylerRepo.findByStylerId(bookAppointmentData.getStylerId());
            if(styler.isEmpty()){
                return errorResponse(response, "Invalid Styler Id");
            }
            if(!isApprovedStyler(styler.get())){
                return errorResponse(response, "This professional is not yet available for booking");
            }
            Optional<SubServiceEntity> subService = subServiceRepo.isServiceExistById(
                    bookAppointmentData.getStylerId(), Long.parseLong(bookAppointmentData.getSubServiceId()));
            if(subService.isEmpty()){
                return errorResponse(response, "Invalid service for this stylist");
            }
            TravelPricing pricing = calculateTravelPricing(bookAppointmentData, styler.get(), subService.get().getPrice());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(pricing.toMap());
        } catch(NumberFormatException ex){
            return errorResponse(response, "Invalid service for this stylist");
        } catch(IllegalArgumentException ex){
            return errorResponse(response, ex.getMessage());
        }
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse bookAppointment(BookAppointmentData bookAppointmentData){
        BaseResponse response = new BaseResponse(true);
        IdempotencyService.Claim idempotencyClaim = null;
        try{
            if (idempotencyService != null && bookAppointmentData.getIdempotencyKey() != null
                    && !bookAppointmentData.getIdempotencyKey().trim().isEmpty()) {
                idempotencyClaim = idempotencyService.claim("book-appointment", bookAppointmentData.getUserId(),
                        bookAppointmentData.getIdempotencyKey(), Duration.ofHours(24));
                if (idempotencyClaim.isDuplicate()) {
                    // Check for a stored response from a previous successful booking
                    String storedResponse = idempotencyService.getStoredResponse(
                            "book-appointment", bookAppointmentData.getUserId(), bookAppointmentData.getIdempotencyKey());
                    if (storedResponse != null) {
                        LOG.info("Replaying stored idempotency response for booking");
                        return new BaseResponse(); // The controller will deserialize the stored JSON
                    }
                    return errorResponse(response, "This booking request is already being processed or was already submitted");
                }
            }
            LocalDate appointmentDate = parseBookingDate(bookAppointmentData.getAppointmentDate());
            LocalTime appointmentStart = parseBookingTime(bookAppointmentData.getArrivalTime());
            if(appointmentDate.isBefore(LocalDate.now(applicationZone()))){
                return errorResponse(response, "Appointment date cannot be in the past");
            }
            // The marketplace exposes quarter-hour boundaries; reject unsupported API values too.
            if(appointmentStart.getMinute() % SLOT_GRANULARITY_MINUTES != 0){
                return errorResponse(response, "Arrival time must start on a 15-minute boundary");
            }

            Optional<UserEntity> isUserExist = userRepo.findByUserId(bookAppointmentData.getUserId());
            if(isUserExist.isEmpty()){
                return errorResponse(response, "Invalid User Id, Kindly create account");
            }
            // This row lock serializes all booking attempts for one stylist before
            // the availability/conflict read and the unique slot inserts.
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerIdForUpdate(bookAppointmentData.getStylerId());
            if(isStylerExist.isEmpty()){
                return errorResponse(response, "Invalid Styler Id");
            }
            if(!isApprovedStyler(isStylerExist.get())){
                return errorResponse(response, "This professional is not yet available for booking");
            }
            // With payments live, a stylist must have finished Connect onboarding
            // to receive payouts — the marketplace blocks booking until then.
            if(stripeService.isConfigured() && !"COMPLETE".equals(isStylerExist.get().getConnectOnboardingStatus())){
                return errorResponse(response, "This professional is not yet available for online booking");
            }

            String subServiceId = bookAppointmentData.getSubServiceId();
            if(subServiceId == null || subServiceId.trim().isEmpty()){
                return errorResponse(response, "A service must be selected");
            }
            String price;
            int durationMinutes;
            TravelPricing pricing;
            try {
                Optional<SubServiceEntity> subService = subServiceRepo.isServiceExistById(
                        bookAppointmentData.getStylerId(), Long.parseLong(subServiceId));
                if(subService.isEmpty()){
                    return errorResponse(response, "Invalid service for this stylist");
                }
                // The client price and duration are never trusted.
                price = subService.get().getPrice();
                durationMinutes = serviceDuration(subService.get().getDurationMinutes());
                pricing = calculateTravelPricing(bookAppointmentData, isStylerExist.get(), price);
            } catch(NumberFormatException ex){
                return errorResponse(response, "Invalid service for this stylist");
            }

            List<String> activeStatuses = Arrays.asList("1", "3");
            boolean canonicalDuplicate = !bookAppointmentRepo
                    .findByUserIdAndStylerIdAndAppointmentDateValueAndAppointmentStartTimeAndStatusIn(
                            bookAppointmentData.getUserId(), bookAppointmentData.getStylerId(),
                            appointmentDate, appointmentStart, activeStatuses)
                    .isEmpty();
            boolean legacyDuplicate = !bookAppointmentRepo.findDuplicateBooking(
                    bookAppointmentData.getUserId(), bookAppointmentData.getStylerId(),
                    bookAppointmentData.getAppointmentDate(), bookAppointmentData.getArrivalTime()).isEmpty();
            if(canonicalDuplicate || legacyDuplicate){
                return errorResponse(response, "You already have a booking request for this date and time");
            }
            if(!isWindowFree(bookAppointmentData.getStylerId(),
                    bookAppointmentData.getAppointmentDate(), bookAppointmentData.getArrivalTime(), durationMinutes)){
                return errorResponse(response, "The stylist already has a booking in this time window — please pick a different time");
            }
            if(!timeWithinAvailability(bookAppointmentData.getStylerId(),
                    bookAppointmentData.getAppointmentDate(), bookAppointmentData.getArrivalTime(), durationMinutes)){
                return errorResponse(response, "The stylist is not available at this time — please pick a time within their working hours");
            }
            if(!isDateNotException(bookAppointmentData.getStylerId(), bookAppointmentData.getAppointmentDate())){
                return errorResponse(response, "The stylist is unavailable on this date — please choose another day");
            }

            bookAppointmentData.setPrice(pricing.totalPrice);
            BookAppointmentEntity appointment = new BookAppointmentEntity(bookAppointmentData, durationMinutes);
            appointment.setServicePrice(pricing.servicePrice);
            appointment.setTravelFee(pricing.travelFee);
            appointment.setIncludedTravelKm(pricing.includedTravelKm);
            appointment.setTravelDistanceKm(pricing.travelDistanceKm);
            appointment.setBillableTravelKm(pricing.billableTravelKm);
            appointment.setExtraTravelRatePerKm(pricing.extraTravelRatePerKm);
            appointment.setPrice(pricing.totalPrice);

            // Stripe: authorize near-term bookings immediately. Future bookings
            // receive a due timestamp and are authorized by the payment scheduler
            // inside Stripe's safe card-authorization window.
            if(stripeService.isConfigured() && shouldAuthorizeNow(appointmentDate, appointmentStart)){
                Optional<CardDetailsEntity> card = cardDetailsRepo.findByUserId(bookAppointmentData.getUserId());
                if(card.isEmpty() || card.get().getStripeCustomerId() == null || card.get().getStripePaymentMethodId() == null){
                    return paymentErrorResponse(response, "NO_PAYMENT_METHOD",
                            "Please add a payment method to your account before booking");
                }
                try {
                    String connectAccountId = isStylerExist.get().getStripeConnectAccountId();
                    String destination = connectAccountId == null || connectAccountId.isBlank() ? null : connectAccountId;
                    long amountCents = centsFromPrice(pricing.totalPrice);
                    PaymentIntent intent = stripeService.authorizeBookingPayment(
                            card.get().getStripeCustomerId(), card.get().getStripePaymentMethodId(),
                            amountCents, appointment.getAppointmentId(), destination, commissionCents(amountCents));
                    appointment.setPaymentIntentId(intent.getId());
                    appointment.setPaymentStatus("AUTHORIZED");
                    appointment.setPaymentAmount(pricing.totalPrice);
                    appointment.setPaymentAuthorizationDueAt(null);
                } catch(com.stripe.exception.CardException ex){
                    // Distinguish card problems so the frontend can prompt the
                    // customer to update their saved card instead of showing a generic error.
                    if("expired_card".equals(ex.getCode())){
                        return paymentErrorResponse(response, "CARD_EXPIRED",
                                "Your saved card has expired. Please update your card and try again.");
                    }
                    String detail = ex.getDeclineCode() == null || ex.getDeclineCode().isBlank()
                            ? "" : " (" + ex.getDeclineCode().replace('_', ' ') + ")";
                    return paymentErrorResponse(response, "CARD_DECLINED",
                            "Your card was declined" + detail + ". Please update your card and try again.");
                } catch(com.stripe.exception.StripeException ex){
                    LOG.warning("Payment authorization failed: " + ex.getMessage());
                    return paymentErrorResponse(response, "PAYMENT_ERROR",
                            "Payment could not be authorized — please check your card or try again");
                }
            }
            if(stripeService.isConfigured() && !shouldAuthorizeNow(appointmentDate, appointmentStart)){
                appointment.setPaymentStatus("PAYMENT_SCHEDULED");
                appointment.setPaymentAmount(pricing.totalPrice);
                appointment.setPaymentAuthorizationDueAt(paymentAuthorizationDueAt(appointmentDate, appointmentStart));
            }
            bookAppointmentRepo.saveAndFlush(appointment);
            reserveBookingSlots(appointment, appointmentDate, appointmentStart, durationMinutes);
            audit(bookAppointmentData.getUserId(), "CUSTOMER", "CREATE_BOOKING", "APPOINTMENT",
                    appointment.getAppointmentId(), "Booking request created");

            sendAppointmentNotification(appointment, "Request",
                    "Booking request received",
                    "Your booking request has been received and is awaiting the professional's confirmation.",
                    "New booking request",
                    "A client has requested an appointment. Please confirm or decline it from your dashboard.");

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Appointment booked successfully");
            response.setData(EMPTY_DATA);

            // Store response for idempotency replay on duplicate requests
            if (idempotencyClaim != null && idempotencyClaim.isAcquired() && idempotencyService != null
                    && bookAppointmentData.getIdempotencyKey() != null) {
                try {
                    idempotencyService.storeResponse("book-appointment", bookAppointmentData.getUserId(),
                            bookAppointmentData.getIdempotencyKey(), objectMapper.writeValueAsString(response));
                } catch (Exception ignored) { }
            }
        }
        catch(DataIntegrityViolationException ex){
            // Let the transactional proxy roll back the appointment and slot
            // rows together. The controller advice returns the conflict response.
            LOG.warning("Booking slot collision: " + ex.getMessage());
            if (idempotencyClaim != null && idempotencyClaim.isAcquired() && idempotencyService != null) {
                idempotencyService.release(idempotencyClaim);
            }
            throw ex;
        }
        catch(DateTimeParseException ex){
            if (idempotencyClaim != null && idempotencyClaim.isAcquired() && idempotencyService != null) {
                idempotencyService.release(idempotencyClaim);
            }
            return errorResponse(response, "Invalid appointment date or arrival time");
        }
        catch(IllegalArgumentException ex){
            return errorResponse(response, ex.getMessage());
        }
        return response;
    }

    private boolean shouldAuthorizeNow(LocalDate appointmentDate, LocalTime appointmentStart){
        ZonedDateTime scheduled = ZonedDateTime.of(appointmentDate, appointmentStart, applicationZone());
        return !scheduled.isAfter(ZonedDateTime.now(applicationZone()).plusDays(paymentAuthorizationWindowDays));
    }

    private LocalDateTime paymentAuthorizationDueAt(LocalDate appointmentDate, LocalTime appointmentStart){
        ZonedDateTime scheduled = ZonedDateTime.of(appointmentDate, appointmentStart, applicationZone())
                .minusHours(paymentAuthorizationLeadHours);
        ZonedDateTime now = ZonedDateTime.now(applicationZone());
        return (scheduled.isBefore(now) ? now : scheduled).toLocalDateTime();
    }

    private LocalDateTime paymentAuthorizationDueAt(BookAppointmentEntity appointment){
        if(appointment.getPaymentAuthorizationDueAt() != null){
            return appointment.getPaymentAuthorizationDueAt();
        }
        LocalDate date = appointment.getAppointmentDateValue();
        LocalTime start = appointment.getAppointmentStartTime();
        if(date == null || start == null){
            date = parseBookingDate(appointment.getAppointmentDate());
            start = parseBookingTime(appointment.getArrivalTime());
        }
        return paymentAuthorizationDueAt(date, start);
    }

    /**
     * Authorizes a scheduled booking exactly once. When captureAfterAuthorization
     * is true, the stylist has already accepted and the authorized amount is
     * captured immediately after Stripe confirms the hold.
     */
    @Transactional(rollbackFor = Exception.class)
    PaymentAttemptResult authorizeScheduledPayment(BookAppointmentEntity appointment, boolean captureAfterAuthorization){
        try {
            Optional<CardDetailsEntity> card = cardDetailsRepo.findByUserId(appointment.getUserId());
            if(card.isEmpty() || card.get().getStripeCustomerId() == null || card.get().getStripePaymentMethodId() == null){
                appointment.setPaymentStatus("PAYMENT_FAILED");
                appointment.setPaymentFailureCode("NO_PAYMENT_METHOD");
                bookAppointmentRepo.save(appointment);
                return PaymentAttemptResult.failure("NO_PAYMENT_METHOD", "Please add a payment method to complete this booking");
            }
            Optional<StylerEntity> styler = stylerRepo.findByStylerId(appointment.getStylerId());
            String destination = styler.filter(s -> s.getStripeConnectAccountId() != null && !s.getStripeConnectAccountId().isBlank())
                    .map(StylerEntity::getStripeConnectAccountId).orElse(null);
            long amountCents = centsFromPrice(appointment.getPaymentAmount() == null ? appointment.getPrice() : appointment.getPaymentAmount());
            PaymentIntent intent = stripeService.authorizeBookingPayment(
                    card.get().getStripeCustomerId(), card.get().getStripePaymentMethodId(),
                    amountCents, appointment.getAppointmentId(), destination, commissionCents(amountCents));
            appointment.setPaymentIntentId(intent.getId());
            appointment.setPaymentStatus("AUTHORIZED");
            appointment.setPaymentFailureCode(null);
            appointment.setPaymentAuthorizationDueAt(null);
            if(captureAfterAuthorization){
                stripeService.captureBookingPayment(intent.getId());
                appointment.setPaymentStatus("CAPTURED");
                sendPaymentReceipt(appointment);
            }
            bookAppointmentRepo.save(appointment);
            return PaymentAttemptResult.success();
        } catch(com.stripe.exception.CardException ex){
            String code = "authentication_required".equals(ex.getCode()) ? "PAYMENT_REQUIRES_ACTION"
                    : "expired_card".equals(ex.getCode()) ? "CARD_EXPIRED" : "CARD_DECLINED";
            appointment.setPaymentStatus("PAYMENT_REQUIRES_ACTION".equals(code) ? "PAYMENT_REQUIRES_ACTION" : "PAYMENT_FAILED");
            appointment.setPaymentFailureCode(code);
            bookAppointmentRepo.save(appointment);
            String message = "PAYMENT_REQUIRES_ACTION".equals(code)
                    ? "Please return to RapidStylers to confirm your payment."
                    : "Your saved card could not be charged. Please update it and try again.";
            return PaymentAttemptResult.failure(code, message);
        } catch(Exception ex){
            LOG.warning("Scheduled payment authorization failed: " + ex.getMessage());
            appointment.setPaymentStatus("PAYMENT_FAILED");
            appointment.setPaymentFailureCode("PAYMENT_ERROR");
            bookAppointmentRepo.save(appointment);
            return PaymentAttemptResult.failure("PAYMENT_ERROR", "Payment could not be completed. Please try again.");
        }
    }

    @Scheduled(fixedDelayString = "${app.booking.payment-scheduler-delay-ms:60000}")
    @Transactional(rollbackFor = Exception.class)
    public void processDuePaymentAuthorizations(){
        if(!stripeService.isConfigured()) return;
        LocalDateTime now = LocalDateTime.now(applicationZone());
        List<BookAppointmentEntity> due = bookAppointmentRepo
                .findByPaymentStatusAndPaymentAuthorizationDueAtBefore("PAYMENT_ACCEPTED_SCHEDULED", now);
        for(BookAppointmentEntity appointment : due){
            if(!"3".equals(appointment.getStatus())) continue;
            authorizeScheduledPayment(appointment, true);
        }
    }

    private static class PaymentAttemptResult {
        private final boolean success;
        private final String code;
        private final String message;
        private PaymentAttemptResult(boolean success, String code, String message){
            this.success = success;
            this.code = code;
            this.message = message;
        }
        private static PaymentAttemptResult success(){ return new PaymentAttemptResult(true, null, null); }
        private static PaymentAttemptResult failure(String code, String message){ return new PaymentAttemptResult(false, code, message); }
    }

    private int serviceDuration(Integer durationMinutes){
        int duration = durationMinutes == null ? DEFAULT_SERVICE_DURATION_MINUTES : durationMinutes;
        if(duration < MIN_SERVICE_DURATION_MINUTES || duration > MAX_SERVICE_DURATION_MINUTES
                || duration % SLOT_GRANULARITY_MINUTES != 0){
            throw new IllegalArgumentException("This service has an invalid duration configuration");
        }
        return duration;
    }

    private TravelPricing calculateTravelPricing(BookAppointmentData data, StylerEntity styler, String rawServicePrice){
        int people = parsePeople(data.getNoOfPeople());
        BigDecimal serviceUnitPrice = amount(rawServicePrice);
        BigDecimal servicePrice = serviceUnitPrice.multiply(BigDecimal.valueOf(people)).setScale(2, RoundingMode.HALF_UP);
        double includedKm = styler.getIncludedTravelKm() == null ? 15.0 : styler.getIncludedTravelKm();
        BigDecimal ratePerKm = amount(styler.getExtraTravelRatePerKm() == null ? "0.00" : styler.getExtraTravelRatePerKm());
        double distanceKm = 0.0;
        double billableKm = 0.0;
        BigDecimal travelFee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if(isHomeService(data.getServiceTime())){
            if(data.getTravelDistanceKm() == null){
                throw new IllegalArgumentException("Travel distance is required for home service bookings");
            }
            distanceKm = roundKm(data.getTravelDistanceKm());
            if(distanceKm < 0){
                throw new IllegalArgumentException("Travel distance cannot be negative");
            }
            if(styler.getMaxServiceDistanceKm() != null && distanceKm > styler.getMaxServiceDistanceKm()){
                throw new IllegalArgumentException("This professional does not serve that distance");
            }
            billableKm = roundKm(Math.max(0.0, distanceKm - includedKm));
            travelFee = ratePerKm.multiply(BigDecimal.valueOf(billableKm)).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal total = servicePrice.add(travelFee).setScale(2, RoundingMode.HALF_UP);
        return new TravelPricing(
                money(servicePrice),
                money(travelFee),
                includedKm,
                distanceKm,
                billableKm,
                money(ratePerKm),
                money(total));
    }

    private int parsePeople(String noOfPeople){
        try {
            int people = Integer.parseInt(noOfPeople == null ? "1" : noOfPeople.trim());
            return Math.max(people, 1);
        } catch(NumberFormatException ex){
            return 1;
        }
    }

    private boolean isHomeService(String serviceTime){
        return "homeService".equalsIgnoreCase(serviceTime);
    }

    private BigDecimal amount(String value){
        String normalized = value == null ? "0" : value.replace(",", "").trim();
        return new BigDecimal(normalized).setScale(2, RoundingMode.HALF_UP);
    }

    private String money(BigDecimal value){
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private double roundKm(double value){
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * Handles signature-verified Stripe webhook events. Capture/release are
     * normally done synchronously in the appointment transitions; this covers
     * async outcomes (e.g. delayed card actions) so payment state stays true.
     */
    public void handleStripeWebhook(String payload, String signatureHeader){
        Event event = stripeService.verifyWebhookEvent(payload, signatureHeader);
        if("payment_intent.succeeded".equals(event.getType())){
            PaymentIntent intent = (PaymentIntent) event.getData().getObject();
            bookAppointmentRepo.findByPaymentIntentId(intent.getId()).ifPresent(a -> {
                // Capture is normally persisted synchronously; this webhook keeps
                // state correct when Stripe completes it asynchronously.
                boolean alreadyCaptured = "CAPTURED".equals(a.getPaymentStatus());
                a.setPaymentStatus("CAPTURED");
                a.setPaymentFailureCode(null);
                bookAppointmentRepo.save(a);
                if(!alreadyCaptured){
                    sendPaymentReceipt(a);
                }
                audit("system", "SYSTEM", "PAYMENT_CAPTURED", "APPOINTMENT", a.getAppointmentId(), "Payment captured");
            });
        } else if("payment_intent.payment_failed".equals(event.getType())){
            PaymentIntent intent = (PaymentIntent) event.getData().getObject();
            bookAppointmentRepo.findByPaymentIntentId(intent.getId()).ifPresent(a -> {
                a.setPaymentStatus("PAYMENT_FAILED");
                a.setPaymentFailureCode("PAYMENT_FAILED");
                bookAppointmentRepo.save(a);
                audit("system", "SYSTEM", "PAYMENT_FAILED", "APPOINTMENT", a.getAppointmentId(), "Payment failed");
            });
        } else if("account.updated".equals(event.getType())){
            handleAccountUpdated((Account) event.getData().getObject());
        } else if("payment_intent.canceled".equals(event.getType())){
            PaymentIntent intent = (PaymentIntent) event.getData().getObject();
            bookAppointmentRepo.findByPaymentIntentId(intent.getId()).ifPresent(a -> {
                a.setPaymentStatus("RELEASED");
                bookAppointmentRepo.save(a);
                audit("system", "SYSTEM", "PAYMENT_RELEASED", "APPOINTMENT", a.getAppointmentId(), "Payment hold released");
            });
        } else if("charge.dispute.created".equals(event.getType())){
            handleDispute((com.stripe.model.Dispute) event.getData().getObject(), true);
        } else if("charge.dispute.closed".equals(event.getType())){
            handleDispute((com.stripe.model.Dispute) event.getData().getObject(), false);
        }
    }

    /**
     * Records dispute lifecycle on the booking: an opened dispute flags the
     * payment and alerts ops; a closed dispute resolves it (won -> CAPTURED,
     * lost -> DISPUTE_LOST).
     */
    private void handleDispute(com.stripe.model.Dispute dispute, boolean opened){
        try{
            String paymentIntentId = dispute == null ? null : dispute.getPaymentIntent();
            if(paymentIntentId == null || paymentIntentId.isBlank()){
                LOG.warning("Dispute webhook missing payment intent: " + (dispute == null ? "null" : dispute.getId()));
                return;
            }
            bookAppointmentRepo.findByPaymentIntentId(paymentIntentId).ifPresent(a -> {
                if(opened){
                    a.setPaymentStatus("DISPUTED");
                    bookAppointmentRepo.save(a);
                    audit("system", "SYSTEM", "PAYMENT_DISPUTE_OPENED", "APPOINTMENT", a.getAppointmentId(),
                            "Chargeback opened (dispute " + dispute.getId() + ")");
                    alertAdmin("Payment dispute opened for appointment " + a.getAppointmentId()
                            + " (dispute " + dispute.getId() + ")");
                } else {
                    boolean lost = "lost".equalsIgnoreCase(dispute.getStatus());
                    a.setPaymentStatus(lost ? "DISPUTE_LOST" : "CAPTURED");
                    bookAppointmentRepo.save(a);
                    audit("system", "SYSTEM", "PAYMENT_DISPUTE_CLOSED", "APPOINTMENT", a.getAppointmentId(),
                            "Dispute closed (" + dispute.getStatus() + ") for dispute " + dispute.getId());
                }
            });
        } catch(Exception ex){
            LOG.warning("Dispute handling failed: " + ex.getMessage());
        }
    }

    /** Loads the admin-configured commission from the DB (seeded by the .env default on first boot). */
    @PostConstruct
    void rebuildStylerLocationIndex(){
        if (locationCacheService == null || stylerRepo == null) return;
        try {
            locationCacheService.clearIndex();
            int indexed = 0;
            for (StylerEntity styler : stylerRepo.findAll()) {
                if (isApprovedStyler(styler) && styler.getLatitude() != null && styler.getLongitude() != null) {
                    locationCacheService.indexStyler(styler.getStylerId(), styler.getLongitude(), styler.getLatitude());
                    indexed++;
                }
            }
            LOG.info("Rebuilt stylist location index: " + indexed + " stylists");
        } catch (Exception ex) {
            LOG.warning("Could not rebuild stylist location index: " + ex.getMessage());
        }
    }

    @PostConstruct
    void loadCommissionSetting(){
        try {
            if(platformSettingRepo == null) return;
            platformSettingRepo.findBySettingKey(COMMISSION_SETTING_KEY).ifPresent(setting -> {
                try {
                    cachedCommissionPercent = Double.parseDouble(setting.getSettingValue());
                } catch(NumberFormatException ignored){}
            });
        } catch(Exception ex){
            LOG.warning("Commission setting load failed: " + ex.getMessage());
        }
    }

    /**
     * Periodic reconciliation: rebuild the Redis geo index from MySQL every
     * 30 minutes so that stale entries (deleted stylists, coordinate changes
     * outside the normal write path) are corrected without a restart.
     */
    @Scheduled(fixedDelayString = "${app.geo.reconcile-interval-ms:1800000}")
    void reconcileStylerLocationIndex() {
        rebuildStylerLocationIndex();
    }

    /** Effective commission percent: admin setting when present, else the .env default. */
    private double effectiveCommissionPercent(){
        Double cached = cachedCommissionPercent;
        return cached != null ? cached : stripeCommissionPercent;
    }

    /** Platform commission in minor units, based on the effective commission percent. */
    private long commissionCents(long amountCents){
        double percent = effectiveCommissionPercent();
        if(percent <= 0 || amountCents <= 0) return 0L;
        return Math.round(amountCents * percent / 100.0);
    }

    /**
     * Applies the account.updated webhook: updates the styler's Connect
     * onboarding status and emails them when onboarding completes or is
     * rejected (only on actual transitions, so retries never re-send).
     */
    void handleAccountUpdated(Account account){
        String status;
        String disabledReason = null;
        if(Boolean.TRUE.equals(account.getDetailsSubmitted())){
            if(Boolean.TRUE.equals(account.getPayoutsEnabled())){
                status = "COMPLETE";
            } else if(account.getRequirements() != null && account.getRequirements().getDisabledReason() != null){
                status = "REJECTED";
                disabledReason = account.getRequirements().getDisabledReason();
            } else {
                status = "PENDING";
            }
        } else {
            status = "PENDING";
        }
        String finalStatus = status;
        String finalDisabledReason = disabledReason;
        stylerRepo.findByStripeConnectAccountId(account.getId()).ifPresent(s -> {
            String previous = s.getConnectOnboardingStatus();
            s.setConnectOnboardingStatus(finalStatus);
            // Persist the rejection reason so the Payouts page can show it; clear it
            // once the account is verified or still in progress.
            s.setConnectDisabledReason("REJECTED".equals(finalStatus) ? finalDisabledReason : null);
            stylerRepo.save(s);
            audit("system", "SYSTEM", "CONNECT_ACCOUNT_UPDATED", "STYLER", s.getStylerId(), finalStatus);
            if("COMPLETE".equals(finalStatus) && !"COMPLETE".equals(previous)){
                sendConnectStatusEmail(s, "RapidStylers - Payouts are ready",
                        "Your payout account is connected",
                        "Your Stripe account is connected and payouts are enabled. Your share of "
                                + "completed appointments will be paid on Stripe's regular payout schedule.");
            } else if("REJECTED".equals(finalStatus) && !"REJECTED".equals(previous)){
                String reason = finalDisabledReason == null ? "" : " (" + finalDisabledReason.replace('_', ' ') + ")";
                sendConnectStatusEmail(s, "RapidStylers - Payout setup needs attention",
                        "Your payout account could not be verified",
                        "Stripe could not verify your payout account" + reason
                                + ". Please reconnect from your dashboard or contact support.");
            }
        });
    }

    private void sendConnectStatusEmail(StylerEntity styler, String subject, String headline, String detail){
        try{
            if(styler.getEmailAddress() == null || styler.getEmailAddress().isBlank()) return;
            String name = (styler.getFirstname() + " " + styler.getLastname()).trim();
            if(name.isBlank()) name = styler.getBusinessName() == null ? "Stylist" : styler.getBusinessName();
            emailConfig.sendSimpleMail(styler.getEmailAddress(), subject,
                    "<p>Dear " + name + ",</p><p><strong>" + headline + "</strong></p>"
                            + "<p>" + detail + "</p><p>Thank you,<br>The RapidStylers Team</p>");
        } catch(Exception ex){
            LOG.warning("Connect status email failed: " + ex.getMessage());
        }
    }

    /** Converts a display price like "165.00" into Stripe's minor-unit amount (cents). */
    private long centsFromPrice(String price){
        if(price == null || price.trim().isEmpty()) return 0L;
        try {
            return new BigDecimal(price.replaceAll("[^0-9.]", ""))
                    .multiply(BigDecimal.valueOf(100)).longValue();
        } catch(Exception ex){
            return 0L;
        }
    }

    private static class TravelPricing {
        private final String servicePrice;
        private final String travelFee;
        private final Double includedTravelKm;
        private final Double travelDistanceKm;
        private final Double billableTravelKm;
        private final String extraTravelRatePerKm;
        private final String totalPrice;

        private TravelPricing(String servicePrice, String travelFee, Double includedTravelKm,
                              Double travelDistanceKm, Double billableTravelKm,
                              String extraTravelRatePerKm, String totalPrice) {
            this.servicePrice = servicePrice;
            this.travelFee = travelFee;
            this.includedTravelKm = includedTravelKm;
            this.travelDistanceKm = travelDistanceKm;
            this.billableTravelKm = billableTravelKm;
            this.extraTravelRatePerKm = extraTravelRatePerKm;
            this.totalPrice = totalPrice;
        }

        private Map<String, Object> toMap(){
            Map<String, Object> data = new HashMap<>();
            data.put("servicePrice", servicePrice);
            data.put("travelFee", travelFee);
            data.put("includedTravelKm", includedTravelKm);
            data.put("travelDistanceKm", travelDistanceKm);
            data.put("billableTravelKm", billableTravelKm);
            data.put("extraTravelRatePerKm", extraTravelRatePerKm);
            data.put("totalPrice", totalPrice);
            return data;
        }
    }

    private void reserveBookingSlots(BookAppointmentEntity appointment, LocalDate date, LocalTime start, int durationMinutes){
        List<BookingSlotLockEntity> locks = new ArrayList<>();
        for(int offset = 0; offset < durationMinutes; offset += SLOT_GRANULARITY_MINUTES){
            LocalDateTime slotDateTime = LocalDateTime.of(date, start).plusMinutes(offset);
            locks.add(new BookingSlotLockEntity(appointment.getStylerId(),
                    slotDateTime.toLocalDate(), slotDateTime.toLocalTime(), appointment.getAppointmentId()));
        }
        // Unique(styler_id, date, slot_start) is the final database-level race guard.
        bookingSlotLockRepo.saveAllAndFlush(locks);
    }

    private boolean appointmentStartHasPassed(BookAppointmentEntity appointment){
        LocalDate date = appointment.getAppointmentDateValue();
        LocalTime start = appointment.getAppointmentStartTime();
        if(date == null || start == null){
            try {
                date = parseBookingDate(appointment.getAppointmentDate());
                start = parseBookingTime(appointment.getArrivalTime());
            } catch(Exception ex){
                return false;
            }
        }
        ZonedDateTime scheduled = ZonedDateTime.of(date, start, applicationZone());
        return !ZonedDateTime.now(applicationZone()).isBefore(scheduled.plusMinutes(completionGraceMinutes));
    }

    private BaseResponse errorResponse(BaseResponse response, String message){
        response.setStatusCode(ERROR_STATUS_CODE);
        response.setMessage(message);
        response.setData(EMPTY_DATA);
        return response;
    }

    /** Error response that also exposes a machine-readable paymentError code for the UI. */
    private BaseResponse paymentErrorResponse(BaseResponse response, String paymentError, String message){
        response.setStatusCode(ERROR_STATUS_CODE);
        response.setMessage(message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentError", paymentError);
        response.setData(data);
        return response;
    }

    /**
     * Refunds a captured payment in full — used by automatic paths (reject /
     * cancel after capture). Idempotent: skips when a completed refund already
     * exists for the payment intent.
     */
    private void autoRefundCapturedPayment(BookAppointmentEntity appointment, String reason, String actorId){
        try{
            if(refundRepo.existsByPaymentIntentIdAndStatus(appointment.getPaymentIntentId(), "COMPLETED")){
                return;
            }
            long totalCents = centsFromPrice(appointment.getPaymentAmount() == null
                    ? appointment.getPrice() : appointment.getPaymentAmount());
            if(totalCents <= 0){
                return;
            }
            String refundId = "RFND-" + appUtils.randomAlphanumeric(8).toUpperCase(Locale.ROOT);
            RefundEntity refund = new RefundEntity();
            refund.setRefundId(refundId);
            refund.setAppointmentId(appointment.getAppointmentId());
            refund.setPaymentIntentId(appointment.getPaymentIntentId());
            refund.setAmount(String.format(Locale.ROOT, "%.2f", totalCents / 100.0));
            refund.setReason(reason);
            refund.setStatus("REQUESTED");
            refund.setCreatedBy(actorId == null || actorId.isBlank() ? "SYSTEM" : actorId);
            refund.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            refundRepo.save(refund);
            com.stripe.model.Refund stripeRefund = stripeService.refundBookingPayment(
                    appointment.getPaymentIntentId(), totalCents, reason,
                    "refund_" + appointment.getPaymentIntentId() + "_" + refundId);
            refund.setStripeRefundId(stripeRefund.getId());
            refund.setStatus("COMPLETED");
            refund.setCompletedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            appointment.setPaymentStatus("REFUNDED");
            bookAppointmentRepo.save(appointment);
            refundRepo.save(refund);
            audit(actorId, "SYSTEM", "PAYMENT_REFUND_AUTO", "APPOINTMENT", appointment.getAppointmentId(),
                    "Automatic refund of $" + refund.getAmount() + " — " + reason);
            // A completed-then-cancelled booking means the stylist payout transfer
            // was already created. Recover it automatically: the reversal is
            // attempted now and retried by a scheduled job when the stylist's
            // balance cannot cover it yet.
            if(appointment.getStripeTransferId() != null && !appointment.getStripeTransferId().isBlank()){
                if(payoutReversalService != null){
                    long shareCents = totalCents - commissionCents(totalCents);
                    payoutReversalService.requestReversal(appointment.getAppointmentId(),
                            appointment.getStripeTransferId(),
                            String.format(Locale.ROOT, "%.2f", shareCents / 100.0),
                            "Appointment cancelled after completion (refund " + refund.getRefundId() + ")");
                } else {
                    audit(actorId, "SYSTEM", "PAYOUT_REVERSAL_REQUIRED", "APPOINTMENT", appointment.getAppointmentId(),
                            "Refunded after completion — stylist payout " + appointment.getStripeTransferId()
                                    + " needs recovery");
                }
            }
            if(outboxEventService != null){
                outboxEventService.refundEvent(appointment, refund.getAmount(), reason, true);
            }
        } catch(Exception ex){
            LOG.warning("Automatic refund failed: " + ex.getMessage());
        }
    }

    /** Sends an operational alert to the configured ops address (no-op when unset). */
    private void alertAdmin(String message){
        if(adminAlertEmail == null || adminAlertEmail.isBlank() || emailConfig == null){
            LOG.warning("Admin alert (no alert email configured): " + message);
            return;
        }
        try{
            emailConfig.sendSimpleMail(adminAlertEmail, "RapidStylers - Action required", "<p>" + message + "</p>");
        } catch(Exception ex){
            LOG.warning("Admin alert email failed: " + ex.getMessage());
        }
    }

    private void audit(String actorId, String actorRole, String action, String resourceType, String resourceId, String details){
        try {
            if(auditLogRepo != null){
                auditLogRepo.save(new AuditLogEntity(actorId, actorRole, action, resourceType, resourceId, details));
            }
        } catch(Exception ex){
            LOG.warning("Audit log write failed: " + ex.getMessage());
        }
    }

    private void awardCompletionPoints(String userId, String appointmentId){
        try {
            LoyaltyAccountEntity account = loyaltyAccountRepo.findByUserId(userId)
                    .orElseGet(() -> new LoyaltyAccountEntity(userId, "RS-" + appUtils.randomAlphanumeric(8).toUpperCase(Locale.ROOT)));
            account.addPoints(10);
            loyaltyAccountRepo.save(account);
            referralRepo.findByReferredUserId(userId).ifPresent(referral -> {
                if("PENDING".equals(referral.getStatus())){
                    referral.setStatus("COMPLETED");
                    referralRepo.save(referral);
                    loyaltyAccountRepo.findByUserId(referral.getReferrerUserId()).ifPresent(referrer -> {
                        referrer.addPoints(25);
                        loyaltyAccountRepo.save(referrer);
                    });
                }
            });
            audit("system", "SYSTEM", "AWARD_LOYALTY", "APPOINTMENT", appointmentId, "Completion points awarded");
        } catch(Exception ex){
            LOG.warning("Loyalty award failed: " + ex.getMessage());
        }
    }

    private ZoneId applicationZone(){
        try {
            return ZoneId.of(appTimeZone == null || appTimeZone.isBlank() ? "America/Edmonton" : appTimeZone);
        } catch(Exception ex){
            return ZoneId.of("America/Edmonton");
        }
    }

    public BaseResponse stylerAppointments(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist = stylerRepo.findByStylerId(stylerId);
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<BookAppointmentEntity> appointments = bookAppointmentRepo.findByStylerId(stylerId);
            List<Object> result = new ArrayList<>();
            for(BookAppointmentEntity entity : appointments){
                result.add(dtoService.appointmentDTO(entity));
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse acceptAppointment(String stylerId, String appointmentId){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "3", new String[]{"1"}, "Appointment confirmed");
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse declineAppointment(String stylerId, String appointmentId){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "2", new String[]{"1"}, "Appointment rejected");
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse completeAppointment(String stylerId, String appointmentId){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "0", new String[]{"3"}, "Appointment marked as completed");
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse cancelAppointment(String userId, String appointmentId){
        return transitionAppointment(userId, ActorRole.CUSTOMER, appointmentId, "4", new String[]{"1", "3"}, "Appointment cancelled");
    }

    /**
     * Stylist-initiated cancellation of their own appointment. Covers the
     * completes-then-cancels edge case: a booking already marked completed
     * (payment captured) is cancelled by the stylist who completed it, and
     * the captured payment is refunded automatically — exactly once.
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse stylerCancelAppointment(String stylerId, String appointmentId){
        return transitionAppointment(stylerId, ActorRole.STYLER, appointmentId, "4", new String[]{"3", "0"}, "Appointment cancelled");
    }

    /** Customer retry path for a deferred or failed payment. */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse retryAppointmentPayment(String userId, String appointmentId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<BookAppointmentEntity> found = bookAppointmentRepo.findByAppointmentId(appointmentId);
            if(found.isEmpty()) return errorResponse(response, "Invalid Appointment Id");
            BookAppointmentEntity appointment = found.get();
            if(!userId.equals(appointment.getUserId())) return errorResponse(response, "You do not have permission to update this appointment");
            if(!Arrays.asList("PAYMENT_FAILED", "PAYMENT_REQUIRES_ACTION", "PAYMENT_ACCEPTED_SCHEDULED").contains(appointment.getPaymentStatus())){
                return errorResponse(response, "This appointment does not need a payment retry");
            }
            PaymentAttemptResult payment = authorizeScheduledPayment(appointment, "3".equals(appointment.getStatus()));
            if(!payment.success){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage(payment.message);
                response.setData(Collections.singletonMap("paymentError", payment.code));
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Payment completed successfully");
            response.setData(EMPTY_DATA);
        } catch(Exception ex){
            LOG.warning("Payment retry failed: " + ex.getMessage());
            return errorResponse(response, "Payment could not be completed. Please try again.");
        }
        return response;
    }


    /** List failed outbox events for admin review. */
    public BaseResponse listFailedOutboxEvents() {
        BaseResponse response = new BaseResponse(true);
        try {
            List<com.macrotel.rapidstylers.outbox.OutboxEventEntity> failed =
                outboxEventRepo.findByStatus(com.macrotel.rapidstylers.outbox.OutboxStatus.FAILED);
            List<Map<String, Object>> result = new ArrayList<>();
            for (var event : failed) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", event.getId());
                map.put("eventId", event.getEventId());
                map.put("eventType", event.getEventType() != null ? event.getEventType().name() : "UNKNOWN");
                map.put("aggregateType", event.getAggregateType());
                map.put("aggregateId", event.getAggregateId());
                map.put("status", event.getStatus().name());
                map.put("attempts", event.getAttempts());
                map.put("lastError", event.getLastError());
                map.put("createdAt", event.getCreatedAt().toString());
                result.add(map);
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        } catch (Exception ex) {
            LOG.warning("Failed to list outbox events: " + ex.getMessage());
        }
        return response;
    }

    /** Reset a failed outbox event back to PENDING for retry. */
    public BaseResponse retryFailedOutboxEvent(Long eventId, String adminId) {
        BaseResponse response = new BaseResponse(true);
        try {
            var event = outboxEventRepo.findById(eventId);
            if (event.isEmpty()) {
                return errorResponse(response, "Event not found");
            }
            var entity = event.get();
            if (entity.getStatus() != com.macrotel.rapidstylers.outbox.OutboxStatus.FAILED) {
                return errorResponse(response, "Only failed events can be retried");
            }
            entity.setStatus(com.macrotel.rapidstylers.outbox.OutboxStatus.PENDING);
            entity.setAttempts(0);
            entity.setLastError(null);
            entity.setNextAttemptAt(LocalDateTime.now());
            outboxEventRepo.save(entity);
            audit(adminId, "ADMIN", "RETRY_OUTBOX_EVENT", "OUTBOX_EVENT",
                    String.valueOf(eventId), "Admin retried failed outbox event");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Event queued for retry");
            response.setData(EMPTY_DATA);
        } catch (Exception ex) {
            LOG.warning("Failed to retry outbox event: " + ex.getMessage());
        }
        return response;
    }


    /**
     * Admin-initiated refund of a captured booking payment. Idempotent per
     * payment intent: a completed refund blocks a second one, so retries and
     * double-clicks never double-refund.
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse adminRefund(String adminId, RefundRequestData data){
        BaseResponse response = new BaseResponse(true);
        try{
            if(data == null || data.getAppointmentId() == null || data.getAppointmentId().isBlank()){
                return errorResponse(response, "appointmentId is required");
            }
            // Lock the appointment row so an admin refund racing a cancellation
            // serializes instead of refunding the same payment twice.
            Optional<BookAppointmentEntity> appointmentOpt = bookAppointmentRepo.findByAppointmentIdForUpdate(data.getAppointmentId().trim());
            if(appointmentOpt.isEmpty()){
                return errorResponse(response, "Invalid Appointment Id");
            }
            BookAppointmentEntity appointment = appointmentOpt.get();
            if(!stripeService.isConfigured() || appointment.getPaymentIntentId() == null || appointment.getPaymentIntentId().isBlank()){
                return errorResponse(response, "This appointment has no payment to refund");
            }
            if(!"CAPTURED".equals(appointment.getPaymentStatus())){
                return errorResponse(response, "Payment is not captured — nothing to refund");
            }
            if(refundRepo.existsByPaymentIntentIdAndStatus(appointment.getPaymentIntentId(), "COMPLETED")){
                return errorResponse(response, "This payment has already been refunded");
            }
            long totalCents = centsFromPrice(appointment.getPaymentAmount() == null
                    ? appointment.getPrice() : appointment.getPaymentAmount());
            long refundCents = totalCents;
            if(data.getAmount() != null && !data.getAmount().isBlank()){
                long requestedCents = centsFromPrice(data.getAmount());
                if(requestedCents <= 0){
                    return errorResponse(response, "Invalid refund amount");
                }
                refundCents = Math.min(requestedCents, totalCents);
            }
            if(refundCents <= 0){
                return errorResponse(response, "Invalid refund amount");
            }
            String refundId = "RFND-" + appUtils.randomAlphanumeric(8).toUpperCase(Locale.ROOT);
            RefundEntity refund = new RefundEntity();
            refund.setRefundId(refundId);
            refund.setAppointmentId(appointment.getAppointmentId());
            refund.setPaymentIntentId(appointment.getPaymentIntentId());
            refund.setAmount(String.format(Locale.ROOT, "%.2f", refundCents / 100.0));
            refund.setReason(data.getReason());
            refund.setStatus("REQUESTED");
            refund.setCreatedBy(adminId == null || adminId.isBlank() ? "SYSTEM" : adminId);
            refund.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            refundRepo.save(refund);
            try{
                com.stripe.model.Refund stripeRefund = stripeService.refundBookingPayment(
                        appointment.getPaymentIntentId(), refundCents, data.getReason(),
                        "refund_" + appointment.getPaymentIntentId() + "_" + refundId);
                refund.setStripeRefundId(stripeRefund.getId());
                refund.setStatus("COMPLETED");
                refund.setCompletedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                appointment.setPaymentStatus("REFUNDED");
                bookAppointmentRepo.save(appointment);
                refundRepo.save(refund);
                audit(adminId, "ADMIN", "PAYMENT_REFUND", "APPOINTMENT", appointment.getAppointmentId(),
                        "Refunded $" + refund.getAmount()
                                + (data.getReason() == null || data.getReason().isBlank() ? "" : " — " + data.getReason()));
                if(outboxEventService != null){
                    outboxEventService.refundEvent(appointment, refund.getAmount(), data.getReason(), true);
                }
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("Refund processed");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("refundId", refundId);
                result.put("amount", refund.getAmount());
                result.put("status", "COMPLETED");
                result.put("stripeRefundId", stripeRefund.getId());
                response.setData(result);
            } catch(Exception ex){
                LOG.warning("Refund failed: " + ex.getMessage());
                refund.setStatus("FAILED");
                refund.setFailureCode(String.valueOf(ex.getMessage()));
                refundRepo.save(refund);
                audit(adminId, "ADMIN", "PAYMENT_REFUND_FAILED", "APPOINTMENT", appointment.getAppointmentId(),
                        "Refund failed: " + ex.getMessage());
                return errorResponse(response, "Refund failed — " + ex.getMessage());
            }
        } catch(Exception ex){
            LOG.warning("Admin refund error: " + ex.getMessage());
        }
        return response;
    }

    /** Lists all refund records, newest first, for the admin view. */
    public BaseResponse adminRefunds(String adminId){
        BaseResponse response = new BaseResponse(true);
        try{
            List<RefundEntity> refunds = refundRepo.findAll();
            refunds.sort(java.util.Comparator.comparing(RefundEntity::getCreatedAt,
                    java.util.Comparator.nullsLast(String::compareTo)).reversed());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Refunds retrieved");
            response.setData(refunds);
        } catch(Exception ex){
            LOG.warning("Admin refund list error: " + ex.getMessage());
        }
        return response;
    }

    private enum ActorRole { STYLER, CUSTOMER }

    /**
     * Loads an appointment, verifies the actor owns the correct side of it,
     * and applies a status transition only when the current state is allowed.
     * Status codes: 1 pending, 3 accepted, 2 rejected, 0 completed, 4 cancelled.
     */
    private BaseResponse transitionAppointment(String ownerId, ActorRole actorRole, String appointmentId,
                                               String newStatus, String[] allowedFrom, String successMessage){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<BookAppointmentEntity> isExist = bookAppointmentRepo.findByAppointmentIdForUpdate(appointmentId);
            if(isExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Appointment Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            BookAppointmentEntity appointment = isExist.get();
            boolean ownsAppointment = actorRole == ActorRole.STYLER
                    ? ownerId.equals(appointment.getStylerId())
                    : ownerId.equals(appointment.getUserId());
            if(!ownsAppointment){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You do not have permission to update this appointment");
                response.setData(EMPTY_DATA);
                return response;
            }
            if("0".equals(newStatus) && !appointmentStartHasPassed(appointment)){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("An appointment can only be completed after its scheduled start time");
                response.setData(EMPTY_DATA);
                return response;
            }
            boolean allowed = false;
            for(String from : allowedFrom){
                if(from.equals(appointment.getStatus())){
                    allowed = true;
                    break;
                }
            }
            if(!allowed){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This appointment cannot be updated from its current state");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Completed bookings can only be cancelled by the stylist within a
            // short window of completion — after that, refunds go through admin.
            if("4".equals(newStatus) && "0".equals(appointment.getStatus())){
                LocalDateTime completedAt = appointment.getCompletedAt();
                if(completedAt == null){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("This completed appointment cannot be cancelled — completion time is unknown. Please contact support.");
                    response.setData(EMPTY_DATA);
                    return response;
                }
                if(completedAt.isBefore(LocalDateTime.now(applicationZone()).minusHours(stylerCancelWindowHours))){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Appointments can only be cancelled within " + stylerCancelWindowHours + " hours of completion");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            if("3".equals(newStatus) && stripeService.isConfigured()
                    && "PAYMENT_SCHEDULED".equals(appointment.getPaymentStatus())
                    && paymentAuthorizationDueAt(appointment).isAfter(LocalDateTime.now(applicationZone()))){
                // The stylist can accept an advance booking before the payment
                // window opens. The scheduler will authorize it later.
                appointment.setPaymentStatus("PAYMENT_ACCEPTED_SCHEDULED");
                appointment.setPaymentFailureCode(null);
            } else if("3".equals(newStatus) && stripeService.isConfigured()
                    && "PAYMENT_SCHEDULED".equals(appointment.getPaymentStatus())){
                PaymentAttemptResult payment = authorizeScheduledPayment(appointment, true);
                if(!payment.success){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage(payment.message);
                    response.setData(Collections.singletonMap("paymentError", payment.code));
                    return response;
                }
            }
            if("3".equals(newStatus) && appointment.getPaymentIntentId() != null && stripeService.isConfigured()
                    && "AUTHORIZED".equals(appointment.getPaymentStatus())){
                try {
                    stripeService.captureBookingPayment(appointment.getPaymentIntentId());
                    appointment.setPaymentStatus("CAPTURED");
                    sendPaymentReceipt(appointment);
                } catch(Exception ex){
                    LOG.warning("Payment capture failed: " + ex.getMessage());
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("Payment capture failed — please try again or update your payment method.");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            if("0".equals(newStatus)){
                appointment.setCompletedAt(LocalDateTime.now(applicationZone()));
            }
            appointment.setStatus(newStatus);
            bookAppointmentRepo.save(appointment);
            if("2".equals(newStatus) || "4".equals(newStatus)){
                // Rejected and customer-cancelled bookings release their held slots.
                bookingSlotLockRepo.deleteByAppointmentId(appointment.getAppointmentId());
                // A captured payment is refunded automatically; otherwise the
                // authorized hold is released so the card is never charged.
                if(appointment.getPaymentIntentId() != null && stripeService.isConfigured()){
                    if("CAPTURED".equals(appointment.getPaymentStatus())){
                        autoRefundCapturedPayment(appointment,
                                "Appointment " + ("2".equals(newStatus) ? "rejected" : "cancelled"), "SYSTEM");
                    } else {
                        try {
                            stripeService.releaseBookingPayment(appointment.getPaymentIntentId());
                            appointment.setPaymentStatus("RELEASED");
                            bookAppointmentRepo.save(appointment);
                        } catch(Exception ex){
                            LOG.warning("Payment hold release failed: " + ex.getMessage());
                        }
                    }
                }
            }
            if("0".equals(newStatus)){
                if(stripeService.isConfigured() && "CAPTURED".equals(appointment.getPaymentStatus())
                        && appointment.getStripeTransferId() == null){
                    try {
                        Optional<StylerEntity> styler = stylerRepo.findByStylerId(appointment.getStylerId());
                        String destination = styler.map(StylerEntity::getStripeConnectAccountId).orElse(null);
                        long totalCents = centsFromPrice(appointment.getPaymentAmount() == null
                                ? appointment.getPrice() : appointment.getPaymentAmount());
                        long stylistShareCents = totalCents - commissionCents(totalCents);
                        if(destination != null && stylistShareCents > 0){
                            com.stripe.model.Transfer transfer = stripeService.transferStylistShare(
                                    destination, stylistShareCents, appointment.getAppointmentId());
                            if(transfer != null){
                                appointment.setStripeTransferId(transfer.getId());
                            }
                        }
                    } catch(Exception ex){
                        LOG.warning("Stylist payout transfer failed: " + ex.getMessage());
                        response.setStatusCode(ERROR_STATUS_CODE);
                        response.setMessage("Payout transfer failed — the appointment was not completed. Please contact support.");
                        response.setData(EMPTY_DATA);
                        return response;
                    }
                }
                awardCompletionPoints(appointment.getUserId(), appointment.getAppointmentId());
            }
            audit(ownerId, actorRole == ActorRole.STYLER ? "STYLER" : "CUSTOMER", "APPOINTMENT_" + newStatus,
                    "APPOINTMENT", appointmentId, successMessage);

            // Notify both parties about the outcome.
            if("3".equals(newStatus)){
                sendAppointmentNotification(appointment, "Confirmed",
                        "Your appointment has been confirmed",
                        "The professional has accepted your booking request. See you at your appointment!",
                        "Appointment confirmed",
                        "You have confirmed this client's booking request.");
            } else if("2".equals(newStatus)){
                sendAppointmentNotification(appointment, "Declined",
                        "Your appointment request was declined",
                        "The professional could not take this booking. Please book another slot.",
                        "Appointment declined",
                        "You have declined this client's booking request.");
            } else if("0".equals(newStatus)){
                sendAppointmentNotification(appointment, "Completed",
                        "Your appointment has been completed",
                        "Thanks for using RapidStylers. We hope you enjoyed your session — a review is welcome.",
                        "Appointment completed",
                        "You have marked this appointment as completed.");
            } else if("4".equals(newStatus)){
                sendAppointmentNotification(appointment, "Cancelled",
                        "Your appointment was cancelled",
                        "Your appointment has been cancelled. Check your dashboard for updates.",
                        "Appointment cancelled",
                        actorRole == ActorRole.STYLER
                                ? "You have cancelled this appointment."
                                : "This appointment has been cancelled by the client.");
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(successMessage);
            response.setData(EMPTY_DATA);
        }
        catch (RuntimeException ex){
            // Persistence failures must escape the transaction so status changes
            // and slot releases roll back together.
            LOG.warning(ex.getMessage());
            throw ex;
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listUserAppointment(String userId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<UserEntity> isUserExist = userRepo.findByUserId(userId);
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id, Kindly create account");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<BookAppointmentEntity> getUserAppointment = bookAppointmentRepo.findByUserId(userId);
            List<Object> result = new ArrayList<>();
           for(BookAppointmentEntity bookAppointmentEntity : getUserAppointment){
               result.add(dtoService.appointmentDTO(bookAppointmentEntity));
           }
           response.setStatusCode(SUCCESS_STATUS_CODE);
           response.setMessage(SUCCESS_MESSAGE);
           response.setData(result);
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listUserPendingAppointment(String userId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<UserEntity> isUserExist = userRepo.findByUserId(userId);
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id, Kindly create account");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<BookAppointmentEntity> getUserAppointment = bookAppointmentRepo.userPendingAppointment(userId);
            List<Object> result = new ArrayList<>();
            for(BookAppointmentEntity bookAppointmentEntity : getUserAppointment){
                result.add(dtoService.appointmentDTO(bookAppointmentEntity));
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse addUserFeedBack (UserFeedbackData userFeedbackData){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if userId exist
            Optional<UserEntity> isUserExist = userRepo.findByUserId(userFeedbackData.getUserId());
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            FeedbackEntity feedbackEntity = new FeedbackEntity();
            feedbackEntity.setUserId(userFeedbackData.getUserId());
            feedbackEntity.setFeedBackType(userFeedbackData.getFeedbackType());
            feedbackEntity.setEmailAddress(userFeedbackData.getEmailAddress());
            feedbackEntity.setUserId(userFeedbackData.getUserId());
            feedbackEntity.setMessage(AppUtils.sanitizeText(userFeedbackData.getMessage()));
            feedbackEntity.setEmailAddress(userFeedbackData.getEmailAddress());
            feedBackRepo.save(feedbackEntity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Your feedback has been submitted successful, Admin will take care of it.");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listUserFeedBack(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<FeedbackEntity> getAllFeedBack = feedBackRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(FeedbackEntity feedbackEntity : getAllFeedBack){
                result.add(dtoService.feedBackDTO(feedbackEntity));
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Creates (or reuses) a Stripe Connect Express account for the stylist and
     * returns a hosted onboarding link. returnUrl/refreshUrl come from the
     * frontend (the stylist dashboard) and are where Stripe sends the stylist
     * after finishing or abandoning onboarding.
     */
    public BaseResponse createStylerConnectAccount(String stylerId, String returnUrl, String refreshUrl){
        BaseResponse response = new BaseResponse(true);
        try{
            if(!stripeService.isConfigured()){
                return errorResponse(response, "Payments are not configured yet");
            }
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                return errorResponse(response, "Invalid Styler Id");
            }
            StylerEntity styler = stylerOpt.get();
            String accountId = styler.getStripeConnectAccountId();
            if(accountId == null || accountId.isBlank()){
                Account account = stripeService.createExpressAccount(stylerId, styler.getEmailAddress(),
                        styler.getBusinessName(), styler.getFirstname(), styler.getLastname());
                accountId = account.getId();
                styler.setStripeConnectAccountId(accountId);
                styler.setConnectOnboardingStatus("PENDING");
                stylerRepo.save(styler);
            }
            AccountLink link = stripeService.createAccountLink(accountId, refreshUrl, returnUrl);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("onboardingUrl", link.getUrl());
            data.put("status", styler.getConnectOnboardingStatus());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch(Exception ex){
            LOG.warning("Connect account setup failed: " + ex.getMessage());
            return errorResponse(response, "Could not start payout setup — please try again");
        }
        return response;
    }

    /**
     * Payout summary for a stylist: earnings and commission from captured
     * appointments (recomputed with the configured commission percent) plus the
     * live available/pending balances Stripe reports for the connected account.
     */
    public BaseResponse getStylerPayouts(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                return errorResponse(response, "Invalid Styler Id");
            }
            StylerEntity styler = stylerOpt.get();
            String accountId = styler.getStripeConnectAccountId();
            boolean connected = accountId != null && !accountId.isBlank();

            BigDecimal totalEarned = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalCommission = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            List<Map<String, Object>> appointments = new ArrayList<>();
            for(BookAppointmentEntity appointment : bookAppointmentRepo.findByStylerId(stylerId)){
                if(!"0".equals(appointment.getStatus())
                        || appointment.getPaymentIntentId() == null
                        || !"CAPTURED".equals(appointment.getPaymentStatus())){
                    continue;
                }
                BigDecimal total = amount(appointment.getPaymentAmount() == null
                        ? appointment.getPrice() : appointment.getPaymentAmount());
                BigDecimal commission = total.multiply(BigDecimal.valueOf(effectiveCommissionPercent()))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal share = total.subtract(commission);
                totalEarned = totalEarned.add(share);
                totalCommission = totalCommission.add(commission);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("appointmentId", appointment.getAppointmentId());
                row.put("date", appointment.getAppointmentDate());
                row.put("arrivalTime", appointment.getArrivalTime());
                row.put("total", money(total));
                row.put("commission", money(commission));
                row.put("stylerShare", money(share));
                appointments.add(row);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("connected", connected);
            data.put("status", connected && styler.getConnectOnboardingStatus() != null
                    ? styler.getConnectOnboardingStatus() : "NOT_STARTED");
            data.put("disabledReason", connected ? styler.getConnectDisabledReason() : null);
            data.put("totalEarned", money(totalEarned));
            data.put("totalCommission", money(totalCommission));
            data.put("stripeAvailable", "0.00");
            data.put("stripePending", "0.00");
            if(connected && stripeService.isConfigured()){
                try {
                    Balance balance = Balance.retrieve(RequestOptions.builder().setStripeAccount(accountId).build());
                    data.put("stripeAvailable", moneyCents(sumBalanceAmounts(balance.getAvailable(), stripeService.currency())));
                    data.put("stripePending", moneyCents(sumPendingAmounts(balance.getPending(), stripeService.currency())));
                } catch(Exception ex){
                    LOG.warning("Connected balance lookup failed: " + ex.getMessage());
                }
            }
            data.put("appointments", appointments);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch(Exception ex){
            LOG.warning("Payout summary failed: " + ex.getMessage());
            return errorResponse(response, "Could not load payout summary");
        }
        return response;
    }

    /**
     * Business summary for a stylist dashboard: real appointment, client,
     * revenue, and popular-service stats (replaces the hardcoded card).
     * Status map: 1 pending, 3 accepted, 0 completed, 2 rejected, 4 cancelled.
     */
    public BaseResponse getStylerBusinessSummary(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                return errorResponse(response, "Invalid Styler Id");
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(businessSummaryData(stylerOpt.get()));
        } catch(Exception ex){
            LOG.warning("Business summary failed: " + ex.getMessage());
            return errorResponse(response, "Could not load business summary");
        }
        return response;
    }

    /**
     * Admin view: per-stylist business stats for every professional, most
     * appointments first. Reuses the same computation as the stylist dashboard.
     */
    public BaseResponse getAdminStylerBusinessSummaries(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<Map<String, Object>> rows = new ArrayList<>();
            for(StylerEntity styler : stylerRepo.findAll()){
                Map<String, Object> row = new LinkedHashMap<>(businessSummaryData(styler));
                row.put("stylerId", styler.getStylerId());
                row.put("businessName", styler.getBusinessName());
                row.put("name", (styler.getFirstname() + " " + styler.getLastname()).trim());
                row.put("emailAddress", styler.getEmailAddress());
                row.put("verificationStatus", styler.getVerificationStatus());
                rows.add(row);
            }
            rows.sort((a, b) -> Long.compare(((Number) b.get("totalAppointments")).longValue(),
                    ((Number) a.get("totalAppointments")).longValue()));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(rows);
        } catch(Exception ex){
            LOG.warning("Admin business summaries failed: " + ex.getMessage());
            return errorResponse(response, "Could not load business summaries");
        }
        return response;
    }

    /**
     * Shared per-stylist stats computation. Revenue counts the captured payment
     * amount when the payment lifecycle is active (paymentStatus CAPTURED),
     * otherwise the completed appointment price. netRevenue is the stylist's
     * share after the platform commission, consistent with the payout page.
     */
    private Map<String, Object> businessSummaryData(StylerEntity styler){
        String stylerId = styler.getStylerId();
        List<BookAppointmentEntity> appointments = bookAppointmentRepo.findByStylerId(stylerId);
        if(appointments == null) appointments = new ArrayList<>();

        long total = appointments.size();
        long pending = 0, confirmed = 0, finished = 0, cancelled = 0;
        Set<String> clients = new HashSet<>();
        BigDecimal grossRevenue = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        Map<String, Integer> serviceCounts = new LinkedHashMap<>();
        Map<String, String> serviceNames = new HashMap<>();
        for(SubServiceEntity service : subServiceRepo.findByStylerId(stylerId)){
            serviceNames.put(String.valueOf(service.getId()), service.getName());
        }

        for(BookAppointmentEntity appointment : appointments){
            if("1".equals(appointment.getStatus())) pending++;
            else if("3".equals(appointment.getStatus())) confirmed++;
            else if("0".equals(appointment.getStatus())) finished++;
            else if("4".equals(appointment.getStatus())) cancelled++;
            if("2".equals(appointment.getStatus()) || "4".equals(appointment.getStatus())) continue;
            if(appointment.getUserId() != null) clients.add(appointment.getUserId());
            if("0".equals(appointment.getStatus())){
                boolean captured = "CAPTURED".equals(appointment.getPaymentStatus());
                grossRevenue = grossRevenue.add(amount(
                        captured && appointment.getPaymentAmount() != null
                                ? appointment.getPaymentAmount() : appointment.getPrice()));
            }
            if(appointment.getSubServiceId() != null){
                serviceCounts.merge(appointment.getSubServiceId(), 1, Integer::sum);
            }
        }

        List<Map<String, Object>> popularServices = new ArrayList<>();
        serviceCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", serviceNames.getOrDefault(entry.getKey(), "Service"));
                    row.put("count", entry.getValue());
                    popularServices.add(row);
                });

        BigDecimal commission = grossRevenue.multiply(BigDecimal.valueOf(effectiveCommissionPercent()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal netRevenue = grossRevenue.subtract(commission);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalAppointments", total);
        data.put("clients", clients.size());
        data.put("pending", pending);
        data.put("confirmed", confirmed);
        data.put("finished", finished);
        data.put("cancelled", cancelled);
        data.put("totalRevenue", money(grossRevenue));
        data.put("totalCommission", money(commission));
        data.put("netRevenue", money(netRevenue));
        data.put("popularServices", popularServices);
        return data;
    }

    private long sumBalanceAmounts(java.util.List<Balance.Available> entries, String currency){
        long total = 0L;
        for(Balance.Available entry : entries){
            if(entry.getAmount() != null && (currency == null || currency.equals(entry.getCurrency()))){
                total += entry.getAmount();
            }
        }
        return total;
    }

    private long sumPendingAmounts(java.util.List<Balance.Pending> entries, String currency){
        long total = 0L;
        for(Balance.Pending entry : entries){
            if(entry.getAmount() != null && (currency == null || currency.equals(entry.getCurrency()))){
                total += entry.getAmount();
            }
        }
        return total;
    }

    private String moneyCents(long cents){
        return money(BigDecimal.valueOf(cents).movePointLeft(2));
    }

    public BaseResponse getStylerConnectStatus(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                return errorResponse(response, "Invalid Styler Id");
            }
            StylerEntity styler = stylerOpt.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", styler.getStripeConnectAccountId());
            data.put("status", styler.getConnectOnboardingStatus() == null
                    ? "NOT_STARTED" : styler.getConnectOnboardingStatus());
            data.put("disabledReason", styler.getConnectDisabledReason());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch(Exception ex){
            LOG.warning("Connect status lookup failed: " + ex.getMessage());
            return errorResponse(response, "Could not load payout status");
        }
        return response;
    }

    /**
     * Admin support view: every stylist's Connect payout status, ordered so
     * problems surface first (REJECTED, then PENDING, NOT_STARTED, COMPLETE).
     * Pure DB read — no Stripe calls, so it stays fast regardless of account count.
     */
    public BaseResponse getAdminStylerConnectStatuses(){
        BaseResponse response = new BaseResponse(true);
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for(StylerEntity styler : stylerRepo.findAll()){
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stylerId", styler.getStylerId());
                row.put("name", (styler.getFirstname() + " " + styler.getLastname()).trim());
                row.put("businessName", styler.getBusinessName());
                row.put("emailAddress", styler.getEmailAddress());
                row.put("verificationStatus", styler.getVerificationStatus() == null
                        ? "PENDING" : styler.getVerificationStatus());
                row.put("accountActive", "0".equals(styler.getStatus()));
                row.put("connectStatus", styler.getConnectOnboardingStatus() == null
                        ? "NOT_STARTED" : styler.getConnectOnboardingStatus());
                row.put("connectAccountId", styler.getStripeConnectAccountId());
                row.put("disabledReason", styler.getConnectDisabledReason());
                row.put("registered", styler.getInsertedDt());
                rows.add(row);
            }
            rows.sort((a, b) -> {
                int rankA = connectStatusRank((String) a.get("connectStatus"));
                int rankB = connectStatusRank((String) b.get("connectStatus"));
                int byStatus = Integer.compare(rankA, rankB);
                if(byStatus != 0) return byStatus;
                return String.valueOf(a.get("stylerId")).compareTo(String.valueOf(b.get("stylerId")));
            });
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(rows);
        } catch(Exception ex){
            LOG.warning("Connect status list failed: " + ex.getMessage());
            return errorResponse(response, "Could not load stylist payout statuses");
        }
        return response;
    }

    /** Sort rank for the admin overview: rejected and stuck payouts first. */
    private int connectStatusRank(String status){
        switch(status == null ? "NOT_STARTED" : status){
            case "REJECTED": return 0;
            case "PENDING": return 1;
            case "NOT_STARTED": return 2;
            default: return 3;
        }
    }

    public BaseResponse getCardSetupIntent(String userId){
        BaseResponse response = new BaseResponse(true);
        try{
            if(!stripeService.isConfigured()){
                return errorResponse(response, "Payments are not configured yet");
            }
            Optional<UserEntity> isUserExist = userRepo.findByUserId(userId);
            if(isUserExist.isEmpty()){
                return errorResponse(response, "Invalid User Id");
            }
            UserEntity user = isUserExist.get();
            Optional<CardDetailsEntity> existing = cardDetailsRepo.findByUserId(userId);
            String customerId = existing.filter(c -> c.getStripeCustomerId() != null)
                    .map(CardDetailsEntity::getStripeCustomerId).orElse(null);
            Customer customer = stripeService.getOrCreateCustomer(customerId, user.getEmailAddress(),
                    (user.getFirstname() + " " + user.getLastname()).trim());
            String clientSecret = stripeService.createSetupIntentClientSecret(customer.getId());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("clientSecret", clientSecret);
            data.put("stripeCustomerId", customer.getId());
            response.setData(data);
        } catch(Exception ex){
            LOG.warning("Setup intent failed: " + ex.getMessage());
            return errorResponse(response, "Could not start card setup — please try again");
        }
        return response;
    }

    /**
     * Persists only Stripe references and display metadata for a card collected
     * inside Stripe's Elements iframe. Raw card numbers, CVVs and expiry dates
     * are never sent to or stored by this application.
     */
    public BaseResponse updateUserCardDetails(CardDetailsData cardDetailsData){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<UserEntity> isUserExist = userRepo.findByUserId(cardDetailsData.getUserId());
            if(isUserExist.isEmpty()){
                return errorResponse(response, "Invalid User Id");
            }
            if(!stripeService.isConfigured()){
                return errorResponse(response, "Payments are not configured yet");
            }
            UserEntity user = isUserExist.get();
            Optional<CardDetailsEntity> existing = cardDetailsRepo.findByUserId(cardDetailsData.getUserId());
            String customerId = existing.filter(c -> c.getStripeCustomerId() != null)
                    .map(CardDetailsEntity::getStripeCustomerId).orElse(null);
            Customer customer = stripeService.getOrCreateCustomer(customerId, user.getEmailAddress(),
                    (user.getFirstname() + " " + user.getLastname()).trim());
            StripeService.CardDisplay display = stripeService.attachPaymentMethod(customer.getId(),
                    cardDetailsData.getPaymentMethodId());

            CardDetailsEntity entity = existing.orElseGet(CardDetailsEntity::new);
            entity.setUserId(cardDetailsData.getUserId());
            entity.setCardName(cardDetailsData.getCardName().trim());
            entity.setStripeCustomerId(customer.getId());
            entity.setStripePaymentMethodId(cardDetailsData.getPaymentMethodId());
            entity.setLast4(display.last4);
            entity.setBrand(display.brand);
            entity.setExpMonth(display.expMonth);
            entity.setExpYear(display.expYear);
            cardDetailsRepo.save(entity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(existing.isPresent() ? "Card details updated successfully" : "Card details added successfully");
            response.setData(EMPTY_DATA);
        } catch(Exception ex){
            LOG.warning("Card update failed: " + ex.getMessage());
            return errorResponse(response, "Could not save your card — please try again");
        }
        return response;
    }

    // ── Location-based search (Redis geospatial) ────────────────────────

    public BaseResponse searchNearby(double longitude, double latitude, double radius, String serviceTypeId, String city, String requestedDate, String requestedTime, int requestedDurationMinutes, boolean openNow){
        return searchNearby(longitude, latitude, radius, serviceTypeId, city, requestedDate, requestedTime, requestedDurationMinutes, openNow, null, null);
    }

    public BaseResponse searchNearby(double longitude, double latitude, double radius, String serviceTypeId, String city, String requestedDate, String requestedTime, int requestedDurationMinutes, boolean openNow, Integer requestedPage, Integer requestedPageSize){
        BaseResponse response = searchNearby(longitude, latitude, radius, serviceTypeId, city);
        if(!"200".equals(response.getStatusCode()) || !(response.getData() instanceof List)) return response;
        try {
            LocalDate requested = requestedDate == null || requestedDate.isBlank() ? null : parseBookingDate(requestedDate);
            LocalTime requestedStart = requestedTime == null || requestedTime.isBlank() ? null : parseBookingTime(requestedTime);
            int duration = requestedDurationMinutes <= 0 ? DEFAULT_SERVICE_DURATION_MINUTES : requestedDurationMinutes;
            List<StylerAccountDTO> candidates = new ArrayList<>();
            for(Object item : (List<?>) response.getData()){
                if(!(item instanceof StylerAccountDTO)) continue;
                StylerAccountDTO dto = (StylerAccountDTO) item;
                Optional<StylerEntity> styler = stylerRepo.findByStylerId(dto.getStylerId());
                if(styler.isEmpty() || !isApprovedStyler(styler.get())) continue;
                if(requested != null && requestedStart != null && (!isDateNotException(dto.getStylerId(), requested.toString())
                        || !timeWithinAvailability(dto.getStylerId(), requested.toString(), requestedStart.toString(), duration)
                        || !isWindowFree(dto.getStylerId(), requested.toString(), requestedStart.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)), duration))) continue;
                if(openNow && !isOpenAt(dto.getStylerId(), ZonedDateTime.now(applicationZone()).toLocalDate(), ZonedDateTime.now(applicationZone()).toLocalTime(), 30)) continue;
                candidates.add(dto);
            }
            candidates.sort((left, right) -> Double.compare(searchScore(right), searchScore(left)));
            if (requestedPage != null || requestedPageSize != null) {
                int page = requestedPage == null || requestedPage < 1 ? 1 : requestedPage;
                int pageSize = requestedPageSize == null || requestedPageSize < 1 ? 20 : Math.min(requestedPageSize, 50);
                int from = Math.min((page - 1) * pageSize, candidates.size());
                int to = Math.min(from + pageSize, candidates.size());
                Map<String, Object> paged = new LinkedHashMap<>();
                paged.put("items", candidates.subList(from, to));
                paged.put("page", page);
                paged.put("pageSize", pageSize);
                paged.put("total", candidates.size());
                paged.put("hasNext", to < candidates.size());
                response.setData(paged);
            } else {
                response.setData(candidates);
            }
        } catch(Exception ex){
            return errorResponse(response, "Invalid search date or time");
        }
        return response;
    }

    public BaseResponse searchNearby(double longitude, double latitude, double radius, String serviceTypeId, String city){
        BaseResponse response = new BaseResponse(true);
        try{
            // Step 1: Redis geospatial search (returns stylerId → distance in km)
            Map<String, Double> redisResults = locationCacheService.findNearbyWithDistance(longitude, latitude, radius);

            // Collect all results: stylerId → distance (null for DB-only results)
            Map<String, Double> allStylerDistances = new LinkedHashMap<>();
            for(Map.Entry<String, Double> entry : redisResults.entrySet()){
                allStylerDistances.put(entry.getKey(), entry.getValue());
            }

            // Step 2: DB city search — catches stylers not yet in Redis
            Set<String> seenIds = new HashSet<>(allStylerDistances.keySet());
            // Trim defensively so padded values (" Calgary ") still match and
            // whitespace-only payloads are ignored, not passed to the repo.
            if(city != null) city = city.trim();
            if(city != null && !city.isEmpty()){
                List<StylerEntity> cityStylers;
                if(serviceTypeId != null && !serviceTypeId.isEmpty()){
                    cityStylers = stylerRepo.findByCityAndServiceType(city, serviceTypeId);
                } else {
                    cityStylers = stylerRepo.findByCityIgnoreCase(city);
                }
                for(StylerEntity styler : cityStylers){
                    if(!isApprovedStyler(styler)) continue;
                    if(!seenIds.contains(styler.getStylerId())){
                        // Compute Haversine distance for DB results
                        double dist = haversine(latitude, longitude, styler.getLatitude(), styler.getLongitude());
                        allStylerDistances.put(styler.getStylerId(), dist);
                        seenIds.add(styler.getStylerId());
                    }
                }
            }

            if(allStylerDistances.isEmpty()){
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("No professionals found in this area");
                response.setData(EMPTY_DATA);
                return response;
            }

            // Step 3: Sort by distance (nearest first)
            List<Map.Entry<String, Double>> sorted = new ArrayList<>(allStylerDistances.entrySet());
            sorted.sort(Map.Entry.comparingByValue());

            // Step 4: Fetch full data from MySQL for sorted IDs
            List<Object> result = new ArrayList<>();
            for(Map.Entry<String, Double> entry : sorted){
                String stylerId = entry.getKey();
                double distKm = entry.getValue();
                Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(stylerId);
                if(stylerOpt.isPresent()){
                    StylerEntity styler = stylerOpt.get();
                    if(!isApprovedStyler(styler)) continue;
                    if(serviceTypeId != null && !serviceTypeId.isEmpty()){
                        if(!serviceTypeId.equals(styler.getServiceTypeId())) continue;
                    }
                    StylerAccountDTO dto = dtoService.stylerAccountDTO(styler);
                    dto.setDistanceKm(Math.round(distKm * 10.0) / 10.0);
                    result.add(dto);
                }
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    private boolean isOpenAt(String stylerId, LocalDate date, LocalTime time, int durationMinutes){
        if(!isDateNotException(stylerId, date.toString())) return false;
        List<AvailabilityEntity> rows = availabilityRepo.findByStylerId(stylerId);
        if(rows == null || rows.isEmpty()) return false;
        int weekday = date.getDayOfWeek().getValue() % 7;
        return rows.stream().filter(row -> Integer.toString(weekday).equals(row.getDayOfWeek())).anyMatch(row -> {
            try {
                LocalTime start = parseAvailabilityTime(row.getStartTime());
                LocalTime end = parseAvailabilityTime(row.getEndTime());
                return !time.isBefore(start) && !time.plusMinutes(durationMinutes).isAfter(end)
                        && isWindowFree(stylerId, date.toString(), time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)), durationMinutes);
            } catch(Exception ex){ return false; }
        });
    }

    private double searchScore(StylerAccountDTO dto){
        double distance = dto.getDistanceKm() == null ? 1000.0 : dto.getDistanceKm();
        double rating = dto.getAverageRating() == null ? 0.0 : dto.getAverageRating();
        long reviews = dto.getReviewCount() == null ? 0L : dto.getReviewCount();
        long completed = bookAppointmentRepo.findByStylerId(dto.getStylerId()).stream().filter(a -> "0".equals(a.getStatus())).count();
        double coldStartBoost = reviews == 0 ? 1.0 : 0.0;
        return (rating * 20.0) + Math.min(completed, 100) * 0.25 + coldStartBoost - Math.min(distance, 1000) * 0.35;
    }

    /**
     * Haversine formula — returns distance in km between two lat/lng points.
     */
    private double haversine(double lat1, double lng1, double lat2, double lng2){
        final double R = 6371.0; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public BaseResponse detectLocation(javax.servlet.http.HttpServletRequest request){
        BaseResponse response = new BaseResponse(true);
        try{
            Map<String, Object> location = locationService.detectLocation(request);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(location);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse reverseGeocode(double lat, double lng){
        BaseResponse response = new BaseResponse(true);
        try{
            Map<String, Object> geo = geocodingService.reverseGeocode(lat, lng);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(geo);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Build a full address string from structured StylerData fields for geocoding.
     */
    private String buildAddress(StylerData stylerData){
        StringBuilder sb = new StringBuilder();
        if(stylerData.getStreetAddress() != null && !stylerData.getStreetAddress().isEmpty()){
            sb.append(stylerData.getStreetAddress());
        }
        if(stylerData.getUnit() != null && !stylerData.getUnit().isEmpty()){
            sb.append(" ").append(stylerData.getUnit());
        }
        if(stylerData.getCity() != null && !stylerData.getCity().isEmpty()){
            sb.append(", ").append(stylerData.getCity());
        }
        if(stylerData.getBusinessProvince() != null && !stylerData.getBusinessProvince().isEmpty()){
            sb.append(", ").append(stylerData.getBusinessProvince());
        }
        if(stylerData.getPostalCode() != null && !stylerData.getPostalCode().isEmpty()){
            sb.append(" ").append(stylerData.getPostalCode());
        }
        if(stylerData.getCountry() != null && !stylerData.getCountry().isEmpty()){
            sb.append(", ").append(stylerData.getCountry());
        }
        return sb.toString();
    }

    /** Returns the trimmed name, or "" when blank/null — lets userId generation
     *  (and emails) work for the minimal email+password registration. */
    private String nullSafeName(String name){
        return name == null ? "" : name.trim();
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
}

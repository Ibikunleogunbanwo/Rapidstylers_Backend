package com.macrotel.rapidstylers.auth.service;

import com.macrotel.rapidstylers.auth.entity.OTPEntity;
import com.macrotel.rapidstylers.auth.pojo.OTPData;
import com.macrotel.rapidstylers.auth.repo.OTPRepo;
import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.common.service.FunnelService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.security.access.Roles;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * One-time email codes: sends the sign-up and password-reset codes for customers
 * and stylists, and checks the sign-up code when they type it back.
 */
@Service
public class OtpService {
    // Global rate-limit budgets (shared across OTP generation, OTP verification
    // and login so failures on one surface lock out the others).
    private static final int AUTH_WINDOW_SECONDS = 900;   // 15 min
    private static final int AUTH_MAX_FAILURES = 5;       // per email
    private static final int AUTH_IP_MAX_FAILURES = 20;   // per IP
    // Long enough to fill in the rest of the stylist sign-up after the email code.
    // The photos only upload at the last step, so a short pass failed anyone who
    // paused (finding their ID, a phone call). It only allows sign-up photo uploads.
    static final Duration SIGNUP_UPLOAD_TTL = Duration.ofHours(6);
    private static final int OTP_GEN_WINDOW_SECONDS = 900;
    private static final int OTP_GEN_MAX = 3;             // per email
    private static final int OTP_VERIFY_WINDOW_SECONDS = 900;
    private static final int OTP_VERIFY_MAX = 10;         // per IP
    // Wrong guesses one emailed code survives, from any number of IPs.
    static final int OTP_MAX_WRONG_GUESSES = 5;
    static final String CODE_LOCKED = "Too many wrong codes. Request a new code and try again.";
    @Autowired
    OTPRepo otpRepo;
    @Autowired
    CustomerRecords customerRecords;
    @Autowired
    StylerRecords stylerRecords;
    /** Sign-up funnel tally; null in unit tests that build this service by hand. */
    @Autowired(required = false)
    FunnelService funnelService;
    @Autowired
    EmailConfig emailConfig;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    RateLimiterService rateLimiterService;
    private static final Logger LOG = Logger.getLogger(OtpService.class.getName());
    AppUtils appUtils = new AppUtils();


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
            Optional<UserEntity> isEmailExist = customerRecords.findByEmailAddress(email);
            Optional<StylerEntity> isStylerEmailExist = stylerRecords.findByEmailAddress(email);
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
            funnel(FunnelService.SIGNUP_STARTED, "CUSTOMER", email);

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
                return wrongCode(response, ip, isOTPExist);
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
                if(otpPurpose.equals("USER SIGN UP")){
                    funnel(FunnelService.EMAIL_VERIFIED, "CUSTOMER", emailAddress);
                }
                // Successful verification clears the failure budget for this email/IP.
                rateLimiterService.clear("auth:" + emailAddress);
                rateLimiterService.clear("auth_ip:" + ip);
            }
            otpData.setIsUsed(OTPEntity.VERIFIED);
            otpRepo.save(otpData);
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
            Optional<UserEntity> isEmailExist = customerRecords.findByEmailAddress(emailAddress);
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
    public void sendOtpEmail(String emailAddress, String recipientName, String otpCode) {
        String emailSubject = "RapidStylers Verification Code";
        String emailBody = "Dear " + recipientName + ",<br><br>"
                + "Your RapidStylers verification code is:<br><br>"
                + "OTP Code: <strong>" + otpCode + "</strong><br><br>"
                + "This code expires 10 minutes after it was sent. "
                + "If you did not request it, you can safely ignore this email.<br><br>"
                + "Thank you,<br>The Rapid Stylers Team";
        emailConfig.sendSimpleMail(emailAddress, emailSubject, emailBody);
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
            Optional<StylerEntity> isStylerEmailExist = stylerRecords.findByEmailAddress(emailAddress);
            Optional<UserEntity> isUserEmailExist = customerRecords.findByEmailAddress(emailAddress);
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
            funnel(FunnelService.SIGNUP_STARTED, "STYLIST", emailAddress);

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
     * Answers a wrong code (or an email with no open code). The guess counts
     * against the caller's IP and against the emailed code itself, so spreading
     * guesses over many IPs still runs out: on the last allowed wrong guess the
     * code closes and only a newly emailed one can verify the address.
     */
    private BaseResponse wrongCode(BaseResponse response, String ip, Optional<OTPEntity> openCode){
        rateLimiterService.record("auth_ip:" + ip, AUTH_WINDOW_SECONDS);
        boolean closed = false;
        if(openCode.isPresent()){
            Long id = openCode.get().getId();
            closed = otpRepo.recordWrongGuess(id, OTP_MAX_WRONG_GUESSES) > 0
                    && otpRepo.findById(id).map(otp -> OTPEntity.LOCKED.equals(otp.getIsUsed())).orElse(false);
        }
        response.setStatusCode(ERROR_STATUS_CODE);
        response.setMessage(closed ? CODE_LOCKED : "Invalid OTP Code");
        response.setData(EMPTY_DATA);
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
                return wrongCode(response, ip, isOTPExist);
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
                funnel(FunnelService.EMAIL_VERIFIED, "STYLIST", emailAddress);
                // Successful verification clears the failure budget for this email/IP.
                rateLimiterService.clear("auth:" + emailAddress);
                rateLimiterService.clear("auth_ip:" + ip);
            }
            otpData.setIsUsed(OTPEntity.VERIFIED);
            otpRepo.save(otpData);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    private void funnel(String step, String role, String subject){
        if(funnelService != null){
            funnelService.record(step, role, subject);
        }
    }
}

package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.OTPEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.OTPData;
import com.macrotel.rapidstylers.repo.OTPRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Optional;
import java.util.logging.Logger;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@Service
public class AppService {
    BaseResponse baseResponse = new BaseResponse();
    AppUtils appUtils = new AppUtils();
    private static final Logger LOG = Logger.getLogger(AppService.class.getName());
    @Autowired
    OTPRepo otpRepo;
    @Autowired
    EmailConfig emailConfig;

    public BaseResponse testing(){
        baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
        baseResponse.setMessage("API is working well");
        baseResponse.setData(EMPTY_DATA);
        return baseResponse;
    }

    public BaseResponse generateSignUpOtpCode(OTPData otpData){
        try{
            //Check if user has requested for OTPCode in the past 1 minute;
            Optional<OTPEntity> getPreviousOtp = otpRepo.checkSignUpValidityOtp(otpData.getEmailAddress());
            if(getPreviousOtp.isPresent()){
                OTPEntity previousOtp = getPreviousOtp.get();
                String previousTimer = previousOtp.getInsertedDt();
                LocalDateTime previousTime = LocalDateTime.parse(previousTimer, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
                LocalDateTime currentTime = LocalDateTime.now();
                long minutesDifference = ChronoUnit.MINUTES.between(previousTime, currentTime);
                if (minutesDifference < 1) {
                    baseResponse.setStatusCode(ERROR_STATUS_CODE);
                    baseResponse.setMessage("OTP Code was generated earlier, Kindly wait for another minute to regenerate another one");
                    baseResponse.setData(EMPTY_DATA);
                    return baseResponse;
                }
            }
            String otpCode = appUtils.randomDigit(6);
            OTPEntity otpEntity = new OTPEntity();
            otpEntity.setEmailAddress(otpData.getEmailAddress());
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

            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage("A one-time password (OTP) code has been sent to your email. Please verify it.");
            baseResponse.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }

    public BaseResponse verifyUserOTP(String otpCode){
        try{
            Optional<OTPEntity> isOTPExist = otpRepo.findByCode(otpCode);
            if(isOTPExist.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Invalid OTP Code");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            OTPEntity otpData = isOTPExist.get();
            String previousOtpTime =  otpData.getInsertedDt();
            LocalDateTime previousTime = LocalDateTime.parse(previousOtpTime, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
            LocalDateTime currentTime = LocalDateTime.now();
            long minutesDifference = ChronoUnit.MINUTES.between(previousTime, currentTime);
            if (minutesDifference > 10) {
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("OTP Code has expired, Kindly generate another one");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            } else {
                otpData.setIsUsed("0");
                otpRepo.save(otpData);
                HashMap<String, String> otpValue = new HashMap<>();
                otpValue.put("emailAddress", otpData.getEmailAddress());

                baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
                baseResponse.setMessage("OTP Verify Successful");
                baseResponse.setData(otpValue);
            }
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }

    public BaseResponse userSignUp()
}

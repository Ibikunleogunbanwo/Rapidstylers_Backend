package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.dto.UserAccountDTO;
import com.macrotel.rapidstylers.entity.OTPEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.repo.OTPRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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
    @Autowired
    UserRepo userRepo;

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

    public BaseResponse userSignUp(UserData userData){
        try{
            //Verify if user verify email address or not
            Optional<OTPEntity> verifyUserEmail = otpRepo.verifyOtpSuccess(userData.getEmailAddress());
            if(verifyUserEmail.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Email Address is yet to be verified");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            //Check if the email already exist in the database of user account
            Optional<UserEntity> isUserExist = userRepo.findByEmailAddress(userData.getEmailAddress());
            if(isUserExist.isPresent()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Email Address already exist, Kindly choose another email address");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            UserEntity userEntity = new UserEntity(userData);
            userRepo.save(userEntity);

            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage("Account created successful");
            baseResponse.setData(EMPTY_DATA);
        }
        catch (Exception ex){

        }
        return baseResponse;
    }

    public BaseResponse userSignIn(SignInData signInData){
        try{
            //Validate UserSign In
            String emailAddress = signInData.getEmailAddress();
            String password = appUtils.encryptPassword(signInData.getPassword());
            Optional<UserEntity> userSignIn = userRepo.userAuthenticate(emailAddress,password);
            if(userSignIn.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Invalid Email Address or Password");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            UserEntity userEntity = userSignIn.get();
            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage(SUCCESS_MESSAGE);
            baseResponse.setData(userAccountDTO(userEntity));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }

    public BaseResponse resetPasswordMessage(OTPData otpData){
        try{
            String emailAddress = otpData.getEmailAddress();
            Optional<UserEntity> isEmailExist = userRepo.findByEmailAddress(emailAddress);
            if(isEmailExist.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Invalid Email Address, Kindly create an account");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            UserEntity userEntity = isEmailExist.get();
            String firstname = userEntity.getFirstname();
            String otpCode = appUtils.randomDigit(6);
            OTPEntity otpEntity = new OTPEntity();

            otpEntity.setEmailAddress(emailAddress);
            otpEntity.setCode(otpCode);
            otpEntity.setPurpose("FORGET PASSWORD");
            otpEntity.setInsertedDt(String.valueOf(LocalDate.now()));
            otpRepo.save(otpEntity);

            //Email Message
            String emailSubject = "RapidStylers Password Reset Request";
            String emailBody = "Dear " + firstname + ",<br><br>"
                    + "We received a request to reset your RapidStylers account password."
                    + "<br>To proceed with the password reset, please use the following OTP code:<br><br>"
                    + "OTP Code: <strong>" + otpCode  + "</strong><br><br>"
                    + "Enter this OTP code to complete the password reset process. If you didn't make this request, you can safely ignore this email."
                    + "<br><br>Thank you,<br>The Rapid Stylers Team";
            emailConfig.sendSimpleMail(emailAddress,emailSubject,emailBody);

            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage("Password Reset Initiated, Check Mail for OTP Code");
            baseResponse.setData(EMPTY_DATA);
        }
        catch(Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }
    public BaseResponse resetPassword(ForgotPasswordData forgotPasswordData){
        try{
            String password = forgotPasswordData.getPassword();
            String confirmPassword = forgotPasswordData.getConfirmPassword();
            String emailAddress = forgotPasswordData.getEmailAddress();
            if(password.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Password cannot be empty");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            if(!password.equals(confirmPassword)){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("The entered password does not match the confirmed password. Please ensure both passwords are identical.");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            String encryptPassword = appUtils.encryptPassword(password);
            Optional<UserEntity> getUserData = userRepo.findByEmailAddress(emailAddress);
            if(getUserData.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Invalid Email Address, Kindly create account");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            UserEntity userPrevData = getUserData.get();
            userPrevData.setPassword(encryptPassword);
            userRepo.save(userPrevData);

            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage("Password Change Successful");
            baseResponse.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return  baseResponse;
    }

    private UserAccountDTO userAccountDTO(UserEntity userEntity){
        UserAccountDTO userAccountDTO = new UserAccountDTO();
        userAccountDTO.setAddress(userEntity.getAddress());
        userAccountDTO.setCountry(userEntity.getCountry());
        userAccountDTO.setLastname(userEntity.getLastname());
        userAccountDTO.setFirstname(userEntity.getFirstname());
        userAccountDTO.setEmailAddress(userEntity.getEmailAddress());
        userAccountDTO.setState(userEntity.getState());
        userAccountDTO.setPhoneNumber(userEntity.getPhoneNumber());
        userAccountDTO.setUserId(userEntity.getUserId());
        userAccountDTO.setDateRegistered(userEntity.getInsertedDt());
        return userAccountDTO;
    }
}

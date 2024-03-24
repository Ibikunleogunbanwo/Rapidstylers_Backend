package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.*;
import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.repo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@Service
public class AppService {
    BaseResponse baseResponse = new BaseResponse(true);
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
            LOG.warning(ex.getMessage());
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
            baseResponse.setData(dtoService.userAccountDTO(userEntity));
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

    public BaseResponse createIdentificationType (IdentificationData identificationData){
        try{
            Optional<IdentificationEntity> isIdNameExist = identificationRepo.findByIdentificationName(identificationData.getIdentificationName());
            if(isIdNameExist.isPresent()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Identification already exist");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            IdentificationEntity identificationEntity = new IdentificationEntity();
            identificationEntity.setIdentificationName(identificationData.getIdentificationName());
            identificationRepo.save(identificationEntity);

            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage(identificationData.getIdentificationName()+ " successfully added to list of identification");
            baseResponse.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }

    public BaseResponse listIdentification(){
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
            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage(SUCCESS_MESSAGE);
            baseResponse.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }

    public BaseResponse updateIdentification(IdentificationData identificationData){
        try{
            //Get Identifications
            if(identificationData.getId().isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Identification Id cannot be empty");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            Optional<IdentificationEntity> isIdentificationExist = identificationRepo.findById(Long.parseLong(identificationData.getId()));
            if(isIdentificationExist.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("No Identification available for such id");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            IdentificationEntity identificationEntity = isIdentificationExist.get();
            identificationEntity.setIdentificationName(identificationData.getIdentificationName());
            identificationRepo.save(identificationEntity);

            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage("Identification Updated Successful");
            baseResponse.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }

    public BaseResponse deleteIdentification(String id){
        try{
            Optional<IdentificationEntity> getIdentification = identificationRepo.findById(Long.parseLong(id));
            if(getIdentification.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("No Identification available for such id");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            IdentificationEntity identificationEntity = getIdentification.get();
            identificationRepo.delete(identificationEntity);
            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage("Identification deleted successful");
            baseResponse.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }


    public BaseResponse createServiceType (ServiceTypeData serviceTypeData){
        try{
            Optional<ServiceEntity> isServiceNameExist = serviceRepo.findByServiceName(serviceTypeData.getServiceName());
            if(isServiceNameExist.isPresent()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Service Name already exist");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            ServiceEntity serviceEntity = new ServiceEntity();
            serviceEntity.setServiceName(serviceTypeData.getServiceName());
            serviceRepo.save(serviceEntity);

            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage(serviceTypeData.getServiceName()+ " successfully added to list of service");
            baseResponse.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }

    public BaseResponse listService(){
        try{
            List<ServiceEntity> getAllService = serviceRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(ServiceEntity serviceEntity : getAllService){
                HashMap<String, String> serviceMap = new HashMap<>();
                serviceMap.put("id", String.valueOf(serviceEntity.getId()));
                serviceMap.put("serviceName", serviceEntity.getServiceName());
                serviceMap.put("status", serviceEntity.getStatus());
                serviceMap.put("dateCreated", serviceEntity.getInsertedDt());
                result.add(serviceMap);
            }
            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage(SUCCESS_MESSAGE);
            baseResponse.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }
    public BaseResponse updateService(ServiceTypeData serviceTypeData){
        try{
            //Get Identifications
            if(serviceTypeData.getId().isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Service Id cannot be empty");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            Optional<ServiceEntity> isServiceExist = serviceRepo.findById(Long.parseLong(serviceTypeData.getId()));
            if(isServiceExist.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("No Service exist for such id");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            ServiceEntity serviceEntity = isServiceExist.get();
            serviceEntity.setServiceName(serviceTypeData.getServiceName());
            serviceRepo.save(serviceEntity);

            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage("Service Updated Successful");
            baseResponse.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }

    public BaseResponse deleteService(String id){
        try{
            Optional<ServiceEntity> getService = serviceRepo.findById(Long.parseLong(id));
            if(getService.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("No Service available for such id");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            ServiceEntity serviceEntity = getService.get();
            serviceRepo.delete(serviceEntity);
            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage("Service deleted successful");
            baseResponse.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }
    public BaseResponse createStyler(StylerData stylerData){
        try{
            //Check if email already exit for a styler
            Optional<StylerEntity> isEmailExist = stylerRepo.isEmailExist(stylerData.getEmailAddress());
            if(isEmailExist.isPresent()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Email Address already exist, Kindly choose another email");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            //Check if identificationId and ServiceId exist
            Optional<IdentificationEntity> isIdentificationExist = identificationRepo.findById(Long.parseLong(stylerData.getIdentificationTypeId()));
            if(isIdentificationExist.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Invalid Identification Type, Contact Admin");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            Optional<ServiceEntity> isServiceTypeExist = serviceRepo.findById(Long.parseLong(stylerData.getServiceTypeId()));
            if(isServiceTypeExist.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Invalid Service Type, Contact Admin");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            StylerEntity stylerEntity = new StylerEntity(stylerData);
            stylerRepo.save(stylerEntity);

            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage("Stylers Account Created Successful");
            baseResponse.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }

    public BaseResponse stylerLogin(SignInData signInData){
        try{
            String emailAddress = signInData.getEmailAddress();
            String password = appUtils.encryptPassword(signInData.getPassword());
            Optional<StylerEntity> stylerSignIn = stylerRepo.stylerAuthenticate(emailAddress,password);
            if(stylerSignIn.isEmpty()){
                baseResponse.setStatusCode(ERROR_STATUS_CODE);
                baseResponse.setMessage("Invalid Email Address or Password");
                baseResponse.setData(EMPTY_DATA);
                return baseResponse;
            }
            StylerEntity stylerEntity = stylerSignIn.get();
            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage(SUCCESS_MESSAGE);
            baseResponse.setData(dtoService.stylerAccountDTO(stylerEntity));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }

    public BaseResponse listAllStylers(){
        try{
            List<StylerEntity> getAllStylers = stylerRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(StylerEntity stylerEntity : getAllStylers){
                result.add(dtoService.stylerAccountDTO(stylerEntity));
            }
            Collections.reverse(result);
            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage(SUCCESS_MESSAGE);
            baseResponse.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return baseResponse;
    }
}

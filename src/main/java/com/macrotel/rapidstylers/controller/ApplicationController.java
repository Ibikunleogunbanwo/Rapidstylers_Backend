package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.service.AppService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@RestController
@RequestMapping("/rapid_stylers")
@CrossOrigin(origins = {"*"})
public class ApplicationController {
    @Autowired
    AppService appService;

    @GetMapping("/testing")
    public ResponseEntity testing(){
        BaseResponse baseResponse = appService.testing();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/generate_sign_up_otp_code")
    public ResponseEntity generateSignUpOtpCode(@Valid @RequestBody OTPData otpData){
        BaseResponse baseResponse = appService.generateSignUpOtpCode(otpData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/verify_otp_code")
    public ResponseEntity verifyOtpCode(@RequestParam("otpCode") String otpCode){
        BaseResponse baseResponse = appService.verifyUserOTP(otpCode);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/create_user_account")
    public ResponseEntity createUserAccount(@Valid @RequestBody UserData userData){
        BaseResponse baseResponse = appService.userSignUp(userData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/user_sign_in")
    public ResponseEntity userSignIn(@Valid @RequestBody SignInData signInData){
        BaseResponse baseResponse = appService.userSignIn(signInData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/generate_reset_password_token")
    public ResponseEntity resetPasswordToken(@Valid @RequestBody OTPData otpData){
        BaseResponse baseResponse = appService.resetPasswordMessage(otpData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/reset_user_password")
    public ResponseEntity resetUserPassword(@Valid @RequestBody ForgotPasswordData forgotPasswordData){
        BaseResponse baseResponse = appService.resetPassword(forgotPasswordData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/create_identification")
    public ResponseEntity createIdentification(@Valid @RequestBody IdentificationData identificationData){
        BaseResponse baseResponse = appService.createIdentificationType(identificationData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_identification")
    public ResponseEntity listIdentification(){
        BaseResponse baseResponse = appService.listIdentification();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/delete_identification")
    public ResponseEntity deleteIdentification(@RequestParam("id") String id){
        BaseResponse baseResponse = appService.deleteIdentification(id);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/update_identification")
    public ResponseEntity updateIdentification(@Valid @RequestBody IdentificationData identificationData){
        BaseResponse baseResponse = appService.updateIdentification(identificationData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse handleValidationExceptions(
            MethodArgumentNotValidException ex) {

        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode(ERROR_STATUS_CODE);
        baseResponse.setMessage("An error occurred");
        Map<String, String> errors = new HashMap<>();

        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        baseResponse.setData(errors);
        return baseResponse;

    }
}

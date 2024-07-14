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
    public ResponseEntity <BaseResponse> testing(){
        BaseResponse baseResponse = appService.testing();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/generate_sign_up_otp_code")
    public ResponseEntity <BaseResponse> generateSignUpOtpCode(@Valid @RequestBody OTPData otpData){
        BaseResponse baseResponse = appService.generateSignUpOtpCode(otpData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/verify_otp_code")
    public ResponseEntity <BaseResponse> verifyOtpCode(@RequestParam("otpCode") String otpCode){
        BaseResponse baseResponse = appService.verifyUserOTP(otpCode);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/create_user_account")
    public ResponseEntity <BaseResponse> createUserAccount(@Valid @RequestBody UserData userData){
        BaseResponse baseResponse = appService.userSignUp(userData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/user_sign_in")
    public ResponseEntity <BaseResponse> userSignIn(@Valid @RequestBody SignInData signInData){
        BaseResponse baseResponse = appService.userSignIn(signInData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/user_data")
    public ResponseEntity<BaseResponse> singleUserData(@RequestParam("userId") String userId){
        BaseResponse baseResponse = appService.singleUserData(userId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/generate_reset_password_token")
    public ResponseEntity <BaseResponse> resetPasswordToken(@Valid @RequestBody OTPData otpData){
        BaseResponse baseResponse = appService.resetPasswordMessage(otpData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/reset_user_password")
    public ResponseEntity <BaseResponse> resetUserPassword(@Valid @RequestBody ForgotPasswordData forgotPasswordData){
        BaseResponse baseResponse = appService.resetPassword(forgotPasswordData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/update_user_password")
    public ResponseEntity <BaseResponse> updateUserPassword(@Valid @RequestBody ForgotPasswordData forgotPasswordData){
        BaseResponse baseResponse = appService.updateUserPassword(forgotPasswordData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/create_identification")
    public ResponseEntity <BaseResponse> createIdentification(@Valid @RequestBody IdentificationData identificationData){
        BaseResponse baseResponse = appService.createIdentificationType(identificationData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_identification")
    public ResponseEntity <BaseResponse> listIdentification(){
        BaseResponse baseResponse = appService.listIdentification();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/delete_identification")
    public ResponseEntity <BaseResponse> deleteIdentification(@RequestParam("id") String id){
        BaseResponse baseResponse = appService.deleteIdentification(id);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/update_identification")
    public ResponseEntity <BaseResponse> updateIdentification(@Valid @RequestBody IdentificationData identificationData){
        BaseResponse baseResponse = appService.updateIdentification(identificationData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/create_service")
    public ResponseEntity <BaseResponse> createService(@Valid @RequestBody ServiceTypeData serviceTypeData){
        BaseResponse baseResponse = appService.createServiceType(serviceTypeData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_service")
    public ResponseEntity <BaseResponse> listService(){
        BaseResponse baseResponse = appService.listService();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/delete_service")
    public ResponseEntity <BaseResponse> deleteService(@RequestParam("id") String id){
        BaseResponse baseResponse = appService.deleteService(id);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/update_service")
    public ResponseEntity <BaseResponse> updateService(@Valid @RequestBody ServiceTypeData serviceTypeData){
        BaseResponse baseResponse = appService.updateService(serviceTypeData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/create_styler")
    public ResponseEntity <BaseResponse> createStyler(@Valid @RequestBody StylerData stylerData){
        BaseResponse baseResponse = appService.createStyler(stylerData);
        HttpStatus status =  (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/styler_sign_in")
    public ResponseEntity <BaseResponse> stylerSignIn(@Valid @RequestBody SignInData signInData){
        BaseResponse baseResponse = appService.stylerLogin(signInData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/list_all_stylers")
    public ResponseEntity <BaseResponse> listAllStylers(){
        BaseResponse baseResponse = appService.listAllStylers();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/styler_sign_out")
    public ResponseEntity <BaseResponse> stylerSignOut(@RequestParam("stylerId") String stylerId){
        BaseResponse baseResponse = appService.stylerLogOut(stylerId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/search_styler")
    public ResponseEntity <BaseResponse> searchStyler(@RequestParam("businessName") String businessName){
        BaseResponse baseResponse = appService.searchStyler(businessName);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/create_sub_service")
    public ResponseEntity <BaseResponse> createSubService(@Valid @RequestBody SubServiceData subServiceData){
        BaseResponse baseResponse = appService.createSubService(subServiceData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/list_sub_service")
    public ResponseEntity <BaseResponse> listStylerSubService(@RequestParam("stylerId") String stylerId){
        BaseResponse baseResponse = appService.listStylerSubService(stylerId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/create_portfolio")
    public ResponseEntity <BaseResponse> createPortfolio(@Valid @RequestBody StylerPortfolioData stylerPortfolioData){
        BaseResponse baseResponse = appService.createStylerPortfolio(stylerPortfolioData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/list_portfolio")
    public ResponseEntity<BaseResponse> listStylerPortfolio(@RequestParam("stylerId") String stylerId){
        BaseResponse baseResponse = appService.listStylerPortfolio(stylerId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/create_review")
    public ResponseEntity <BaseResponse> createReview(@Valid @RequestBody ReviewData reviewData){
        BaseResponse baseResponse = appService.createStylerReview(reviewData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/list_review")
    public ResponseEntity<BaseResponse> listReview(@RequestParam("stylerId") String stylerId){
        BaseResponse baseResponse = appService.listStylerReviews(stylerId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/single_styler")
    public ResponseEntity<BaseResponse> singleStyler(@RequestParam("stylerId") String stylerId){
        BaseResponse baseResponse = appService.getStylerDetails(stylerId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/search_by_service")
    public ResponseEntity<BaseResponse> stylerByService(@RequestParam("serviceTypeId") String serviceTypeId){
        BaseResponse baseResponse = appService.getStylerByService(serviceTypeId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/book_appointment")
    public ResponseEntity <BaseResponse> bookAppointment(@Valid @RequestBody BookAppointmentData bookAppointmentData){
        BaseResponse baseResponse = appService.bookAppointment(bookAppointmentData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/user_appointments")
    public ResponseEntity<BaseResponse> userAppointments(@RequestParam("userId") String userId){
        BaseResponse baseResponse = appService.listUserAppointment(userId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/user_pending_appointments")
    public ResponseEntity<BaseResponse> userPendingAppointments(@RequestParam("userId") String userId){
        BaseResponse baseResponse = appService.listUserPendingAppointment(userId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/update_user_data")
    public ResponseEntity <BaseResponse> updateUserData(@Valid @RequestBody UpdateData updateData){
        BaseResponse baseResponse = appService.updateUserData(updateData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/add_feedback")
    public ResponseEntity<BaseResponse> addFeedBack(@Valid @RequestBody UserFeedbackData userFeedbackData){
        BaseResponse baseResponse = appService.addUserFeedBack(userFeedbackData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_feedback")
    public ResponseEntity<BaseResponse> listFeedback(){
        BaseResponse baseResponse = appService.listUserFeedBack();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/update_card_details")
    public ResponseEntity<BaseResponse> updateCardDetails(@Valid @RequestBody CardDetailsData cardDetailsData){
        BaseResponse baseResponse = appService.updateUserCardDetails(cardDetailsData);
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

package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.service.AppService;
import com.macrotel.rapidstylers.config.EncryptionConfig;
import com.macrotel.rapidstylers.service.GalleryService;
import com.macrotel.rapidstylers.service.GeocodingService;
import com.macrotel.rapidstylers.service.PaymentReconciliationService;
import com.macrotel.rapidstylers.service.RefreshTokenService;
import com.macrotel.rapidstylers.service.SessionActivityService;
import com.macrotel.rapidstylers.service.StepUpService;
import com.macrotel.rapidstylers.security.JwtUtil;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@RestController
@RequestMapping("/rapid_stylers")
public class ApplicationController {
    @Autowired
    AppService appService;
    @Autowired
    GalleryService galleryService;
    @Autowired
    GeocodingService geocodingService;
    @Autowired
    EncryptionConfig encryptionConfig;
    @Autowired
    RefreshTokenService refreshTokenService;
    @Autowired
    SessionActivityService sessionActivityService;
    @Autowired
    StepUpService stepUpService;
    @Autowired
    PaymentReconciliationService paymentReconciliationService;
    @Autowired
    JwtUtil jwtUtil;

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

    @PostMapping("/verify_otp_code")
    public ResponseEntity <BaseResponse> verifyOtpCode(@RequestBody Map<String, String> body){
        String otpCode = body.getOrDefault("otpCode", "");
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
    @PostMapping("/sign_in")
    public ResponseEntity <BaseResponse> signIn(@Valid @RequestBody SignInData signInData){
        BaseResponse baseResponse = appService.signIn(signInData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
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
        BaseResponse baseResponse = appService.signInWithGoogle(idToken);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/user_data")
    public ResponseEntity<BaseResponse> singleUserData(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.singleUserData(accountId);
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
    public ResponseEntity <BaseResponse> updateUserPassword(@Valid @RequestBody ForgotPasswordData forgotPasswordData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.updateUserPassword(forgotPasswordData, accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/create_identification")
    @PreAuthorize("hasRole('ADMIN')")
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

    @PostMapping("/delete_identification")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> deleteIdentification(@RequestBody Map<String, String> body){
        String id = body.getOrDefault("id", "");
        BaseResponse baseResponse = appService.deleteIdentification(id);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/update_identification")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> updateIdentification(@Valid @RequestBody IdentificationData identificationData){
        BaseResponse baseResponse = appService.updateIdentification(identificationData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/create_service")
    @PreAuthorize("hasRole('ADMIN')")
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

    @PostMapping("/delete_service")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> deleteService(@RequestBody Map<String, String> body){
        String id = body.getOrDefault("id", "");
        BaseResponse baseResponse = appService.deleteService(id);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/update_service")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> updateService(@Valid @RequestBody ServiceTypeData serviceTypeData){
        BaseResponse baseResponse = appService.updateService(serviceTypeData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/gallery")
    public ResponseEntity <BaseResponse> gallery(
            @RequestParam("category") String category,
            @RequestParam(value = "per_page", defaultValue = "12") int perPage,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "query", defaultValue = "") String query){
        BaseResponse baseResponse = galleryService.searchGallery(category, perPage, page, query);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_blog")
    public ResponseEntity <BaseResponse> listBlog(){
        BaseResponse baseResponse = appService.listBlogPosts();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/single_blog")
    public ResponseEntity <BaseResponse> singleBlog(@RequestParam("id") String id){
        BaseResponse baseResponse = appService.singleBlogPost(id);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/create_blog")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> createBlog(@Valid @RequestBody BlogPostData blogPostData){
        BaseResponse baseResponse = appService.createBlogPost(blogPostData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/update_blog")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> updateBlog(@Valid @RequestBody BlogPostData blogPostData){
        BaseResponse baseResponse = appService.updateBlogPost(blogPostData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/delete_blog")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> deleteBlog(@RequestBody Map<String, String> body){
        String id = body.getOrDefault("id", "");
        BaseResponse baseResponse = appService.deleteBlogPost(id);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/styler_generate_otp")
    public ResponseEntity<BaseResponse> stylerGenerateOtp(@Valid @RequestBody OTPData otpData){
        BaseResponse baseResponse = appService.stylerGenerateOtp(otpData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/styler_verify_otp")
    public ResponseEntity<BaseResponse> stylerVerifyOtp(@RequestBody Map<String, String> body){
        String otpCode = body.getOrDefault("otpCode", "");
        BaseResponse baseResponse = appService.stylerVerifyOtp(otpCode);
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
    @GetMapping("/admin/styler_verification_queue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> stylerVerificationQueue(){
        BaseResponse baseResponse = appService.getStylerVerificationQueue();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/admin/update_styler_verification")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> updateStylerVerification(@Valid @RequestBody VerificationActionData verificationActionData){
        BaseResponse baseResponse = appService.updateStylerVerification(verificationActionData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/styler_sign_out")
    public ResponseEntity <BaseResponse> stylerSignOut(HttpServletRequest request){
        String stylerId = currentAccountId(request);
        if(stylerId == null){
            return unauthorized();
        }
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
    @GetMapping("/search_by_province")
    public ResponseEntity <BaseResponse> searchByProvince(@RequestParam("province") String province){
        BaseResponse baseResponse = appService.searchStylerByProvince(province);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/search_nearby")
    public ResponseEntity <BaseResponse> searchNearby(@RequestBody SearchNearbyData data){
        BaseResponse baseResponse = appService.searchNearby(data.getLng(), data.getLat(), data.getRadius(), data.getServiceTypeId(), data.getCity(), data.getRequestedDate(), data.getRequestedTime(), data.getDurationMinutes(), data.isOpenNow(), data.getPage(), data.getPageSize());
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/place_autocomplete")
    public ResponseEntity<BaseResponse> placeAutocomplete(@RequestParam("input") String input){
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
        baseResponse.setMessage(SUCCESS_MESSAGE);
        baseResponse.setData(geocodingService.placeAutocomplete(input));
        return ResponseEntity.ok(baseResponse);
    }
    @GetMapping("/place_details")
    public ResponseEntity<BaseResponse> placeDetails(@RequestParam("placeId") String placeId){
        BaseResponse baseResponse = new BaseResponse();
        Map<String, Object> data = geocodingService.geocodeByPlaceId(placeId);
        if (data == null) {
            baseResponse.setStatusCode(ERROR_STATUS_CODE);
            baseResponse.setMessage("Place not found");
            baseResponse.setData(java.util.Collections.emptyList());
        } else {
            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage(SUCCESS_MESSAGE);
            baseResponse.setData(data);
        }
        return ResponseEntity.ok(baseResponse);
    }
    @GetMapping("/detect-location")
    public ResponseEntity <BaseResponse> detectLocation(javax.servlet.http.HttpServletRequest request){
        BaseResponse baseResponse = appService.detectLocation(request);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/reverse-geocode")
    public ResponseEntity <BaseResponse> reverseGeocode(@RequestBody Map<String, Double> body){
        double lat = body.getOrDefault("lat", 0.0);
        double lng = body.getOrDefault("lng", 0.0);
        BaseResponse baseResponse = appService.reverseGeocode(lat, lng);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/create_sub_service")
    public ResponseEntity <BaseResponse> createSubService(@Valid @RequestBody SubServiceData subServiceData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        // The authenticated stylist owns the service; never trust the body stylerId.
        subServiceData.setStylerId(accountId);
        BaseResponse baseResponse = appService.createSubService(subServiceData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/styler_own_sub_services")
    public ResponseEntity <BaseResponse> ownStylerSubServices(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.listOwnStylerSubService(accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/update_sub_service")
    public ResponseEntity <BaseResponse> updateSubService(@Valid @RequestBody ServiceUpdateData data, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        return new ResponseEntity<>(appService.updateSubService(accountId, data), HttpStatus.OK);
    }

    @GetMapping("/list_sub_service")
    public ResponseEntity <BaseResponse> listStylerSubService(@RequestParam("stylerId") String stylerId){
        BaseResponse baseResponse = appService.listStylerSubService(stylerId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/create_portfolio")
    public ResponseEntity <BaseResponse> createPortfolio(@Valid @RequestBody StylerPortfolioData stylerPortfolioData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        // The portfolio owner is the authenticated styler — never trust the body's stylerId.
        stylerPortfolioData.setStylerId(accountId);
        BaseResponse baseResponse = appService.createStylerPortfolio(stylerPortfolioData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/styler_own_portfolio")
    public ResponseEntity <BaseResponse> stylerOwnPortfolio(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.listOwnStylerPortfolio(accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/list_portfolio")
    public ResponseEntity<BaseResponse> listStylerPortfolio(@RequestParam("stylerId") String stylerId){
        BaseResponse baseResponse = appService.listStylerPortfolio(stylerId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/admin/all_portfolios")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> allPortfolios(){
        BaseResponse baseResponse = appService.listAllPortfolios();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/admin/delete_portfolio_image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> deletePortfolioImage(@Valid @RequestBody PortfolioActionData portfolioActionData){
        BaseResponse baseResponse = appService.adminDeletePortfolioImage(portfolioActionData.getPortfolioId());
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    /** Stylist removes one of their own gallery images (ownership enforced from the token). */
    @PostMapping("/delete_portfolio_image")
    public ResponseEntity<BaseResponse> deleteOwnPortfolioImage(@Valid @RequestBody PortfolioActionData portfolioActionData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.deleteOwnPortfolioImage(accountId, portfolioActionData.getPortfolioId());
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/admin/review_moderation_queue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> reviewModerationQueue(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        return new ResponseEntity<>(appService.getReviewModerationQueue(), HttpStatus.OK);
    }

    @PostMapping("/admin/update_review_moderation")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> updateReviewModeration(@RequestBody Map<String, Object> body, HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        Long reviewId = body.get("reviewId") == null ? null : Long.valueOf(String.valueOf(body.get("reviewId")));
        return new ResponseEntity<>(appService.updateReviewModeration(reviewId, String.valueOf(body.get("action")), adminId), HttpStatus.OK);
    }

    @PostMapping("/create_review")
    public ResponseEntity <BaseResponse> createReview(@Valid @RequestBody ReviewData reviewData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        // The reviewer is the authenticated account — never trust the body's userId.
        reviewData.setUserId(accountId);
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
    @PostMapping("/booking_estimate")
    public ResponseEntity<BaseResponse> bookingEstimate(@RequestBody BookAppointmentData bookAppointmentData){
        BaseResponse baseResponse = appService.estimateBooking(bookAppointmentData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/book_appointment")
    public ResponseEntity <BaseResponse> bookAppointment(@Valid @RequestBody BookAppointmentData bookAppointmentData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        // The booking owner is the authenticated account — never trust the body's userId.
        bookAppointmentData.setUserId(accountId);
        String idempotencyKey = request.getHeader("Idempotency-Key");
        bookAppointmentData.setIdempotencyKey(idempotencyKey);
        BaseResponse baseResponse = appService.bookAppointment(bookAppointmentData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler_appointments")
    public ResponseEntity<BaseResponse> stylerAppointments(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.stylerAppointments(accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler_availability")
    public ResponseEntity<BaseResponse> stylerAvailability(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.stylerAvailability(accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/update_styler_availability")
    public ResponseEntity<BaseResponse> updateStylerAvailability(@Valid @RequestBody AvailabilityUpdateData availabilityData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.updateStylerAvailability(accountId, availabilityData.getSlots());
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler/travel_settings")
    public ResponseEntity<BaseResponse> stylerTravelSettings(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.stylerTravelSettings(accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/update_styler_travel_settings")
    public ResponseEntity<BaseResponse> updateStylerTravelSettings(@RequestBody Map<String, Object> body, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        Object included = body.get("includedTravelKm");
        Double includedKm = included == null ? null : Double.valueOf(included.toString());
        String fee = body.get("baseTravelFee") == null ? null : String.valueOf(body.get("baseTravelFee"));
        BaseResponse baseResponse = appService.updateStylerTravelSettings(accountId, includedKm, fee);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler_availability_exceptions")
    public ResponseEntity<BaseResponse> stylerAvailabilityExceptions(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.stylerAvailabilityExceptions(accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/add_availability_exception")
    public ResponseEntity<BaseResponse> addAvailabilityException(@Valid @RequestBody ExceptionData exceptionData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.addAvailabilityException(accountId, exceptionData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/delete_availability_exception")
    public ResponseEntity<BaseResponse> deleteAvailabilityException(@RequestBody Map<String, Long> body, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.deleteAvailabilityException(accountId, body.get("exceptionId"));
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/accept_appointment")
    public ResponseEntity <BaseResponse> acceptAppointment(@Valid @RequestBody AppointmentActionData appointmentActionData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.acceptAppointment(accountId, appointmentActionData.getAppointmentId(),
                appointmentActionData.getDecisionNote());
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/decline_appointment")
    public ResponseEntity <BaseResponse> declineAppointment(@Valid @RequestBody AppointmentActionData appointmentActionData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.declineAppointment(accountId, appointmentActionData.getAppointmentId(),
                appointmentActionData.getDecisionNote());
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/complete_appointment")
    public ResponseEntity <BaseResponse> completeAppointment(@Valid @RequestBody AppointmentActionData appointmentActionData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.completeAppointment(accountId, appointmentActionData.getAppointmentId());
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/retry_appointment_payment")
    public ResponseEntity<BaseResponse> retryAppointmentPayment(@Valid @RequestBody AppointmentActionData action, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        return new ResponseEntity<>(appService.retryAppointmentPayment(accountId, action.getAppointmentId()), HttpStatus.OK);
    }

    @PostMapping("/cancel_appointment")
    public ResponseEntity <BaseResponse> cancelAppointment(@Valid @RequestBody AppointmentActionData appointmentActionData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.cancelAppointment(accountId, appointmentActionData.getAppointmentId());
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/styler_cancel_appointment")
    public ResponseEntity <BaseResponse> stylerCancelAppointment(@Valid @RequestBody AppointmentActionData appointmentActionData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.stylerCancelAppointment(accountId, appointmentActionData.getAppointmentId());
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/notifications")
    public ResponseEntity<BaseResponse> notifications(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        BaseResponse baseResponse = appService.listNotifications(accountId);
        return new ResponseEntity<>(baseResponse, HttpStatus.OK);
    }

    @PostMapping("/notifications/read")
    public ResponseEntity<BaseResponse> markNotificationRead(@RequestBody Map<String, Long> body, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        BaseResponse baseResponse = appService.markNotificationRead(accountId, body.get("notificationId"));
        return new ResponseEntity<>(baseResponse, HttpStatus.OK);
    }

    @PostMapping("/notifications/read_all")
    public ResponseEntity<BaseResponse> markAllNotificationsRead(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        BaseResponse baseResponse = appService.markAllNotificationsRead(accountId);
        return new ResponseEntity<>(baseResponse, HttpStatus.OK);
    }

    @GetMapping("/notification_preferences")
    public ResponseEntity<BaseResponse> notificationPreferences(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        return new ResponseEntity<>(appService.getNotificationPreferences(accountId), HttpStatus.OK);
    }

    @PostMapping("/notification_preferences")
    public ResponseEntity<BaseResponse> updateNotificationPreferences(@RequestBody NotificationPreferencesData data, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        return new ResponseEntity<>(appService.updateNotificationPreferences(accountId, data), HttpStatus.OK);
    }

    @GetMapping("/user_appointments")
    public ResponseEntity<BaseResponse> userAppointments(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.listUserAppointment(accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/saved_stylists")
    public ResponseEntity<BaseResponse> savedStylists(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.listSavedStylists(accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(), "400")) ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse, status);
    }

    @PostMapping("/save_stylist")
    public ResponseEntity<BaseResponse> saveStylist(@RequestBody Map<String, String> body, HttpServletRequest request){
        String stylerId = body.getOrDefault("stylerId", "");
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.saveStylist(accountId, stylerId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(), "400")) ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse, status);
    }

    @PostMapping("/remove_saved_stylist")
    public ResponseEntity<BaseResponse> removeSavedStylist(@RequestBody Map<String, String> body, HttpServletRequest request){
        String stylerId = body.getOrDefault("stylerId", "");
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.removeSavedStylist(accountId, stylerId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(), "400")) ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse, status);
    }

    @PostMapping("/support_tickets")
    public ResponseEntity<BaseResponse> createSupportTicket(@Valid @RequestBody SupportTicketData data, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        return new ResponseEntity<>(appService.createSupportTicket(accountId, data), HttpStatus.OK);
    }

    @GetMapping("/support_tickets")
    public ResponseEntity<BaseResponse> supportTickets(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        return new ResponseEntity<>(appService.listSupportTickets(accountId), HttpStatus.OK);
    }

    @GetMapping("/loyalty_account")
    public ResponseEntity<BaseResponse> loyaltyAccount(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        return new ResponseEntity<>(appService.getLoyaltyAccount(accountId), HttpStatus.OK);
    }

    @PostMapping("/apply_referral")
    public ResponseEntity<BaseResponse> applyReferral(@RequestBody Map<String, String> body, HttpServletRequest request){
        String referralCode = body.getOrDefault("referralCode", "");
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        return new ResponseEntity<>(appService.createReferral(accountId, referralCode), HttpStatus.OK);
    }

    @GetMapping("/user_pending_appointments")
    public ResponseEntity<BaseResponse> userPendingAppointments(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.listUserPendingAppointment(accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/update_user_data")
    public ResponseEntity <BaseResponse> updateUserData(@Valid @RequestBody UpdateData updateData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        BaseResponse baseResponse = appService.updateUserData(updateData, accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/add_feedback")
    public ResponseEntity<BaseResponse> addFeedBack(@Valid @RequestBody UserFeedbackData userFeedbackData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        // Feedback is attributed to the authenticated account.
        userFeedbackData.setUserId(accountId);
        BaseResponse baseResponse = appService.addUserFeedBack(userFeedbackData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_feedback")
    public ResponseEntity<BaseResponse> listFeedback(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        BaseResponse baseResponse = appService.listUserFeedBack();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    @PostMapping("/update_card_details")
    public ResponseEntity<BaseResponse> updateCardDetails(@Valid @RequestBody CardDetailsData cardDetailsData, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null){
            return unauthorized();
        }
        // Card details are attached to the authenticated account.
        cardDetailsData.setUserId(accountId);
        BaseResponse baseResponse = appService.updateUserCardDetails(cardDetailsData);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }
    /** Stripe Connect Express onboarding: creates/reuses the stylist account and returns the hosted onboarding link. */
    @PostMapping("/styler/connect_account")
    public ResponseEntity<BaseResponse> stylerConnectAccount(@RequestBody Map<String, String> body, HttpServletRequest request){
        String stylerId = currentAccountId(request);
        if(stylerId == null) return unauthorized();
        String fallback = request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort())
                + "/styler-dashboard";
        String returnUrl = body.getOrDefault("returnUrl", fallback);
        String refreshUrl = body.getOrDefault("refreshUrl", fallback);
        BaseResponse baseResponse = appService.createStylerConnectAccount(stylerId, returnUrl, refreshUrl);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/admin/styler_business_summaries")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminStylerBusinessSummaries(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        return new ResponseEntity<>(appService.getAdminStylerBusinessSummaries(), HttpStatus.OK);
    }

    @GetMapping("/admin/styler_connect_statuses")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> adminStylerConnectStatuses(){
        BaseResponse baseResponse = appService.getAdminStylerConnectStatuses();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler/payouts")
    public ResponseEntity<BaseResponse> stylerPayouts(HttpServletRequest request){
        String stylerId = currentAccountId(request);
        if(stylerId == null) return unauthorized();
        return new ResponseEntity<>(appService.getStylerPayouts(stylerId), HttpStatus.OK);
    }

    @GetMapping("/styler/business_summary")
    public ResponseEntity<BaseResponse> stylerBusinessSummary(HttpServletRequest request){
        String stylerId = currentAccountId(request);
        if(stylerId == null) return unauthorized();
        return new ResponseEntity<>(appService.getStylerBusinessSummary(stylerId), HttpStatus.OK);
    }

    @GetMapping("/styler/connect_status")
    public ResponseEntity<BaseResponse> stylerConnectStatus(HttpServletRequest request){
        String stylerId = currentAccountId(request);
        if(stylerId == null) return unauthorized();
        return new ResponseEntity<>(appService.getStylerConnectStatus(stylerId), HttpStatus.OK);
    }

    /** Returns a Stripe SetupIntent clientSecret so the frontend can collect a card inside Elements. */
    @GetMapping("/card_setup_intent")
    public ResponseEntity<BaseResponse> cardSetupIntent(HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        BaseResponse baseResponse = appService.getCardSetupIntent(accountId);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    /** Stripe webhook (signature-verified). Public: no API key or JWT required. */
    @PostMapping("/stripe/webhook")
    public ResponseEntity<String> stripeWebhook(@RequestBody String payload,
                                                @RequestHeader(value = "Stripe-Signature", required = false) String signature){
        if(signature == null || signature.isEmpty()){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing Stripe-Signature header");
        }
        try {
            appService.handleStripeWebhook(payload, signature);
            return ResponseEntity.ok("ok");
        } catch(IllegalArgumentException ex){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        } catch(Exception ex){
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Webhook handling failed");
        }
    }

    @GetMapping("/admin/support_tickets")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminSupportTickets(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        return new ResponseEntity<>(appService.listAllSupportTickets(), HttpStatus.OK);
    }

    @PostMapping("/admin/update_support_ticket")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminUpdateSupportTicket(@Valid @RequestBody SupportTicketActionData data, HttpServletRequest request){
        String adminId = currentAccountId(request);
        return new ResponseEntity<>(appService.updateSupportTicket(data, adminId), HttpStatus.OK);
    }

    @GetMapping("/admin/settings/commission")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminCommissionSetting(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        return new ResponseEntity<>(appService.getCommissionSetting(adminId), HttpStatus.OK);
    }

    @PostMapping("/admin/settings/commission")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminUpdateCommissionSetting(@RequestBody Map<String, Object> body, HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        double percent = 0;
        Object value = body.get("commissionPercent");
        if(value instanceof Number){
            percent = ((Number) value).doubleValue();
        } else if(value instanceof String){
            try { percent = Double.parseDouble((String) value); } catch(NumberFormatException ignored){}
        }
        BaseResponse baseResponse = appService.updateCommissionSetting(adminId, percent);
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/admin/recovery_campaigns")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminRecoveryCampaigns(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        BaseResponse baseResponse = appService.listRecoveryCampaigns();
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/admin/kpis")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminKpis(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        return new ResponseEntity<>(appService.getAdminKpis(), HttpStatus.OK);
    }

    /** Read-cache effectiveness (hits/misses/loads/evictions + per-key top) since boot. */
    @GetMapping("/admin/cache_stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminCacheStats(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        return new ResponseEntity<>(appService.getCacheStats(), HttpStatus.OK);
    }

    @GetMapping("/admin/audit_logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminAuditLogs(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        return new ResponseEntity<>(appService.listAuditLogs(), HttpStatus.OK);
    }

    /** Admin-only: sends a test email through EmailConfig to verify delivery. */
    @PostMapping("/admin/test_email")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminTestEmail(@RequestBody Map<String, String> body, HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        BaseResponse baseResponse = appService.sendTestEmail(adminId, body.get("recipient"));
        HttpStatus status = (Objects.equals(baseResponse.getStatusCode(), "200") || Objects.equals(baseResponse.getStatusCode(),"400"))?HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return new ResponseEntity<>(baseResponse,status);
    }

    /** Decrypts a value encrypted with AES/GCM on the backend. The frontend calls this
     *  instead of holding the decryption key itself. */
    @PostMapping("/decrypt")
    public ResponseEntity<BaseResponse> decrypt(@RequestBody Map<String, String> body, HttpServletRequest request){
        String accountId = currentAccountId(request);
        if(accountId == null) return unauthorized();
        String encrypted = body.get("encrypted");
        if(encrypted == null || encrypted.isBlank()){
            BaseResponse resp = new BaseResponse();
            resp.setStatusCode(ERROR_STATUS_CODE);
            resp.setMessage("Missing 'encrypted' field");
            return ResponseEntity.badRequest().body(resp);
        }
        try {
            String plaintext = encryptionConfig.decrypt(encrypted);
            BaseResponse resp = new BaseResponse();
            resp.setStatusCode(SUCCESS_STATUS_CODE);
            resp.setMessage(SUCCESS_MESSAGE);
            resp.setData(Map.of("value", plaintext));
            return ResponseEntity.ok(resp);
        } catch(Exception e){
            BaseResponse resp = new BaseResponse();
            resp.setStatusCode(ERROR_STATUS_CODE);
            resp.setMessage("Decryption failed");
            return ResponseEntity.badRequest().body(resp);
        }
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
        // Validate the old token first
        var tokenEntity = refreshTokenService.validate(refreshTokenValue);
        if (tokenEntity == null) {
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
        return ResponseEntity.ok(response);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<BaseResponse> logout(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String accountId = currentAccountId(request);
        String role = currentRole(request);
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
        return ResponseEntity.ok(response);
    }

    @GetMapping("/admin/failed_events")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminFailedEvents(HttpServletRequest request) {
        String adminId = requireAdmin(request);
        if (adminId == null) return unauthorized();
        return new ResponseEntity<>(appService.listFailedOutboxEvents(), HttpStatus.OK);
    }

    @PostMapping("/admin/failed_events/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminRetryFailedEvent(@PathVariable("id") Long id, HttpServletRequest request) {
        String adminId = requireAdmin(request);
        if (adminId == null) return unauthorized();
        return new ResponseEntity<>(appService.retryFailedOutboxEvent(id, adminId), HttpStatus.OK);
    }

    @PostMapping("/admin/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminRefund(@RequestBody RefundRequestData data, HttpServletRequest request) {
        String adminId = requireAdmin(request);
        if (adminId == null) return unauthorized();
        // Step-up: refunds move real money, so the acting admin must re-prove their
        // password (X-Step-Up-Password), not just present a valid session token.
        if (!stepUpService.verify(adminId, request.getHeader("X-Step-Up-Password"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(stepUpRequired());
        }
        return new ResponseEntity<>(appService.adminRefund(adminId, data), HttpStatus.OK);
    }

    @GetMapping("/admin/refunds")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminRefunds(HttpServletRequest request) {
        String adminId = requireAdmin(request);
        if (adminId == null) return unauthorized();
        return new ResponseEntity<>(appService.adminRefunds(adminId), HttpStatus.OK);
    }

    @GetMapping("/admin/payment_reconciliation")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminPaymentReconciliation(HttpServletRequest request) {
        String adminId = requireAdmin(request);
        if (adminId == null) return unauthorized();
        return new ResponseEntity<>(paymentReconciliationService.runReconciliation(), HttpStatus.OK);
    }

    /** Returns the authenticated account id set by JwtAuthFilter, or null when the request has no valid token. */
    private String currentAccountId(HttpServletRequest request){
        Object accountId = request.getAttribute("accountId");
        return (accountId == null) ? null : String.valueOf(accountId);
    }

    /** Returns the JWT role claim set by JwtAuthFilter, or null. */
    private String currentRole(HttpServletRequest request){
        Object role = request.getAttribute("role");
        return (role == null) ? null : String.valueOf(role);
    }

    /** Returns the account id only if the JWT role is ADMIN; otherwise null. */
    private String requireAdmin(HttpServletRequest request){
        if (!"ADMIN".equals(currentRole(request))) return null;
        return currentAccountId(request);
    }

    private ResponseEntity<BaseResponse> unauthorized(){
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode("401");
        baseResponse.setMessage("Authentication required");
        baseResponse.setData(EMPTY_DATA);
        return new ResponseEntity<>(baseResponse, HttpStatus.UNAUTHORIZED);
    }

    private BaseResponse stepUpRequired(){
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode("403");
        baseResponse.setMessage("Re-authentication required. Please re-enter your admin password.");
        baseResponse.setData(EMPTY_DATA);
        return baseResponse;
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

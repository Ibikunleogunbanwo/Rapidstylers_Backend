package com.macrotel.rapidstylers.styler.controller;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.styler.pojo.ServiceUpdateData;
import com.macrotel.rapidstylers.styler.pojo.StylerBusinessInfoData;
import com.macrotel.rapidstylers.styler.pojo.StylerData;
import com.macrotel.rapidstylers.styler.pojo.StylerPersonalInfoData;
import com.macrotel.rapidstylers.styler.pojo.StylerPhotosData;
import com.macrotel.rapidstylers.styler.pojo.SubServiceData;
import com.macrotel.rapidstylers.styler.pojo.VerificationActionData;
import com.macrotel.rapidstylers.styler.service.StylerProfileService;
import com.macrotel.rapidstylers.styler.service.StylerService;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Stylist sign-up, verification, sub-services and the public profile page. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class StylerController {
    @Autowired
    StylerService stylerService;
    @Autowired
    StylerProfileService stylerProfileService;
    @Autowired(required = false)
    AuditService auditService;

    /** The signed-in stylist's own editable profile. */
    @GetMapping("/styler/profile")
    public ResponseEntity<BaseResponse> ownProfile(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(stylerProfileService.profile(accountId));
    }

    @PostMapping("/styler/update_personal_info")
    public ResponseEntity<BaseResponse> updatePersonalInfo(@Valid @RequestBody StylerPersonalInfoData data, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(stylerProfileService.updatePersonalInfo(accountId, data));
    }

    @PostMapping("/styler/update_business_info")
    public ResponseEntity<BaseResponse> updateBusinessInfo(@Valid @RequestBody StylerBusinessInfoData data, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(stylerProfileService.updateBusinessInfo(accountId, data));
    }

    @PostMapping("/styler/update_photos")
    public ResponseEntity<BaseResponse> updatePhotos(@Valid @RequestBody StylerPhotosData data, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(stylerProfileService.updatePhotos(accountId, data));
    }

    @PostMapping("/create_styler")
    public ResponseEntity <BaseResponse> createStyler(@Valid @RequestBody StylerData stylerData){
        BaseResponse baseResponse = stylerService.createStyler(stylerData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/admin/styler_verification_queue")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity <BaseResponse> stylerVerificationQueue(){
        BaseResponse baseResponse = stylerService.getStylerVerificationQueue();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/admin/update_styler_verification")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity <BaseResponse> updateStylerVerification(@Valid @RequestBody VerificationActionData verificationActionData,
                                                                   HttpServletRequest request){
        BaseResponse baseResponse = stylerService.updateStylerVerification(verificationActionData);
        // Owners and managers both decide applications, so record who did.
        String adminId = CurrentAccount.adminId(request);
        if(auditService != null && adminId != null && SUCCESS_STATUS_CODE.equals(baseResponse.getStatusCode())){
            auditService.audit(adminId, "ADMIN", "STYLER_VERIFICATION_UPDATED", "STYLER",
                    verificationActionData.getStylerId(), verificationActionData.getAction());
        }
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/create_sub_service")
    public ResponseEntity <BaseResponse> createSubService(@Valid @RequestBody SubServiceData subServiceData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        // The authenticated stylist owns the service; never trust the body stylerId.
        subServiceData.setStylerId(accountId);
        BaseResponse baseResponse = stylerService.createSubService(subServiceData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler_own_sub_services")
    public ResponseEntity <BaseResponse> ownStylerSubServices(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = stylerService.listOwnStylerSubService(accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/update_sub_service")
    public ResponseEntity <BaseResponse> updateSubService(@Valid @RequestBody ServiceUpdateData data, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(stylerService.updateSubService(accountId, data));
    }

    @GetMapping("/list_sub_service")
    public ResponseEntity <BaseResponse> listStylerSubService(@RequestParam("stylerId") String stylerId){
        BaseResponse baseResponse = stylerService.listStylerSubService(stylerId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/single_styler")
    public ResponseEntity<BaseResponse> singleStyler(@RequestParam("stylerId") String stylerId){
        BaseResponse baseResponse = stylerService.getStylerDetails(stylerId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }
}

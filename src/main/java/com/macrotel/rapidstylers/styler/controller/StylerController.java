package com.macrotel.rapidstylers.styler.controller;

import com.macrotel.rapidstylers.styler.pojo.ServiceUpdateData;
import com.macrotel.rapidstylers.styler.pojo.StylerData;
import com.macrotel.rapidstylers.styler.pojo.SubServiceData;
import com.macrotel.rapidstylers.styler.pojo.VerificationActionData;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.controller.ApiResponses;
import com.macrotel.rapidstylers.pojo.*;
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

    @PostMapping("/create_styler")
    public ResponseEntity <BaseResponse> createStyler(@Valid @RequestBody StylerData stylerData){
        BaseResponse baseResponse = stylerService.createStyler(stylerData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/admin/styler_verification_queue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> stylerVerificationQueue(){
        BaseResponse baseResponse = stylerService.getStylerVerificationQueue();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/admin/update_styler_verification")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> updateStylerVerification(@Valid @RequestBody VerificationActionData verificationActionData){
        BaseResponse baseResponse = stylerService.updateStylerVerification(verificationActionData);
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

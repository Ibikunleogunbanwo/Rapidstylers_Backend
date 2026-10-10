package com.macrotel.rapidstylers.customer.controller;

import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.customer.pojo.UpdateData;
import com.macrotel.rapidstylers.customer.service.CustomerService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Customer profile and saved stylists. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class CustomerController {
    @Autowired
    CustomerService customerService;

    @GetMapping("/user_data")
    public ResponseEntity<BaseResponse> singleUserData(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = customerService.singleUserData(accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/saved_stylists")
    public ResponseEntity<BaseResponse> savedStylists(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = customerService.listSavedStylists(accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse, status);
    }

    @PostMapping("/save_stylist")
    public ResponseEntity<BaseResponse> saveStylist(@RequestBody Map<String, String> body, HttpServletRequest request){
        String stylerId = body.getOrDefault("stylerId", "");
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = customerService.saveStylist(accountId, stylerId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse, status);
    }

    @PostMapping("/remove_saved_stylist")
    public ResponseEntity<BaseResponse> removeSavedStylist(@RequestBody Map<String, String> body, HttpServletRequest request){
        String stylerId = body.getOrDefault("stylerId", "");
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = customerService.removeSavedStylist(accountId, stylerId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse, status);
    }

    @PostMapping("/update_user_data")
    public ResponseEntity <BaseResponse> updateUserData(@Valid @RequestBody UpdateData updateData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = customerService.updateUserData(updateData, accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }
}

package com.macrotel.rapidstylers.engagement.controller;

import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.controller.ApiResponses;
import com.macrotel.rapidstylers.engagement.service.LoyaltyService;
import com.macrotel.rapidstylers.pojo.*;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Loyalty points and referral codes. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class LoyaltyController {
    @Autowired
    LoyaltyService loyaltyService;

    @GetMapping("/loyalty_account")
    public ResponseEntity<BaseResponse> loyaltyAccount(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(loyaltyService.getLoyaltyAccount(accountId));
    }

    @PostMapping("/apply_referral")
    public ResponseEntity<BaseResponse> applyReferral(@RequestBody Map<String, String> body, HttpServletRequest request){
        String referralCode = body.getOrDefault("referralCode", "");
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(loyaltyService.createReferral(accountId, referralCode));
    }
}

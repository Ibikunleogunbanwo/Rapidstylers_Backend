package com.macrotel.rapidstylers.payment.controller;

import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.payment.service.PromotionService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Promo codes: the customer's "does my code work?" check, and the admin's list and editor. */
@RestController
@RequestMapping("/rapid_stylers")
public class PromotionController {

    @Autowired
    PromotionService promotionService;

    /** Body: {code, subtotal}. Answers what the code takes off; booking re-checks it. */
    @PostMapping("/check_promo_code")
    public ResponseEntity<BaseResponse> check(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String userId = CurrentAccount.id(request);
        if (userId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(promotionService.check(userId, text(body.get("code")), text(body.get("subtotal"))));
    }

    @GetMapping("/admin/promo_codes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> list(HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(promotionService.list());
    }

    /**
     * Body: {id?, code, description?, discountType PERCENT|FIXED, discountValue,
     * maxDiscount?, minBooking?, startsAt?, expiresAt?, maxRedemptions?,
     * maxPerCustomer?, firstBookingOnly?, active?}. No id adds a new code.
     */
    @PostMapping("/admin/save_promo_code")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> save(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(promotionService.save(adminId, body));
    }

    @GetMapping("/admin/promo_redemptions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> redemptions(@RequestParam(required = false) Long promoId, HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(promotionService.redemptions(promoId));
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}

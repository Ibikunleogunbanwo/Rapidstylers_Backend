package com.macrotel.rapidstylers.payment.controller;

import com.macrotel.rapidstylers.auth.service.StepUpService;
import com.macrotel.rapidstylers.booking.pojo.ServiceDisputeData;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.payment.pojo.CardDetailsData;
import com.macrotel.rapidstylers.payment.pojo.RefundRequestData;
import com.macrotel.rapidstylers.payment.service.CardService;
import com.macrotel.rapidstylers.payment.service.ConnectAccountService;
import com.macrotel.rapidstylers.payment.service.PaymentReconciliationService;
import com.macrotel.rapidstylers.payment.service.PaymentService;
import com.macrotel.rapidstylers.payment.service.StylerPayoutSummary;
import com.macrotel.rapidstylers.payment.service.PayoutReleaseService;
import com.macrotel.rapidstylers.payment.service.RefundReviewService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Stripe Connect payouts, saved cards, refunds and the platform commission. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class PaymentController {
    @Autowired
    CardService cardService;
    @Autowired
    ConnectAccountService connectAccountService;
    @Autowired
    PaymentService paymentService;
    @Autowired
    StylerPayoutSummary stylerPayoutSummary;
    @Autowired
    StepUpService stepUpService;
    @Autowired
    PaymentReconciliationService paymentReconciliationService;
    @Autowired
    RefundReviewService refundReviewService;
    @Autowired
    PayoutReleaseService payoutReleaseService;

    @PostMapping("/update_card_details")
    public ResponseEntity<BaseResponse> updateCardDetails(@Valid @RequestBody CardDetailsData cardDetailsData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        // Card details are attached to the authenticated account.
        cardDetailsData.setUserId(accountId);
        BaseResponse baseResponse = cardService.updateUserCardDetails(cardDetailsData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    /** Stripe Connect Express onboarding: creates/reuses the stylist account and returns the hosted onboarding link. */
    @PostMapping("/styler/connect_account")
    public ResponseEntity<BaseResponse> stylerConnectAccount(@RequestBody Map<String, String> body, HttpServletRequest request){
        String stylerId = CurrentAccount.id(request);
        if(stylerId == null) return CurrentAccount.unauthorized();
        String fallback = request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort())
                + "/styler-dashboard";
        String returnUrl = body.getOrDefault("returnUrl", fallback);
        String refreshUrl = body.getOrDefault("refreshUrl", fallback);
        BaseResponse baseResponse = connectAccountService.createStylerConnectAccount(stylerId, returnUrl, refreshUrl);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/admin/styler_business_summaries")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminStylerBusinessSummaries(HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(connectAccountService.getAdminStylerBusinessSummaries());
    }

    @GetMapping("/admin/styler_connect_statuses")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> adminStylerConnectStatuses(){
        BaseResponse baseResponse = connectAccountService.getAdminStylerConnectStatuses();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler/payouts")
    public ResponseEntity<BaseResponse> stylerPayouts(HttpServletRequest request){
        String stylerId = CurrentAccount.id(request);
        if(stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(stylerPayoutSummary.getStylerPayouts(stylerId));
    }

    @GetMapping("/styler/business_summary")
    public ResponseEntity<BaseResponse> stylerBusinessSummary(HttpServletRequest request){
        String stylerId = CurrentAccount.id(request);
        if(stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(connectAccountService.getStylerBusinessSummary(stylerId));
    }

    @GetMapping("/styler/connect_status")
    public ResponseEntity<BaseResponse> stylerConnectStatus(HttpServletRequest request){
        String stylerId = CurrentAccount.id(request);
        if(stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(connectAccountService.getStylerConnectStatus(stylerId));
    }

    /** Returns a Stripe SetupIntent clientSecret so the frontend can collect a card inside Elements. */
    @GetMapping("/card_setup_intent")
    public ResponseEntity<BaseResponse> cardSetupIntent(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        BaseResponse baseResponse = cardService.getCardSetupIntent(accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/admin/settings/commission")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminCommissionSetting(HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(paymentService.getCommissionSetting(adminId));
    }

    @PostMapping("/admin/settings/commission")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminUpdateCommissionSetting(@RequestBody Map<String, Object> body, HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        double percent = 0;
        Object value = body.get("commissionPercent");
        if(value instanceof Number){
            percent = ((Number) value).doubleValue();
        } else if(value instanceof String){
            try { percent = Double.parseDouble((String) value); } catch(NumberFormatException ignored){}
        }
        BaseResponse baseResponse = paymentService.updateCommissionSetting(adminId, percent);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/admin/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminRefund(@RequestBody RefundRequestData data, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        // Step-up: refunds move real money, so the acting admin must re-prove their
        // password (X-Step-Up-Password), not just present a valid session token.
        if (!stepUpService.verify(adminId, request.getHeader("X-Step-Up-Password"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(stepUpRequired());
        }
        return ApiResponses.respond(paymentService.adminRefund(adminId, data));
    }

    @GetMapping("/admin/refunds")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminRefunds(HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(refundReviewService.refundsWithPayments());
    }

    /** The booking payment behind an appointment: charged, refunded, still refundable, paid out. */
    @GetMapping("/admin/refund_context")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminRefundContext(@RequestParam("appointmentId") String appointmentId,
                                                           HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(refundReviewService.paymentContext(appointmentId));
    }

    /** Completed, charged bookings whose stylist has not been paid and no payout is scheduled. */
    @GetMapping("/admin/unpaid_payouts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminUnpaidPayouts(HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(payoutReleaseService.unpaidPayouts());
    }

    /** Sends one unpaid stylist payout now; held again if the stylist still has no payout account. */
    @PostMapping("/admin/pay_stylist_now")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminPayStylistNow(@Valid @RequestBody ServiceDisputeData data,
                                                           HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(payoutReleaseService.payNow(adminId, data.getAppointmentId()));
    }

    /** Completed appointments whose customer says they didn't happen; their stylist payouts are on hold. */
    @GetMapping("/admin/service_disputes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminServiceDisputes(HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(payoutReleaseService.openDisputes());
    }

    /** Closes a dispute: refund first if owed, then this pays the stylist their share of what was kept. */
    @PostMapping("/admin/resolve_service_dispute")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminResolveServiceDispute(@Valid @RequestBody ServiceDisputeData data,
                                                                   HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(payoutReleaseService.resolveDispute(adminId, data.getAppointmentId(), data.getNote()));
    }

    @GetMapping("/admin/payment_reconciliation")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminPaymentReconciliation(HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(paymentReconciliationService.runReconciliation());
    }

    private BaseResponse stepUpRequired(){
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode("403");
        baseResponse.setMessage("Re-authentication required. Please re-enter your admin password.");
        baseResponse.setData(EMPTY_DATA);
        return baseResponse;
    }
}

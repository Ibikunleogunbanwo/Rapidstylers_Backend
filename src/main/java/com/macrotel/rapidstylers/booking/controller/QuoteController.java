package com.macrotel.rapidstylers.booking.controller;

import com.macrotel.rapidstylers.booking.pojo.QuoteOfferData;
import com.macrotel.rapidstylers.booking.pojo.QuoteRequestData;
import com.macrotel.rapidstylers.booking.service.QuoteService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Custom quotes. Customers ask and answer under /quotes; stylists price them
 * under /styler/quotes. Whose quote it is always comes from the token.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class QuoteController {
    @Autowired
    QuoteService quoteService;

    @PostMapping("/quotes/request")
    public ResponseEntity<BaseResponse> request(@Valid @RequestBody QuoteRequestData data, HttpServletRequest request) {
        String userId = CurrentAccount.id(request);
        if (userId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(quoteService.request(userId, data));
    }

    @GetMapping("/quotes")
    public ResponseEntity<BaseResponse> customerQuotes(HttpServletRequest request) {
        String userId = CurrentAccount.id(request);
        if (userId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(quoteService.listForCustomer(userId));
    }

    @PostMapping("/quotes/decline")
    public ResponseEntity<BaseResponse> decline(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userId = CurrentAccount.id(request);
        if (userId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(quoteService.decline(userId, body.getOrDefault("quoteRef", "")));
    }

    @GetMapping("/styler/quotes")
    public ResponseEntity<BaseResponse> stylistQuotes(HttpServletRequest request) {
        String stylerId = CurrentAccount.id(request);
        if (stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(quoteService.listForStylist(stylerId));
    }

    @PostMapping("/styler/quotes/offer")
    public ResponseEntity<BaseResponse> offer(@Valid @RequestBody QuoteOfferData data, HttpServletRequest request) {
        String stylerId = CurrentAccount.id(request);
        if (stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(quoteService.offer(stylerId, data));
    }

    @PostMapping("/styler/quotes/withdraw")
    public ResponseEntity<BaseResponse> withdraw(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String stylerId = CurrentAccount.id(request);
        if (stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(quoteService.withdraw(stylerId, body.getOrDefault("quoteRef", "")));
    }
}

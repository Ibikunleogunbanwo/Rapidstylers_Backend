package com.macrotel.rapidstylers.styler.controller;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.styler.pojo.PortfolioActionData;
import com.macrotel.rapidstylers.styler.pojo.StylerPortfolioData;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.styler.service.PortfolioService;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Stylist portfolio photos. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class PortfolioController {
    @Autowired
    PortfolioService portfolioService;

    @PostMapping("/create_portfolio")
    public ResponseEntity <BaseResponse> createPortfolio(@Valid @RequestBody StylerPortfolioData stylerPortfolioData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        // The portfolio owner is the authenticated styler — never trust the body's stylerId.
        stylerPortfolioData.setStylerId(accountId);
        BaseResponse baseResponse = portfolioService.createStylerPortfolio(stylerPortfolioData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler_own_portfolio")
    public ResponseEntity <BaseResponse> stylerOwnPortfolio(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = portfolioService.listOwnStylerPortfolio(accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_portfolio")
    public ResponseEntity<BaseResponse> listStylerPortfolio(@RequestParam("stylerId") String stylerId){
        BaseResponse baseResponse = portfolioService.listStylerPortfolio(stylerId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/admin/all_portfolios")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> allPortfolios(){
        BaseResponse baseResponse = portfolioService.listAllPortfolios();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/admin/delete_portfolio_image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> deletePortfolioImage(@Valid @RequestBody PortfolioActionData portfolioActionData){
        BaseResponse baseResponse = portfolioService.adminDeletePortfolioImage(portfolioActionData.getPortfolioId());
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    /** Stylist removes one of their own gallery images (ownership enforced from the token). */
    @PostMapping("/delete_portfolio_image")
    public ResponseEntity<BaseResponse> deleteOwnPortfolioImage(@Valid @RequestBody PortfolioActionData portfolioActionData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = portfolioService.deleteOwnPortfolioImage(accountId, portfolioActionData.getPortfolioId());
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }
}

package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.customer.service.CustomerService;
import com.macrotel.rapidstylers.booking.service.BookingService;
import com.macrotel.rapidstylers.payment.service.CardService;
import com.macrotel.rapidstylers.payment.service.ConnectAccountService;
import com.macrotel.rapidstylers.styler.service.AvailabilityService;
import com.macrotel.rapidstylers.styler.service.PortfolioService;
import com.macrotel.rapidstylers.styler.service.SearchService;
import com.macrotel.rapidstylers.styler.service.StylerService;
import com.macrotel.rapidstylers.engagement.service.FeedbackService;
import com.macrotel.rapidstylers.engagement.service.LoyaltyService;
import com.macrotel.rapidstylers.engagement.service.NotificationService;
import com.macrotel.rapidstylers.engagement.service.ReviewService;
import com.macrotel.rapidstylers.engagement.service.SupportService;
import com.macrotel.rapidstylers.catalog.service.CatalogService;
import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.service.AppService;
import com.macrotel.rapidstylers.config.EncryptionConfig;
import com.macrotel.rapidstylers.service.GalleryService;
import com.macrotel.rapidstylers.styler.service.GeocodingService;
import com.macrotel.rapidstylers.payment.service.PaymentReconciliationService;
import com.macrotel.rapidstylers.auth.service.StepUpService;
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
import java.util.logging.Logger;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@RestController
@RequestMapping("/rapid_stylers")
public class ApplicationController {
    /** Only the webhook endpoint logs here: every other method answers through AppService. */
    private static final Logger LOG = Logger.getLogger(ApplicationController.class.getName());

    @Autowired
    AppService appService;
    @Autowired
    GalleryService galleryService;
    @Autowired
    EncryptionConfig encryptionConfig;

    @GetMapping("/testing")
    public ResponseEntity <BaseResponse> testing(){
        BaseResponse baseResponse = appService.testing();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }
    @GetMapping("/gallery")
    public ResponseEntity <BaseResponse> gallery(
            @RequestParam("category") String category,
            @RequestParam(value = "per_page", defaultValue = "12") int perPage,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "query", defaultValue = "") String query){
        BaseResponse baseResponse = galleryService.searchGallery(category, perPage, page, query);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/admin/recovery_campaigns")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminRecoveryCampaigns(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        BaseResponse baseResponse = appService.listRecoveryCampaigns();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/admin/kpis")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminKpis(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        return ApiResponses.respond(appService.getAdminKpis());
    }

    /** Read-cache effectiveness (hits/misses/loads/evictions + per-key top) since boot. */
    @GetMapping("/admin/cache_stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminCacheStats(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        return ApiResponses.respond(appService.getCacheStats());
    }

    @GetMapping("/admin/audit_logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminAuditLogs(HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        return ApiResponses.respond(appService.listAuditLogs());
    }

    /** Admin-only: sends a test email through EmailConfig to verify delivery. */
    @PostMapping("/admin/test_email")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminTestEmail(@RequestBody Map<String, String> body, HttpServletRequest request){
        String adminId = currentAccountId(request);
        if(adminId == null) return unauthorized();
        BaseResponse baseResponse = appService.sendTestEmail(adminId, body.get("recipient"));
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    /** Admin-only: decrypts a value encrypted with AES/GCM under the app key. Locked
     *  down because any authenticated role could otherwise use it as a decryption
     *  oracle for arbitrary ciphertext (card user-ids are stored plaintext now, so no
     *  customer/styler flow needs it). */
    @PostMapping("/decrypt")
    @PreAuthorize("hasRole('ADMIN')")
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
            return ApiResponses.respond(resp);
        } catch(Exception e){
            BaseResponse resp = new BaseResponse();
            resp.setStatusCode(ERROR_STATUS_CODE);
            resp.setMessage("Decryption failed");
            return ResponseEntity.badRequest().body(resp);
        }
    }


    @GetMapping("/admin/failed_events")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminFailedEvents(HttpServletRequest request) {
        String adminId = requireAdmin(request);
        if (adminId == null) return unauthorized();
        return ApiResponses.respond(appService.listFailedOutboxEvents());
    }

    @PostMapping("/admin/failed_events/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminRetryFailedEvent(@PathVariable("id") Long id, HttpServletRequest request) {
        String adminId = requireAdmin(request);
        if (adminId == null) return unauthorized();
        return ApiResponses.respond(appService.retryFailedOutboxEvent(id, adminId));
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

package com.macrotel.rapidstylers.admin.controller;

import com.macrotel.rapidstylers.admin.service.AdminService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.config.EncryptionConfig;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Admin dashboard tools: KPIs, audit log, cache stats, test email, recovery campaigns, failed events and the decrypt helper. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class AdminController {
    @Autowired
    AdminService adminService;
    @Autowired
    EncryptionConfig encryptionConfig;
    @Autowired
    com.macrotel.rapidstylers.engagement.service.NotificationService notificationService;

    @GetMapping("/admin/recovery_campaigns")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminRecoveryCampaigns(HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        BaseResponse baseResponse = adminService.listRecoveryCampaigns();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/admin/kpis")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminKpis(HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminService.getAdminKpis());
    }

    /** Read-cache effectiveness (hits/misses/loads/evictions + per-key top) since boot. */
    @GetMapping("/admin/cache_stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminCacheStats(HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminService.getCacheStats());
    }

    @GetMapping("/admin/audit_logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminAuditLogs(@RequestParam(value = "page", required = false) Integer page,
                                                       @RequestParam(value = "size", required = false) Integer size,
                                                       @RequestParam(value = "action", required = false) String action,
                                                       @RequestParam(value = "actorId", required = false) String actorId,
                                                       @RequestParam(value = "resourceId", required = false) String resourceId,
                                                       HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        // Without paging or filters: the latest 100, as before.
        if(page == null && size == null && action == null && actorId == null && resourceId == null){
            return ApiResponses.respond(adminService.listAuditLogs());
        }
        return ApiResponses.respond(adminService.searchAuditLogs(page, size, action, actorId, resourceId));
    }

    /** Admin-only: an in-app announcement to one customer (recipientEmail) or all active customers. */
    @PostMapping("/admin/send_announcement")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminSendAnnouncement(@RequestBody Map<String, String> body, HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(notificationService.sendAnnouncement(adminId, body.get("title"),
                body.get("message"), body.get("recipientEmail")));
    }

    /** Admin-only: sends a test email through EmailConfig to verify delivery. */
    @PostMapping("/admin/test_email")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminTestEmail(@RequestBody Map<String, String> body, HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        BaseResponse baseResponse = adminService.sendTestEmail(adminId, body.get("recipient"));
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
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
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
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminService.listFailedOutboxEvents());
    }

    @PostMapping("/admin/failed_events/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminRetryFailedEvent(@PathVariable("id") Long id, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminService.retryFailedOutboxEvent(id, adminId));
    }
}

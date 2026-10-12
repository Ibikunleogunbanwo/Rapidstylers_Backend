package com.macrotel.rapidstylers.admin.controller;

import com.macrotel.rapidstylers.admin.service.ManagerView;
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
 * Admin dashboard tools: KPIs, audit log, cache stats, test email, announcements, ID photo links, recovery campaigns, failed events and the decrypt helper. Same URLs as before, under /rapid_stylers.
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
    @Autowired
    com.macrotel.rapidstylers.media.service.IdentificationImageService identificationImageService;
    @Autowired
    com.macrotel.rapidstylers.media.service.IdentificationImageMigrationService identificationImageMigrationService;

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
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<BaseResponse> adminKpis(HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        BaseResponse response = adminService.getAdminKpis();
        return ApiResponses.respond(ManagerView.isManager(request) ? ManagerView.kpis(response) : response);
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

    /**
     * Admin-only: an in-app announcement. audience CUSTOMERS (default) or STYLISTS;
     * with recipientEmail it goes to that one customer or stylist, without it to
     * every active customer or every active, approved stylist.
     */
    @PostMapping("/admin/send_announcement")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminSendAnnouncement(@RequestBody Map<String, String> body, HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(notificationService.sendAnnouncement(adminId, body.get("title"),
                body.get("message"), body.get("recipientEmail"), body.get("audience")));
    }

    /** Admin-only: sent announcements, newest first. */
    @GetMapping("/admin/announcements")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminAnnouncements(HttpServletRequest request){
        if(CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(notificationService.listAnnouncements());
    }

    /** Admin-only: how many customers (or, with audience=STYLISTS, approved stylists) an announcement to everyone reaches. */
    @GetMapping("/admin/announcement_audience_count")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminAnnouncementAudienceCount(@RequestParam(value = "audience", required = false) String audience,
                                                                       HttpServletRequest request){
        if(CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(notificationService.announcementAudienceCount(audience));
    }

    /**
     * Owner-only: moves ID photos still stored as public Cloudinary assets to
     * private ones. Body {dryRun (default true), afterId, limit}; call again with
     * the returned nextAfterId until it is null.
     */
    @PostMapping("/admin/migrate_identification_images")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminMigrateIdentificationImages(@RequestBody(required = false) Map<String, Object> body,
                                                                         HttpServletRequest request){
        String adminId = CurrentAccount.adminId(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        Map<String, Object> options = body == null ? Map.of() : body;
        // Anything but an explicit false is a dry run, so a mistyped call changes nothing.
        boolean dryRun = !(Boolean.FALSE.equals(options.get("dryRun")) || "false".equalsIgnoreCase(String.valueOf(options.get("dryRun"))));
        Long limit = number(options.get("limit"));
        return ApiResponses.respond(identificationImageMigrationService.migrate(adminId, dryRun,
                number(options.get("afterId")), limit == null ? null : (int) Math.min(limit, Integer.MAX_VALUE)));
    }

    /** A JSON number (or numeric string) as a Long, or null. */
    private static Long number(Object value){
        if(value instanceof Number) return ((Number) value).longValue();
        if(value instanceof String && ((String) value).trim().matches("\\d{1,18}")) return Long.parseLong(((String) value).trim());
        return null;
    }

    /** Admin-only: a 10-minute signed link to a stylist's private ID photo ({url, expiresAt}). */
    @GetMapping("/admin/identification_image")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<BaseResponse> adminIdentificationImage(@RequestParam(value = "stylerId", required = false) String stylerId,
                                                                 HttpServletRequest request){
        String adminId = CurrentAccount.adminId(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(identificationImageService.signedIdentificationLink(stylerId, adminId));
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

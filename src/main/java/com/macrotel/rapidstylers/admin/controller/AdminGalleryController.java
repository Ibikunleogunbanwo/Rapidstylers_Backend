package com.macrotel.rapidstylers.admin.controller;

import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.styler.service.PortfolioModerationService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Admin gallery moderation: the queue, approve, reject with a note, and one photo's decision history. */
@RestController
@RequestMapping("/rapid_stylers")
public class AdminGalleryController {

    @Autowired
    PortfolioModerationService moderationService;

    /** status: PENDING (default), APPROVED, REJECTED or ALL. */
    @GetMapping("/admin/gallery_moderation")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> queue(@RequestParam(value = "status", required = false) String status,
                                              @RequestParam(value = "page", required = false) Integer page,
                                              @RequestParam(value = "size", required = false) Integer size,
                                              HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(moderationService.queue(status, page, size));
    }

    @GetMapping("/admin/gallery_image_history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> history(@RequestParam("portfolioId") Long portfolioId, HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(moderationService.history(portfolioId));
    }

    /** Body: {portfolioId, note?}. */
    @PostMapping("/admin/approve_gallery_image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> approve(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(moderationService.approve(adminId, id(body), text(body.get("note"))));
    }

    /** Body: {portfolioId, note}. The note is required and emailed to the stylist. */
    @PostMapping("/admin/reject_gallery_image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> reject(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(moderationService.reject(adminId, id(body), text(body.get("note"))));
    }

    private static Long id(Map<String, Object> body) {
        Object raw = body.get("portfolioId");
        if (raw == null) return null;
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}

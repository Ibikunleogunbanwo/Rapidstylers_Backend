package com.macrotel.rapidstylers.catalog.controller;

import com.macrotel.rapidstylers.catalog.service.SiteContentService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Website content: the published FAQ, homepage and SEO tags for visitors, and
 * the admin's draft, publish, history and restore. Keys are "faq", "homepage",
 * "seo:/path" and "email:template_name".
 */
@RestController
@RequestMapping("/rapid_stylers")
public class SiteContentController {

    @Autowired
    SiteContentService siteContentService;

    @GetMapping("/site_content")
    public ResponseEntity<BaseResponse> publicContent(@RequestParam String key) {
        return ApiResponses.respond(siteContentService.publicContent(key));
    }

    @GetMapping("/site_seo")
    public ResponseEntity<BaseResponse> publicSeo() {
        return ApiResponses.respond(siteContentService.publicSeo());
    }

    @GetMapping("/admin/site_content")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> list(@RequestParam(required = false) String kind, HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(siteContentService.list(kind));
    }

    @GetMapping("/admin/site_content_item")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> get(@RequestParam String key, HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(siteContentService.get(key));
    }

    /** Body: {key, content: {...}}. Saves the draft only. */
    @PostMapping("/admin/save_site_content")
    @PreAuthorize("hasRole('ADMIN')")
    @SuppressWarnings("unchecked")
    public ResponseEntity<BaseResponse> save(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        Object content = body.get("content");
        return ApiResponses.respond(siteContentService.saveDraft(adminId, text(body.get("key")),
                content instanceof Map ? (Map<String, Object>) content : null));
    }

    /** Body: {key}. */
    @PostMapping("/admin/publish_site_content")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> publish(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(siteContentService.publish(adminId, text(body.get("key"))));
    }

    /** Body: {key}. */
    @PostMapping("/admin/unpublish_site_content")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> unpublish(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(siteContentService.unpublish(adminId, text(body.get("key"))));
    }

    @GetMapping("/admin/site_content_history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> history(@RequestParam String key, HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(siteContentService.history(key));
    }

    /** Body: {key, version}. Loads that version into the draft. */
    @PostMapping("/admin/restore_site_content")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> restore(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        Integer version;
        try {
            version = body.get("version") == null ? null : Integer.valueOf(String.valueOf(body.get("version")).trim());
        } catch (NumberFormatException ex) {
            version = null;
        }
        return ApiResponses.respond(siteContentService.restore(adminId, text(body.get("key")), version));
    }

    @GetMapping("/admin/preview_email_template")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> preview(@RequestParam String key,
                                                @RequestParam(defaultValue = "false") boolean published,
                                                HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(siteContentService.previewEmail(key, published));
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw).trim();
    }
}

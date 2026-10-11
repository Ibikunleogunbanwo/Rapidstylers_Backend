package com.macrotel.rapidstylers.engagement.controller;

import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.engagement.service.NewsletterCampaignService;
import com.macrotel.rapidstylers.engagement.service.NewsletterSubscriptionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The newsletter: the website's subscribe form and the confirm and
 * unsubscribe links (no sign-in), the switch on a customer's or stylist's
 * account page, and the admin's subscribers, blocked addresses and campaigns.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class NewsletterController {

    @Autowired NewsletterSubscriptionService subscriptionService;
    @Autowired NewsletterCampaignService campaignService;

    // ── public ──────────────────────────────────────────────────────────────

    /** Body: {email, province?, consent: true}. */
    @PostMapping("/newsletter_subscribe")
    public ResponseEntity<BaseResponse> subscribe(@RequestBody Map<String, Object> body) {
        return ApiResponses.respond(subscriptionService.subscribe(text(body.get("email")), text(body.get("province")),
                Boolean.parseBoolean(String.valueOf(body.get("consent")))));
    }

    /** Body: {token}. */
    @PostMapping("/newsletter_confirm")
    public ResponseEntity<BaseResponse> confirm(@RequestBody Map<String, Object> body) {
        return ApiResponses.respond(subscriptionService.confirm(text(body.get("token"))));
    }

    /** Body: {token}. */
    @PostMapping("/newsletter_unsubscribe")
    public ResponseEntity<BaseResponse> unsubscribe(@RequestBody Map<String, Object> body) {
        return ApiResponses.respond(subscriptionService.unsubscribe(text(body.get("token"))));
    }

    @GetMapping("/newsletter_preferences")
    public ResponseEntity<BaseResponse> preferences(@RequestParam String token) {
        return ApiResponses.respond(subscriptionService.preferences(token));
    }

    // ── signed-in customer or stylist ───────────────────────────────────────

    @GetMapping("/my_newsletter")
    public ResponseEntity<BaseResponse> mine(HttpServletRequest request) {
        String accountId = CurrentAccount.id(request);
        if (accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(subscriptionService.mine(accountId, CurrentAccount.role(request)));
    }

    /** Body: {subscribed: true|false}. */
    @PostMapping("/update_my_newsletter")
    public ResponseEntity<BaseResponse> updateMine(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String accountId = CurrentAccount.id(request);
        if (accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(subscriptionService.updateMine(accountId, CurrentAccount.role(request),
                Boolean.parseBoolean(String.valueOf(body.get("subscribed")))));
    }

    // ── admin: subscribers and blocked addresses ────────────────────────────

    @GetMapping("/admin/newsletter_subscribers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> subscribers(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String search,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size,
                                                    HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(subscriptionService.subscribers(status, search, page, size));
    }

    @GetMapping("/admin/email_suppressions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> suppressions(HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(subscriptionService.suppressions());
    }

    /** Body: {email, note?}. */
    @PostMapping("/admin/add_email_suppression")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> addSuppression(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(subscriptionService.addSuppression(adminId, text(body.get("email")), text(body.get("note"))));
    }

    /** Body: {email}. */
    @PostMapping("/admin/remove_email_suppression")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> removeSuppression(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(subscriptionService.removeSuppression(adminId, text(body.get("email"))));
    }

    // ── admin: campaigns ────────────────────────────────────────────────────

    @GetMapping("/admin/newsletter_campaigns")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> campaigns(HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(campaignService.list());
    }

    @GetMapping("/admin/newsletter_campaign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> campaign(@RequestParam Long id, HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(campaignService.get(id));
    }

    /** Body: {id?, name, subject, body, audienceRole: ALL|CUSTOMER|STYLER|GUEST, provinces: [..]}. */
    @PostMapping("/admin/save_newsletter_campaign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> save(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(campaignService.save(adminId, id(body.get("id")), text(body.get("name")),
                text(body.get("subject")), text(body.get("body")), text(body.get("audienceRole")), list(body.get("provinces"))));
    }

    /** Body: {audienceRole, provinces}. Answers {count}. */
    @PostMapping("/admin/newsletter_audience")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> audience(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(campaignService.audience(text(body.get("audienceRole")), list(body.get("provinces"))));
    }

    /** Body: {id, sendAt?}. No sendAt means as soon as possible. */
    @PostMapping("/admin/schedule_newsletter_campaign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> schedule(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(campaignService.schedule(adminId, id(body.get("id")), text(body.get("sendAt"))));
    }

    /** Body: {id}. */
    @PostMapping("/admin/cancel_newsletter_campaign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> cancel(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(campaignService.cancel(adminId, id(body.get("id"))));
    }

    /** Body: {id, email}. */
    @PostMapping("/admin/send_newsletter_test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> sendTest(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(campaignService.sendTest(adminId, id(body.get("id")), text(body.get("email"))));
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static Long id(Object raw) {
        try {
            return raw == null || String.valueOf(raw).isBlank() ? null : Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<String> list(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List) for (Object o : (List<?>) raw) if (o != null) out.add(String.valueOf(o));
        return out;
    }
}

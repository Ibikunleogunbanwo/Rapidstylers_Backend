package com.macrotel.rapidstylers.admin.controller;

import com.macrotel.rapidstylers.admin.service.AdminFunnelService;
import com.macrotel.rapidstylers.common.service.FunnelService;
import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import java.util.Map;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * The sign-up funnel: the site's visit beacon (public) and the admin report.
 * The other steps are counted by the sign-up and booking code itself.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class SignupFunnelController {

    /** A browser id the site makes up (for example a UUID); nothing personal. */
    private static final Pattern VISITOR_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final int VISITS_PER_IP_PER_HOUR = 120;

    @Autowired FunnelService funnelService;
    @Autowired AdminFunnelService adminFunnelService;
    @Autowired RateLimiterService rateLimiterService;

    /** Body: {visitorId}. Always answers 200 for a well-formed id, so it never disturbs the page. */
    @PostMapping("/funnel_visit")
    public ResponseEntity<BaseResponse> visit(@RequestBody Map<String, Object> body) {
        BaseResponse response = new BaseResponse(true);
        Object raw = body == null ? null : body.get("visitorId");
        String visitorId = raw == null ? null : String.valueOf(raw).trim();
        if (visitorId == null || !VISITOR_ID.matcher(visitorId).matches()) {
            response.setMessage("visitorId must be 8 to 64 letters, digits or dashes");
            return ApiResponses.respond(response);
        }
        String key = "funnel_visit:" + rateLimiterService.clientIp();
        if (!rateLimiterService.isBlocked(key, 3600, VISITS_PER_IP_PER_HOUR)) {
            rateLimiterService.record(key, 3600);
            funnelService.record(FunnelService.VISITED, null, visitorId);
        }
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(EMPTY_DATA);
        return ApiResponses.respond(response);
    }

    /** from/to (yyyy-MM-dd), groupBy day|week|month, role CUSTOMER|STYLIST|ALL; all optional. */
    @GetMapping("/admin/signup_funnel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> report(@RequestParam(value = "from", required = false) String from,
                                               @RequestParam(value = "to", required = false) String to,
                                               @RequestParam(value = "groupBy", required = false) String groupBy,
                                               @RequestParam(value = "role", required = false) String role,
                                               HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminFunnelService.report(from, to, groupBy, role));
    }
}

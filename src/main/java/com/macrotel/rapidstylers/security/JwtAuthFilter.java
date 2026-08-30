package com.macrotel.rapidstylers.security;

import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Role-based JWT enforcement. Paths in ROLE_PATHS require a valid
 * "Authorization: Bearer <jwt>" whose role claim matches the required role:
 *   - ADMIN   : create/update/delete service (categories)
 *   - STYLER  : styler-owned mutations (sub-services, portfolio, sign-out)
 *   - CUSTOMER: account-owned mutations (bookings, profile, card, feedback)
 *
 * Public/catalog endpoints are NOT in the map — they remain gated only by the
 * shared x-api-key (see AppConfig) so the landing page stays anonymous.
 */
@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Map<String, Set<String>> ROLE_PATHS = new HashMap<>();

    static {
        allow("/rapid_stylers/create_service", "ADMIN");
        allow("/rapid_stylers/update_service", "ADMIN");
        allow("/rapid_stylers/delete_service", "ADMIN");

        allow("/rapid_stylers/create_blog", "ADMIN");
        allow("/rapid_stylers/update_blog", "ADMIN");
        allow("/rapid_stylers/delete_blog", "ADMIN");
        allow("/rapid_stylers/create_identification", "ADMIN");
        allow("/rapid_stylers/update_identification", "ADMIN");
        allow("/rapid_stylers/delete_identification", "ADMIN");

        // Professional verification workflow
        allow("/rapid_stylers/admin/styler_verification_queue", "ADMIN");
        allow("/rapid_stylers/admin/update_styler_verification", "ADMIN");
        allow("/rapid_stylers/admin/styler_business_summaries", "ADMIN");
        allow("/rapid_stylers/admin/all_portfolios", "ADMIN");
        allow("/rapid_stylers/admin/delete_portfolio_image", "ADMIN");
        allow("/rapid_stylers/delete_cloudinary_image", "ADMIN", "STYLER");

        allow("/rapid_stylers/create_sub_service", "STYLER");
        allow("/rapid_stylers/styler_own_sub_services", "STYLER");
        allow("/rapid_stylers/create_portfolio", "STYLER");
        allow("/rapid_stylers/styler_own_portfolio", "STYLER");
        allow("/rapid_stylers/delete_portfolio_image", "STYLER");
        allow("/rapid_stylers/styler_sign_out", "STYLER");
        allow("/rapid_stylers/styler/connect_account", "STYLER");
        allow("/rapid_stylers/styler/connect_status", "STYLER");
        allow("/rapid_stylers/styler/payouts", "STYLER");
        allow("/rapid_stylers/styler/business_summary", "STYLER");

        allow("/rapid_stylers/book_appointment", "CUSTOMER");
        allow("/rapid_stylers/cancel_appointment", "CUSTOMER");
        allow("/rapid_stylers/retry_appointment_payment", "CUSTOMER");
        allow("/rapid_stylers/decrypt", "CUSTOMER", "STYLER", "ADMIN");

        allow("/rapid_stylers/styler_appointments", "STYLER");
        allow("/rapid_stylers/styler_availability", "STYLER");
        allow("/rapid_stylers/update_styler_availability", "STYLER");
        allow("/rapid_stylers/styler_availability_exceptions", "STYLER");
        allow("/rapid_stylers/styler/travel_settings", "STYLER");
        allow("/rapid_stylers/update_styler_travel_settings", "STYLER");
        allow("/rapid_stylers/add_availability_exception", "STYLER");
        allow("/rapid_stylers/delete_availability_exception", "STYLER");
        allow("/rapid_stylers/accept_appointment", "STYLER");
        allow("/rapid_stylers/decline_appointment", "STYLER");
        allow("/rapid_stylers/complete_appointment", "STYLER");
        allow("/rapid_stylers/styler_cancel_appointment", "STYLER");

        allow("/rapid_stylers/update_user_data", "CUSTOMER");
        allow("/rapid_stylers/update_user_password", "CUSTOMER");
        allow("/rapid_stylers/update_card_details", "CUSTOMER");
        allow("/rapid_stylers/add_feedback", "CUSTOMER");

        // Account-owned reads and review creation — identity comes from the token, not the query/body.
        allow("/rapid_stylers/user_data", "CUSTOMER");
        allow("/rapid_stylers/user_appointments", "CUSTOMER");
        allow("/rapid_stylers/user_pending_appointments", "CUSTOMER");
        allow("/rapid_stylers/saved_stylists", "CUSTOMER");
        allow("/rapid_stylers/save_stylist", "CUSTOMER");
        allow("/rapid_stylers/remove_saved_stylist", "CUSTOMER");
        allow("/rapid_stylers/notifications", "CUSTOMER");
        allow("/rapid_stylers/notifications/read", "CUSTOMER");
        allow("/rapid_stylers/notifications/read_all", "CUSTOMER");
        allow("/rapid_stylers/notification_preferences", "CUSTOMER");
        allow("/rapid_stylers/create_review", "CUSTOMER");
        allow("/rapid_stylers/support_tickets", "CUSTOMER");
        allow("/rapid_stylers/loyalty_account", "CUSTOMER");
        allow("/rapid_stylers/apply_referral", "CUSTOMER");
        allow("/rapid_stylers/update_sub_service", "STYLER");
        allow("/rapid_stylers/admin/review_moderation_queue", "ADMIN");
        allow("/rapid_stylers/admin/update_review_moderation", "ADMIN");
        allow("/rapid_stylers/admin/support_tickets", "ADMIN");
        allow("/rapid_stylers/admin/update_support_ticket", "ADMIN");
        allow("/rapid_stylers/admin/kpis", "ADMIN");
        allow("/rapid_stylers/admin/settings/commission", "ADMIN");
        allow("/rapid_stylers/admin/audit_logs", "ADMIN");
        allow("/rapid_stylers/admin/styler_connect_statuses", "ADMIN");
        allow("/rapid_stylers/admin/test_email", "ADMIN");
        allow("/rapid_stylers/list_feedback", "ADMIN");
        allow("/rapid_stylers/admin/failed_events", "ADMIN");
        allow("/rapid_stylers/admin/failed_events/{id}/retry", "ADMIN");
        allow("/rapid_stylers/admin/refund", "ADMIN");
        allow("/rapid_stylers/admin/refunds", "ADMIN");
        allow("/rapid_stylers/admin/payment_reconciliation", "ADMIN");
    }

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        Set<String> requiredRoles = ROLE_PATHS.get(path);
        if (requiredRoles == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        String token = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7)
                : null;
        Claims claims = (token == null) ? null : jwtUtil.parseToken(token);
        String role = (claims == null) ? null : claims.get("role", String.class);

        if (role == null || !requiredRoles.contains(role)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"status\": false, \"error\": \"Authorized role required\"}");
            return;
        }

        request.setAttribute("accountId", claims.getSubject());
        request.setAttribute("role", role);
        filterChain.doFilter(request, response);
    }

    private static void allow(String path, String... roles) {
        ROLE_PATHS.put(path, Set.of(roles));
    }
}

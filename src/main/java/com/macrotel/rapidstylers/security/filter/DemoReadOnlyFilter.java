package com.macrotel.rapidstylers.security.filter;

import com.macrotel.rapidstylers.security.access.Routes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Turns the API into a showroom when app.demo.read-only=true (the demo copy of
 * the app that serves the showcase database). Visitors can browse everything,
 * but nothing that would create an account, a booking or a payment gets through:
 * the demo database is for adverts, and an account made there would be lost.
 *
 * <p>Allowed: every GET except the two that write, the browsing routes ({@link Routes#BROWSE}), admin
 * sign-in and admin work (so the showcase can still be tidied), token refresh and
 * sign-out. Everything else answers 403 with a pointer to the live site. Off by
 * default, so the live app is never affected.
 */
@Component
public class DemoReadOnlyFilter extends OncePerRequestFilter {

    private static final List<String> ALWAYS_ALLOWED = List.of(
            "/actuator/**",
            "/rapid_stylers/admin_sign_in",
            "/rapid_stylers/auth/refresh",
            "/rapid_stylers/auth/logout");

    /** GET routes that still change something: a Stripe card setup, an upload signature. */
    private static final List<String> WRITING_GETS = List.of(
            "/rapid_stylers/card_setup_intent",
            "/rapid_stylers/get_upload_signature");

    private final AntPathMatcher matcher = new AntPathMatcher();

    @Value("${app.demo.read-only:false}")
    boolean readOnly;

    @Value("${app.demo.live-url:https://rapidstylers.ca}")
    String liveUrl;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !readOnly;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isAllowed(request.getMethod(), request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"statusCode\":\"403\",\"message\":\"This is the RapidStylers demo. "
                + "To sign up or book, visit " + liveUrl.replace("\"", "") + "\",\"data\":[]}");
    }

    boolean isAllowed(String method, String path) {
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return !matchesAny(WRITING_GETS, path);
        }
        return matchesAny(ALWAYS_ALLOWED, path)
                || matchesAny(List.of(Routes.BROWSE), path)
                || matchesAny(List.of(Routes.ADMIN), path);
    }

    private boolean matchesAny(List<String> patterns, String path) {
        for (String pattern : patterns) {
            if (matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}

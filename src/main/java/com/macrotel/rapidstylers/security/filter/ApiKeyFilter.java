package com.macrotel.rapidstylers.security.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Shared x-api-key gate for every endpoint except the Stripe webhook and the
 * health probe. It only checks the shared key; who the
 * caller is and which roles may use a route are handled by the security chain
 * (JwtAuthFilter, SecurityConfig, Routes), which runs before this filter.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    @Value("${app.api.key}")
    private String apiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String requestApiKey = request.getHeader("x-api-key");
        String requestUrl = request.getRequestURI();

        // The Stripe webhook (signed, not key-gated) bypasses the shared key.
        // The actuator health endpoint is exempt so Docker/Nginx health checks work
        // without an API key; it reports only UP/DOWN status.
        if ("/rapid_stylers/stripe/webhook".equals(requestUrl)
                || "/rapid_stylers/resend/webhook".equals(requestUrl)
                || "/rapid_stylers/newsletter_one_click_unsubscribe".equals(requestUrl)
                || "/actuator/health".equals(requestUrl)) {
            filterChain.doFilter(request, response);
        }
        else{
            if (requestApiKey == null || !requestApiKey.equals(apiKey)) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType("application/json");
                response.getWriter().write("{\"status\": false, \"error\": \"Invalid API key\"}");
                return;

            }
            filterChain.doFilter(request, response);
        }
    }
}

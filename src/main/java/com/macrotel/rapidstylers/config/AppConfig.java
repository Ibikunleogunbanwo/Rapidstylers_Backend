package com.macrotel.rapidstylers.config;

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
 * Shared x-api-key gate for every endpoint except public file serving.
 * Role-based access (ADMIN / STYLER / CUSTOMER) is enforced separately by
 * JwtAuthFilter — it runs first and rejects role-protected paths without a
 * valid JWT, so this filter only needs to handle the shared key.
 */
@Component
public class AppConfig extends OncePerRequestFilter {
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

        // Public file serving and the Stripe webhook (signed, not key-gated) bypass the shared key.
        if ((requestApiKey == null && requestUrl.startsWith("/rapid_stylers/files/"))
                || "/rapid_stylers/stripe/webhook".equals(requestUrl)) {
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

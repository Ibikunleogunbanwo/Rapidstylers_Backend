package com.macrotel.rapidstylers.security.filter;

import com.macrotel.rapidstylers.security.access.Roles;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.auth.service.SessionActivityService;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Works out who is calling. It never rejects a request: when the
 * "Authorization: Bearer <jwt>" header holds a valid token it signs the request
 * in (Spring's SecurityContext, plus the accountId and role request attributes
 * the controllers read). Whether that caller may use the route is decided
 * afterwards by the rules in SecurityConfig and Routes.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    // Silent token renewal must not count as activity, or the idle timeout
    // that /auth/refresh enforces could never trigger.
    private static final String REFRESH_PATH = "/rapid_stylers/auth/refresh";

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private SessionActivityService sessionActivityService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Claims claims = bearerClaims(request);
        String role = (claims == null) ? null : claims.get("role", String.class);
        if (role != null) {
            String subject = claims.getSubject();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    subject, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            // The sign-up upload pass names an email, not an account: it gets no
            // accountId for controllers to read and no session to keep alive.
            if (!Roles.SIGNUP_UPLOAD.equals(role)) {
                request.setAttribute("accountId", subject);
                request.setAttribute("role", role);
                if (!REFRESH_PATH.equals(request.getRequestURI())) {
                    // Best-effort Redis write; never fails the request.
                    sessionActivityService.touch(subject, role);
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Returns the claims of a valid bearer token, or null when there is none or it is invalid. */
    private Claims bearerClaims(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return jwtUtil.parseToken(header.substring(7));
    }
}

package com.macrotel.rapidstylers.security.filter;

import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.auth.service.SessionActivityService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.ServletException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The filter only identifies the caller; it never rejects. Which roles may use
 * which route is covered by SecurityRulesTest over the whole chain.
 */
class JwtAuthFilterTest {

    private JwtUtil jwtUtil;
    private SessionActivityService sessionActivityService;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        sessionActivityService = mock(SessionActivityService.class);
        filter = new JwtAuthFilter();
        ReflectionTestUtils.setField(filter, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(filter, "sessionActivityService", sessionActivityService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenSignsTheRequestIn() throws ServletException, IOException {
        tokenFor("styler-token", "STYLER1", "STYLER");
        MockHttpServletRequest request = request("/rapid_stylers/styler/reviews", "styler-token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "the request continues down the chain");
        assertEquals("STYLER1", request.getAttribute("accountId"));
        assertEquals("STYLER", request.getAttribute("role"));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("STYLER1", auth.getPrincipal());
        assertEquals("ROLE_STYLER", auth.getAuthorities().iterator().next().getAuthority());
        verify(sessionActivityService).touch("STYLER1", "STYLER");
    }

    @Test
    void missingTokenPassesThroughAnonymously() throws ServletException, IOException {
        MockHttpServletRequest request = request("/rapid_stylers/user_data", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "the filter never rejects; the security rules do");
        assertEquals(200, response.getStatus());
        assertNull(request.getAttribute("accountId"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void invalidTokenPassesThroughAnonymously() throws ServletException, IOException {
        when(jwtUtil.parseToken("forged")).thenReturn(null);
        MockHttpServletRequest request = request("/rapid_stylers/user_data", "forged");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(request.getAttribute("accountId"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(sessionActivityService, never()).touch(any(), any());
    }

    @Test
    void tokenRefreshDoesNotCountAsActivity() throws ServletException, IOException {
        tokenFor("customer-token", "CUST1", "CUSTOMER");

        filter.doFilter(request("/rapid_stylers/auth/refresh", "customer-token"),
                new MockHttpServletResponse(), new MockFilterChain());

        verify(sessionActivityService, never()).touch(any(), any());
    }

    @Test
    void uploadPassIsNotAnAccount() throws ServletException, IOException {
        tokenFor("upload-pass", "new.stylist@example.com", "SIGNUP_UPLOAD");
        MockHttpServletRequest request = request("/rapid_stylers/get_upload_signature", "upload-pass");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals("ROLE_SIGNUP_UPLOAD",
                SecurityContextHolder.getContext().getAuthentication().getAuthorities().iterator().next().getAuthority());
        assertNull(request.getAttribute("accountId"), "controllers must not mistake the email for an account id");
        verify(sessionActivityService, never()).touch(any(), any());
    }

    private void tokenFor(String token, String accountId, String role) {
        Claims claims = Jwts.claims().setSubject(accountId);
        claims.put("role", role);
        when(jwtUtil.parseToken(token)).thenReturn(claims);
    }

    private static MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        return request;
    }
}

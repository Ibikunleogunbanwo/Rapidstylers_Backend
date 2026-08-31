package com.macrotel.rapidstylers.security;

import com.macrotel.rapidstylers.service.SessionActivityService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.ServletException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    private JwtUtil jwtUtil;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        filter = new JwtAuthFilter();
        ReflectionTestUtils.setField(filter, "jwtUtil", jwtUtil);
        // The filter calls this on valid-token requests; a no-op mock keeps the
        // existing role/route assertions the focus of the test.
        ReflectionTestUtils.setField(filter, "sessionActivityService", mock(SessionActivityService.class));
    }

    @Test
    void rejectsCloudinaryDeleteWithoutJwt() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rapid_stylers/delete_cloudinary_image");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void allowsCloudinaryDeleteForAdminJwt() throws ServletException, IOException {
        Claims claims = Jwts.claims().setSubject("ADMIN1");
        claims.put("role", "ADMIN");
        when(jwtUtil.parseToken("admin-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rapid_stylers/delete_cloudinary_image");
        request.addHeader("Authorization", "Bearer admin-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals("ADMIN1", request.getAttribute("accountId"));
    }

    @Test
    void protectsIdentificationMaintenanceForAdminsOnly() throws ServletException, IOException {
        Claims claims = Jwts.claims().setSubject("STYLER1");
        claims.put("role", "STYLER");
        when(jwtUtil.parseToken("styler-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rapid_stylers/create_identification");
        request.addHeader("Authorization", "Bearer styler-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void recoveryCampaignsListedForAdminRole() throws ServletException, IOException {
        // /admin/recovery_campaigns has @PreAuthorize("hasRole('ADMIN')") — but
        // the method-level check only sees the role when the filter lists the
        // path and populates the SecurityContext. Without the path entry the
        // endpoint could never succeed; this locks the entry in place.
        Claims claims = Jwts.claims().setSubject("ADMIN1");
        claims.put("role", "ADMIN");
        when(jwtUtil.parseToken("admin-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rapid_stylers/admin/recovery_campaigns");
        request.addHeader("Authorization", "Bearer admin-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals("ADMIN1", request.getAttribute("accountId"));
    }

    @Test
    void recoveryCampaignsRejectsStylerRole() throws ServletException, IOException {
        Claims claims = Jwts.claims().setSubject("STYLER1");
        claims.put("role", "STYLER");
        when(jwtUtil.parseToken("styler-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rapid_stylers/admin/recovery_campaigns");
        request.addHeader("Authorization", "Bearer styler-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void adminPasswordChangeAllowedForAdminRole() throws ServletException, IOException {
        // POST /admin/accounts/{id}/password is @PreAuthorize("hasRole('ADMIN')"),
        // so the filter must list the path and populate the SecurityContext.
        Claims claims = Jwts.claims().setSubject("ADMIN1");
        claims.put("role", "ADMIN");
        when(jwtUtil.parseToken("admin-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rapid_stylers/admin/accounts/5/password");
        request.addHeader("Authorization", "Bearer admin-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals("ADMIN1", request.getAttribute("accountId"));
    }

    @Test
    void adminPasswordChangeRejectsStylerRole() throws ServletException, IOException {
        Claims claims = Jwts.claims().setSubject("STYLER1");
        claims.put("role", "STYLER");
        when(jwtUtil.parseToken("styler-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rapid_stylers/admin/accounts/5/password");
        request.addHeader("Authorization", "Bearer styler-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void cardSetupIntentRequiresCustomerRole() throws ServletException, IOException {
        // /card_setup_intent reads the account from the filter-set attribute;
        // an unlisted path would always see null and return 401 even for a
        // valid customer. The CUSTOMER entry makes it reachable.
        Claims claims = Jwts.claims().setSubject("CUST1");
        claims.put("role", "CUSTOMER");
        when(jwtUtil.parseToken("customer-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rapid_stylers/card_setup_intent");
        request.addHeader("Authorization", "Bearer customer-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals("CUST1", request.getAttribute("accountId"));
    }

    @Test
    void cardSetupIntentRejectsAnonymous() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rapid_stylers/card_setup_intent");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void cacheStatsListedForAdminRole() throws ServletException, IOException {
        // Mirrors the recovery_campaigns guard: the endpoint is @PreAuthorize(ADMIN)
        // and only succeeds when the filter lists the path and populates the context.
        Claims claims = Jwts.claims().setSubject("ADMIN1");
        claims.put("role", "ADMIN");
        when(jwtUtil.parseToken("admin-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rapid_stylers/admin/cache_stats");
        request.addHeader("Authorization", "Bearer admin-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals("ADMIN1", request.getAttribute("accountId"));
    }

    @Test
    void cacheStatsRejectsStylerRole() throws ServletException, IOException {
        Claims claims = Jwts.claims().setSubject("STYLER1");
        claims.put("role", "STYLER");
        when(jwtUtil.parseToken("styler-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rapid_stylers/admin/cache_stats");
        request.addHeader("Authorization", "Bearer styler-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }
}

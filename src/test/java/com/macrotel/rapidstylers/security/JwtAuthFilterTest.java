package com.macrotel.rapidstylers.security;

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
}

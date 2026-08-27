package com.macrotel.rapidstylers.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import javax.servlet.ServletException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SecurityConfigTest {

    @Test
    void apiKeyFilterAllowsCorsOptionsPreflightWithoutApiKey() throws ServletException, IOException {
        AppConfig appConfig = new AppConfig();
        ReflectionTestUtils.setField(appConfig, "apiKey", "test-key");

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/rapid_stylers/list_service");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        appConfig.doFilter(request, response, filterChain);

        assertEquals(200, response.getStatus());
    }

    @Test
    void corsConfigurationUsesConfiguredOriginsWithoutWildcard() throws ServletException, IOException {
        CorsConfig corsConfig = new CorsConfig();
        ReflectionTestUtils.setField(corsConfig, "allowedOrigins", "https://rapidstylers.ca,https://www.rapidstylers.ca");
        CorsFilter filter = corsConfig.corsFilter();

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/rapid_stylers/list_service");
        request.addHeader("Origin", "https://rapidstylers.ca");
        request.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("https://rapidstylers.ca", response.getHeader("Access-Control-Allow-Origin"));
        assertFalse(response.containsHeader("Access-Control-Allow-Credentials"));
    }
}

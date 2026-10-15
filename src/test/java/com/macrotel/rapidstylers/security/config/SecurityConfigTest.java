package com.macrotel.rapidstylers.security.config;

import com.macrotel.rapidstylers.security.filter.ApiKeyFilter;
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
import static org.junit.jupiter.api.Assertions.assertNull;

class SecurityConfigTest {

    @Test
    void apiKeyFilterAllowsCorsOptionsPreflightWithoutApiKey() throws ServletException, IOException {
        ApiKeyFilter appConfig = new ApiKeyFilter();
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
        CorsFilter filter = new CorsFilter(corsConfig.corsConfigurationSource());

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/rapid_stylers/list_service");
        request.addHeader("Origin", "https://rapidstylers.ca");
        request.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("https://rapidstylers.ca", response.getHeader("Access-Control-Allow-Origin"));
        assertFalse(response.containsHeader("Access-Control-Allow-Credentials"));
    }

    @Test
    void demoSiteMayPostOnlyTheNewsletterFormToTheLiveApi() throws ServletException, IOException {
        CorsConfig corsConfig = new CorsConfig();
        ReflectionTestUtils.setField(corsConfig, "allowedOrigins", "https://rapidstylers.ca,https://www.rapidstylers.ca");
        ReflectionTestUtils.setField(corsConfig, "newsletterOrigins", "https://demo.rapidstylers.ca");
        CorsFilter filter = new CorsFilter(corsConfig.corsConfigurationSource());

        MockHttpServletResponse newsletter = preflight(filter, "/rapid_stylers/newsletter_subscribe", "https://demo.rapidstylers.ca");
        assertEquals("https://demo.rapidstylers.ca", newsletter.getHeader("Access-Control-Allow-Origin"));

        MockHttpServletResponse signUp = preflight(filter, "/rapid_stylers/user_sign_up", "https://demo.rapidstylers.ca");
        assertNull(signUp.getHeader("Access-Control-Allow-Origin"));

        MockHttpServletResponse live = preflight(filter, "/rapid_stylers/newsletter_subscribe", "https://www.rapidstylers.ca");
        assertEquals("https://www.rapidstylers.ca", live.getHeader("Access-Control-Allow-Origin"));
    }

    private static MockHttpServletResponse preflight(CorsFilter filter, String path, String origin) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "content-type,x-api-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}

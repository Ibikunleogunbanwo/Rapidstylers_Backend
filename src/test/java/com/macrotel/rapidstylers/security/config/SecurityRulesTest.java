package com.macrotel.rapidstylers.security.config;

import com.macrotel.rapidstylers.security.handler.JsonAuthErrorHandler;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.auth.service.SessionActivityService;
import com.macrotel.rapidstylers.security.jwt.AccessTokenRevocations;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs requests through the real security chain (CORS, JwtAuthFilter, the Routes
 * rules, JsonAuthErrorHandler, ApiKeyFilter) in front of a stand-in controller
 * that answers every path with the account the chain resolved. No database needed.
 */
@WebMvcTest(controllers = SecurityRulesTest.EchoController.class,
        properties = "app.api.key=test-key")
@Import({SecurityConfig.class, CorsConfig.class, JsonAuthErrorHandler.class, SecurityRulesTest.EchoController.class})
class SecurityRulesTest {

    @RestController
    static class EchoController {
        @RequestMapping("/**")
        String echo(HttpServletRequest request) {
            return "account=" + request.getAttribute("accountId");
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockBean
    JwtUtil jwtUtil;

    @MockBean
    SessionActivityService sessionActivityService;

    @MockBean
    AccessTokenRevocations accessTokenRevocations;

    @BeforeEach
    void tokens() {
        for (String role : new String[] {"CUSTOMER", "STYLER", "ADMIN", "SIGNUP_UPLOAD"}) {
            Claims claims = Jwts.claims().setSubject(role + "1");
            claims.put("role", role);
            when(jwtUtil.parseToken(role + "-token")).thenReturn(claims);
        }
    }

    @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
    @CsvSource({
            // Public routes need no token
            "GET,    /rapid_stylers/list_service,                     ,         200",
            "POST,   /rapid_stylers/sign_in,                          ,         200",
            "POST,   /rapid_stylers/stripe/webhook,                   ,         200",
            // The old unchallenged customer sign-in is gone (falls to closed by default)
            "POST,   /rapid_stylers/user_sign_in,                     ,         401",
            // Role routes
            "POST,   /rapid_stylers/delete_cloudinary_image,          ,         401",
            "POST,   /rapid_stylers/delete_cloudinary_image,          ADMIN,    200",
            "POST,   /rapid_stylers/delete_cloudinary_image,          STYLER,   200",
            "POST,   /rapid_stylers/delete_cloudinary_image,          CUSTOMER, 401",
            "POST,   /rapid_stylers/create_identification,            STYLER,   401",
            "GET,    /rapid_stylers/admin/recovery_campaigns,         ADMIN,    200",
            "GET,    /rapid_stylers/admin/recovery_campaigns,         STYLER,   401",
            "POST,   /rapid_stylers/admin/accounts/5/password,        ADMIN,    200",
            "POST,   /rapid_stylers/admin/accounts/5/password,        STYLER,   401",
            "POST,   /rapid_stylers/admin/failed_events/9/retry,      ADMIN,    200",
            "POST,   /rapid_stylers/admin/failed_events/9/retry,      CUSTOMER, 401",
            "GET,    /rapid_stylers/admin/cache_stats,                STYLER,   401",
            "GET,    /rapid_stylers/card_setup_intent,                CUSTOMER, 200",
            "GET,    /rapid_stylers/card_setup_intent,                ,         401",
            "POST,   /rapid_stylers/decrypt,                          ADMIN,    200",
            "POST,   /rapid_stylers/decrypt,                          CUSTOMER, 401",
            "POST,   /rapid_stylers/decrypt,                          STYLER,   401",
            "POST,   /rapid_stylers/decrypt,                          ,         401",
            "GET,    /rapid_stylers/styler/reviews,                   STYLER,   200",
            "GET,    /rapid_stylers/styler/reviews,                   CUSTOMER, 401",
            // Support tickets: customers and stylists, not admins (they use the admin list)
            "POST,   /rapid_stylers/support_tickets,                  CUSTOMER, 200",
            "POST,   /rapid_stylers/support_tickets,                  STYLER,   200",
            "GET,    /rapid_stylers/support_tickets,                  STYLER,   200",
            "GET,    /rapid_stylers/support_tickets,                  ADMIN,    401",
            "GET,    /rapid_stylers/support_tickets,                  ,         401",
            // New admin screens are admin only
            "GET,    /rapid_stylers/admin/identification_image,       ADMIN,    200",
            "GET,    /rapid_stylers/admin/identification_image,       STYLER,   401",
            "GET,    /rapid_stylers/admin/identification_image,       ,         401",
            "GET,    /rapid_stylers/admin/reviews,                    CUSTOMER, 401",
            "GET,    /rapid_stylers/admin/payment_reconciliation_latest, STYLER, 401",
            "GET,    /rapid_stylers/admin/announcements,              CUSTOMER, 401",
            "GET,    /rapid_stylers/admin/announcement_audience_count, ADMIN,   200",
            // Closed by default: unlisted routes need a signed-in account of any role
            "POST,   /rapid_stylers/auth/logout,                      CUSTOMER, 200",
            "POST,   /rapid_stylers/auth/logout,                      STYLER,   200",
            "POST,   /rapid_stylers/auth/logout,                      ADMIN,    200",
            "POST,   /rapid_stylers/auth/logout,                      ,         401",
            "GET,    /rapid_stylers/some_new_route,                   ,         401",
            // The sign-up upload pass is not an account, so it opens nothing beyond public routes
            "POST,   /rapid_stylers/auth/logout,                      SIGNUP_UPLOAD, 401",
            "GET,    /rapid_stylers/user_data,                        SIGNUP_UPLOAD, 401",
            "POST,   /rapid_stylers/delete_cloudinary_image,          SIGNUP_UPLOAD, 200",
            "GET,    /rapid_stylers/get_upload_signature,             SIGNUP_UPLOAD, 200",
            // Upload signatures: stylists, admins and the sign-up pass, nobody else
            "GET,    /rapid_stylers/get_upload_signature,             ,         401",
            "GET,    /rapid_stylers/get_upload_signature,             CUSTOMER, 401",
            "GET,    /rapid_stylers/get_upload_signature,             STYLER,   200",
            "GET,    /rapid_stylers/get_upload_signature,             ADMIN,    200",
            // A trailing slash must not slip past a role rule
            "GET,    /rapid_stylers/user_data/,                       STYLER,   401",
            "GET,    /rapid_stylers/admin/kpis/,                      CUSTOMER, 401",
    })
    void routeAccess(String method, String path, String role, int expected) throws Exception {
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method), path).header("x-api-key", "test-key");
        if (role != null) {
            request.header("Authorization", "Bearer " + role + "-token");
        }
        mockMvc.perform(request).andExpect(status().is(expected));
    }

    @Test
    void allowedRequestCarriesTheAccountFromTheToken() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/rapid_stylers/styler/reviews")
                        .header("x-api-key", "test-key")
                        .header("Authorization", "Bearer STYLER-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("account=STYLER1"));
    }

    @Test
    void rejectionKeepsTheJsonBody() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/rapid_stylers/user_data").header("x-api-key", "test-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"status\": false, \"error\": \"Authorized role required\"}"));
    }

    @Test
    void missingApiKeyIsStillRejectedOnPublicRoutes() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/rapid_stylers/list_service"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflightNeedsNoTokenOrKey() throws Exception {
        mockMvc.perform(options("/rapid_stylers/user_data")
                        .header("Origin", "https://rapidstylers.ca")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://rapidstylers.ca"));
    }

    @Test
    void rejectedCrossOriginRequestStillCarriesCorsHeaders() throws Exception {
        // Without the header the browser hides the 401 from the frontend, which
        // then cannot tell "sign in again" apart from a network error.
        mockMvc.perform(request(HttpMethod.GET, "/rapid_stylers/user_data")
                        .header("Origin", "https://rapidstylers.ca")
                        .header("x-api-key", "test-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://rapidstylers.ca"));
    }

    @Test
    void unknownOriginIsRefused() throws Exception {
        mockMvc.perform(options("/rapid_stylers/list_service")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}

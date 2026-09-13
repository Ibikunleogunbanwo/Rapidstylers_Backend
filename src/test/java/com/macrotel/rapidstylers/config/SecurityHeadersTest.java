package com.macrotel.rapidstylers.config;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the security response headers over the full filter chain (AppConfig,
 * JwtAuthFilter, Spring Security's header writer): X-Content-Type-Options,
 * X-Frame-Options and the Content-Security-Policy declared in
 * SecurityFilterConfig. If a future refactor disables .headers() or drops the
 * policy string, this test turns red — the headers protect the Swagger UI /
 * any HTML this origin serves, and frame-ancestors 'none' blocks clickjacking.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersTest {

    @Autowired MockMvc mockMvc;

    @Test
    void securityHeadersPresentOnPublicProbe() throws Exception {
        // The probe is UP (200) or DOWN (503) depending on whether Redis is
        // reachable in the test env; either way the header writer runs.
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy",
                        Matchers.allOf(
                                Matchers.containsString("default-src 'none'"),
                                Matchers.containsString("object-src 'none'"),
                                Matchers.containsString("frame-ancestors 'none'"))))
                .andReturn();
        int probeStatus = result.getResponse().getStatus();
        assertTrue(probeStatus == 200 || probeStatus == 503,
                "health probe should be UP (200) or DOWN (503), was " + probeStatus);
    }

    @Test
    void headersAlsoAppliedToRejectedRequests() throws Exception {
        // A 401 (no x-api-key) must still carry the headers — a guard that only
        // decorates successful responses would miss the error paths browsers see.
        mockMvc.perform(get("/rapid_stylers/decrypt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Content-Security-Policy"));
    }
}

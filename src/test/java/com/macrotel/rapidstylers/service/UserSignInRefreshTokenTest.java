package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.RefreshTokenEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.repo.RefreshTokenRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Email/password customers must get the same session model as /sign_in and
 * Google sign-in: POST /user_sign_in returns a refresh token, persists it
 * (SHA-256 hash) as a live refresh_tokens row, and the token actually rotates
 * over /auth/refresh — anchoring the server-side idle/absolute session caps.
 *
 * The customer is inserted directly with a real BCrypt hash from the shared
 * PasswordEncoder so the email/password path is exercised end-to-end over the
 * real HTTP stack (no OTP registration ceremony needed).
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserSignInRefreshTokenTest {

    private static final String PASSWORD = "EmailPass1!";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean EmailConfig emailConfig;

    @Autowired UserRepo userRepo;
    @Autowired RefreshTokenRepo refreshTokenRepo;
    @Autowired PasswordEncoder passwordEncoder;

    @Value("${app.api.key}") String apiKey;

    private String email;
    private String userId;

    @BeforeEach
    void uniqueIdentity() {
        long ts = System.currentTimeMillis();
        email = "pwlogin.cust." + ts + "@rapidstylers.test";
        userId = "U" + ts;
    }

    @AfterEach
    void cleanup() {
        if (userId != null) {
            refreshTokenRepo.deleteByAccountIdAndRole(userId, "CUSTOMER");
            userRepo.findByEmailAddress(email).ifPresent(userRepo::delete);
        }
    }

    @Test
    void emailPasswordLoginReturnsRefreshTokenAndAnchorsTheSession() throws Exception {
        // Real BCrypt hash for a known password, exactly how /create_user_account stores it.
        UserEntity user = new UserEntity();
        user.setUserId(userId);
        user.setFirstname("Pat");
        user.setLastname("Password");
        user.setEmailAddress(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setStatus("0");
        userRepo.save(user);

        // ---- Email/password login over the real HTTP stack ----
        JsonNode body = body(apiPost("/user_sign_in",
                Map.of("emailAddress", email, "password", PASSWORD)));

        assertEquals("200", body.get("statusCode").asText(), "email/password login must succeed: " + body);
        assertFalse(body.get("token").asText().isBlank(), "must issue a JWT");
        String refreshToken = body.get("refreshToken").asText();
        assertFalse(refreshToken.isBlank(),
                "/user_sign_in must return a refreshToken, like /sign_in and Google sign-in do");

        // ---- The refresh token must be persisted as a live CUSTOMER row ----
        RefreshTokenEntity stored = refreshTokenRepo
                .findByTokenHashAndRevokedFalse(sha256Hex(refreshToken))
                .orElseThrow(() -> new AssertionError("no live refresh_tokens row for the login"));
        assertEquals(userId, stored.getAccountId());
        assertEquals("CUSTOMER", stored.getRole());

        // ---- ... and actually anchored: /auth/refresh rotates it into a fresh pair ----
        MvcResult refreshResult = mockMvc.perform(post("/rapid_stylers/auth/refresh")
                        .header("x-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn();
        JsonNode refreshed = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        assertEquals("200", refreshed.get("statusCode").asText(), "refresh must succeed: " + refreshed);
        String rotated = refreshed.get("refreshToken").asText();
        assertFalse(rotated.isBlank(), "refresh must return a rotated refresh token");
        assertNotEquals(refreshToken, rotated, "refresh token must rotate, never be reused");

        // Old token revoked, rotated one persisted; both under the same account.
        assertTrue(refreshTokenRepo.findByTokenHashAndRevokedFalse(sha256Hex(refreshToken)).isEmpty(),
                "old refresh token must be revoked after rotation");
        assertTrue(refreshTokenRepo.findByTokenHashAndRevokedFalse(sha256Hex(rotated)).isPresent(),
                "rotated refresh token must be persisted");

        // The refreshed access token must work on a protected endpoint.
        MvcResult userDataResult = mockMvc.perform(get("/rapid_stylers/user_data")
                        .header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + refreshed.get("token").asText()))
                .andReturn();
        assertEquals(200, userDataResult.getResponse().getStatus(),
                "refreshed access token must work on /user_data");
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult apiPost(String path, Object payload) throws Exception {
        MockHttpServletRequestBuilder request = post("/rapid_stylers" + path)
                .header("x-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON);
        if (payload != null) {
            request.content(objectMapper.writeValueAsString(payload));
        }
        return mockMvc.perform(request).andReturn();
    }
}

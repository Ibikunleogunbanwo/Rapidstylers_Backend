package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.outbox.OutboxEventRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventType;
import com.macrotel.rapidstylers.repo.NotificationRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.RefreshTokenRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import com.macrotel.rapidstylers.security.GoogleTokenVerifier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Google sign-in (customer-only) coverage for POST /google_sign_in:
 *
 *   - an invalid/forged id token is rejected (verifier throws)
 *   - an existing customer signs in (JWT + refresh token + CUSTOMER role)
 *   - a professional (styler) email is rejected with a clear message
 *   - a brand-new verified email auto-creates a minimal customer with the
 *     welcome notification and a CUSTOMER_WELCOME outbox event
 *
 * The real GoogleTokenVerifier is mocked because tests cannot mint a token
 * signed by Google's JWKS; AppService consumes only the verified claims, so
 * the same behavior is exercised without hitting Google.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GoogleSignInTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean GoogleTokenVerifier googleTokenVerifier;

    @Autowired UserRepo userRepo;
    @Autowired StylerRepo stylerRepo;
    @Autowired NotificationRepo notificationRepo;
    @Autowired RefreshTokenRepo refreshTokenRepo;
    @Autowired OutboxEventRepo outboxEventRepo;

    @Value("${app.api.key}") String apiKey;

    private String email;
    private String userId;
    private String stylerId;

    @BeforeEach
    void uniqueIdentity() {
        long ts = System.currentTimeMillis();
        email = "google.cust." + ts + "@rapidstylers.test";
    }

    @AfterEach
    void cleanup() {
        if (userId != null) {
            refreshTokenRepo.deleteByAccountIdAndRole(userId, "CUSTOMER");
            notificationRepo.findByUserIdOrderByCreatedAtDesc(userId).forEach(notificationRepo::delete);
            outboxEventRepo.findAll().stream()
                    .filter(e -> userId.equals(e.getAggregateId()))
                    .forEach(outboxEventRepo::delete);
            userRepo.findByEmailAddress(email).ifPresent(userRepo::delete);
        }
        if (stylerId != null) {
            stylerRepo.findByEmailAddress(email).ifPresent(stylerRepo::delete);
        }
    }

    private Claims verifiedClaims(String email) {
        Claims claims = Jwts.claims();
        claims.setSubject("google-sub-123");
        claims.put("email", email);
        claims.put("email_verified", true);
        claims.put("given_name", "Gina");
        claims.put("family_name", "Customer");
        claims.put("name", "Gina Customer");
        return claims;
    }

    @Test
    void invalidTokenIsRejectedAndCreatesNothing() throws Exception {
        when(googleTokenVerifier.verify(anyString()))
                .thenThrow(new GoogleTokenVerifier.IdTokenInvalidException("Invalid Google ID token signature"));

        JsonNode body = postGoogle("not-a-real-id-token");

        assertEquals("400", body.get("statusCode").asText(), "invalid token must fail");
        assertTrue(body.get("message").asText().toLowerCase().contains("invalid"),
                "unexpected message: " + body.get("message").asText());
        assertTrue(userRepo.findByEmailAddress(email).isEmpty(), "no account may be created for an invalid token");
    }

    @Test
    void existingCustomerSignsInWithTokenAndRefreshToken() throws Exception {
        UserEntity user = new UserEntity();
        user.setFirstname("Gina");
        user.setLastname("Customer");
        user.setEmailAddress(email);
        user.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRST");
        user.setStatus("0");
        user.setUserId("G" + System.currentTimeMillis());
        userRepo.save(user);
        userId = user.getUserId();

        when(googleTokenVerifier.verify(anyString())).thenReturn(verifiedClaims(email));

        JsonNode body = postGoogle("valid-token");

        assertEquals("200", body.get("statusCode").asText(), "existing customer must sign in: " + body);
        assertFalse(body.get("token").asText().isBlank(), "must issue a JWT");
        assertFalse(body.get("refreshToken").asText().isBlank(), "must issue a refresh token");
        assertEquals("CUSTOMER", body.get("data").get("role").asText());
        assertEquals(userId, body.get("data").get("account").get("userId").asText());

        // ── The refresh token must actually be persisted (by its SHA-256 hash) ──
        String refreshToken = body.get("refreshToken").asText();
        String oldHash = sha256Hex(refreshToken);
        assertTrue(refreshTokenRepo.findByTokenHashAndRevokedFalse(oldHash).isPresent(),
                "refresh token must be persisted for the customer");

        // ── ... and reusable: /auth/refresh rotates it and returns a fresh pair ──
        MvcResult refreshResult = mockMvc.perform(post("/rapid_stylers/auth/refresh")
                .header("x-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn();
        JsonNode refreshed = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        assertEquals("200", refreshed.get("statusCode").asText(), "refresh must succeed: " + refreshed);
        assertFalse(refreshed.get("token").asText().isBlank(), "refresh must return a new access token");
        String rotated = refreshed.get("refreshToken").asText();
        assertFalse(rotated.isBlank(), "refresh must return a rotated refresh token");
        assertNotEquals(refreshToken, rotated, "refresh token must rotate, never be reused");

        // Old token must be revoked; the rotated one persisted in its place.
        assertTrue(refreshTokenRepo.findByTokenHashAndRevokedFalse(oldHash).isEmpty(),
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

    @Test
    void stylerEmailIsRejectedWithCustomerOnlyMessage() throws Exception {
        StylerEntity styler = new StylerEntity();
        styler.setEmailAddress(email);
        styler.setFirstname("Styler");
        styler.setLastname("Pro");
        styler.setVerificationStatus("APPROVED");
        stylerRepo.save(styler);
        stylerId = styler.getStylerId();

        when(googleTokenVerifier.verify(anyString())).thenReturn(verifiedClaims(email));

        JsonNode body = postGoogle("valid-token");

        assertEquals("400", body.get("statusCode").asText(), "styler email must be rejected");
        assertTrue(body.get("message").asText().toLowerCase().contains("professional"),
                "unexpected message: " + body.get("message").asText());
        assertTrue(userRepo.findByEmailAddress(email).isEmpty(), "no customer account may be created for a styler email");
    }

    @Test
    void newVerifiedEmailAutoCreatesCustomerWithWelcomeNotificationAndOutboxEvent() throws Exception {
        when(googleTokenVerifier.verify(anyString())).thenReturn(verifiedClaims(email));

        JsonNode body = postGoogle("valid-token");

        assertEquals("200", body.get("statusCode").asText(), "auto-create must succeed: " + body);
        assertFalse(body.get("token").asText().isBlank(), "must issue a JWT for the new account");
        assertFalse(body.get("refreshToken").asText().isBlank(), "must issue a refresh token for the new account");
        assertEquals("CUSTOMER", body.get("data").get("role").asText());

        UserEntity created = userRepo.findByEmailAddress(email).orElseThrow();
        userId = created.getUserId();
        assertEquals("Gina", created.getFirstname(), "given name is captured from the verified claims");
        assertEquals("Customer", created.getLastname());
        assertEquals("0", created.getStatus());
        assertNotNull(created.getTermsAcceptedAt(), "auto-create agrees to terms on the user's behalf");
        assertTrue(created.getPassword().startsWith("$2"),
                "password must be stored as a BCrypt hash, not plaintext: " + created.getPassword());

        boolean welcomed = notificationRepo.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .anyMatch(n -> "WELCOME".equals(n.getType()));
        assertTrue(welcomed, "auto-created customer must get the welcome in-app notification");

        boolean welcomeEvent = outboxEventRepo.findAll().stream()
                .anyMatch(e -> OutboxEventType.CUSTOMER_WELCOME.equals(e.getEventType())
                        && userId.equals(e.getAggregateId()));
        assertTrue(welcomeEvent, "auto-created customer must get a CUSTOMER_WELCOME outbox event (welcome email)");
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private JsonNode postGoogle(String idToken) throws Exception {
        MockHttpServletRequestBuilder request = post("/rapid_stylers/google_sign_in")
                .header("x-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("idToken", idToken)));
        MvcResult result = mockMvc.perform(request).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}

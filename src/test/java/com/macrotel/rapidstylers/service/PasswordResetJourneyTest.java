package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.OTPEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.repo.NotificationRepo;
import com.macrotel.rapidstylers.repo.OTPRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Full password-reset journey over the real HTTP stack and the real database,
 * mirroring how a user actually resets a forgotten password:
 *
 *   register (signup OTP) -> request reset token (email OTP captured from the
 *   mocked mailbox) -> verify OTP email-bound -> reset_user_password -> sign in
 *   with the NEW password, old password rejected, OTP not reusable.
 *
 * Emails are intercepted (EmailConfig mocked); OTPs are read from mail bodies
 * because they are stored only as BCrypt hashes. Every row created is deleted
 * in @AfterEach.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetJourneyTest {

    private static final Pattern OTP_PATTERN = Pattern.compile("<strong>(\\d{6})</strong>");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean EmailConfig emailConfig;

    @Autowired UserRepo userRepo;
    @Autowired OTPRepo otpRepo;
    @Autowired NotificationRepo notificationRepo;

    @Value("${app.api.key}") String apiKey;

    private String email;
    private String userId;
    private final String originalPassword = "Original1!";
    private final String newPassword = "NewPass2!";

    @BeforeEach
    void uniqueEmail() {
        email = "reset.cust." + System.currentTimeMillis() + "@rapidstylers.test";
    }

    @AfterEach
    void cleanup() {
        otpRepo.findAll().stream()
                .filter(o -> email.equals(o.getEmailAddress()))
                .forEach(otpRepo::delete);
        if (userId != null) {
            notificationRepo.findByUserIdOrderByCreatedAtDesc(userId).forEach(notificationRepo::delete);
        }
        userRepo.findByEmailAddress(email).ifPresent(userRepo::delete);
    }

    @Test
    void fullPasswordResetOverHttp() throws Exception {
        // ---- 1. Register the account (signup OTP read from the mocked mailbox) ----
        assertOk(apiPost("/generate_sign_up_otp_code", Map.of("emailAddress", email)));
        String signupOtp = lastOtp();
        assertOk(apiPost("/verify_otp_code", Map.of("emailAddress", email, "otpCode", signupOtp)));

        Map<String, Object> userData = new LinkedHashMap<>();
        userData.put("firstname", "Reset");
        userData.put("lastname", "Customer");
        userData.put("emailAddress", email);
        userData.put("country", "Canada");
        userData.put("state", "Ontario");
        userData.put("address", "123 Main St, Toronto, ON M5V 2T6");
        userData.put("phoneNumber", "587555" + String.format("%04d", System.currentTimeMillis() % 10000));
        userData.put("password", originalPassword);
        userData.put("agreeToTerms", true);
        assertOk(apiPost("/create_user_account", userData));
        userId = userRepo.findByEmailAddress(email).orElseThrow().getUserId();

        // Baseline: the original password signs in.
        assertFalse(signIn(email, originalPassword).isBlank(), "original password must work before the reset");

        // ---- 2. Request a reset token for the account ----
        assertOk(apiPost("/generate_reset_password_token", Map.of("emailAddress", email)));
        String resetOtp = lastOtp();

        // The reset OTP is stored as a BCrypt hash, never plaintext.
        OTPEntity storedResetOtp = otpRepo.findLatestUnusedByEmail(email).orElseThrow();
        assertEquals("FORGET PASSWORD", storedResetOtp.getPurpose());
        assertFalse(storedResetOtp.getCode().equals(resetOtp), "reset OTP must not be stored in plaintext");
        assertTrue(storedResetOtp.getCode().startsWith("$2"), "reset OTP must be stored as a BCrypt hash");

        // ---- 3. Verify the code, bound to the email it was issued for ----
        JsonNode verify = body(apiPost("/verify_otp_code", Map.of("emailAddress", email, "otpCode", resetOtp)));
        assertEquals("200", verify.get("statusCode").asText(), "verification must succeed: " + verify);

        // ---- 4. Reset the password with the verified email ----
        JsonNode reset = body(apiPost("/reset_user_password", resetBody(email, newPassword)));
        assertEquals("200", reset.get("statusCode").asText(), "reset must succeed: " + reset);
        assertEquals("Password Change Successful", reset.get("message").asText());

        // ---- 5. Old password rejected; new password signs in ----
        assertInvalidSignIn(email, originalPassword);
        String newToken = signIn(email, newPassword);
        assertFalse(newToken.isBlank(), "sign-in with the new password must return a token");

        // DB state: password is a BCrypt hash matching the new password only.
        UserEntity storedUser = userRepo.findByEmailAddress(email).orElseThrow();
        AppUtils appUtils = new AppUtils();
        assertTrue(storedUser.getPassword().startsWith("$2"), "password must be stored as a BCrypt hash");
        assertTrue(appUtils.passwordMatches(newPassword, storedUser.getPassword()));
        assertFalse(appUtils.passwordMatches(originalPassword, storedUser.getPassword()));

        // ---- 6. The consumed OTP is not reusable for another reset ----
        JsonNode secondReset = body(apiPost("/reset_user_password", resetBody(email, "ThirdPass3!")));
        assertEquals("400", secondReset.get("statusCode").asText(),
                "a reset without a fresh verified OTP must be rejected: " + secondReset);
    }

    // ---- Helpers ----------------------------------------------------------

    private Map<String, String> resetBody(String emailAddress, String password) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("emailAddress", emailAddress);
        body.put("password", password);
        body.put("confirmPassword", password);
        return body;
    }

    private String signIn(String emailAddress, String password) throws Exception {
        JsonNode response = body(apiPost("/user_sign_in", Map.of("emailAddress", emailAddress, "password", password)));
        assertEquals("200", response.get("statusCode").asText(), "sign-in failed: " + response);
        return response.get("token").asText();
    }

    private void assertInvalidSignIn(String emailAddress, String password) throws Exception {
        JsonNode response = body(apiPost("/user_sign_in", Map.of("emailAddress", emailAddress, "password", password)));
        assertEquals("400", response.get("statusCode").asText(),
                "sign-in with the old password must be rejected: " + response);
    }

    /** Most recent captured email that actually carries a 6-digit OTP. */
    private String lastOtp() throws Exception {
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailConfig, atLeastOnce()).sendSimpleMail(anyString(), anyString(), bodyCaptor.capture());
        List<String> bodies = bodyCaptor.getAllValues();
        for (int i = bodies.size() - 1; i >= 0; i--) {
            Matcher matcher = OTP_PATTERN.matcher(bodies.get(i));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        fail("no OTP-bearing email captured");
        return null;
    }

    private void assertOk(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("200", response.get("statusCode").asText(),
                "expected success, got: " + response);
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

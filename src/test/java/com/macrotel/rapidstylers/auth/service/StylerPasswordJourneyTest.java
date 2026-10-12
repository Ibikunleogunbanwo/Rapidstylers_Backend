package com.macrotel.rapidstylers.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.auth.repo.OTPRepo;
import com.macrotel.rapidstylers.auth.repo.RefreshTokenRepo;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

import static com.macrotel.rapidstylers.config.AppConstants.VERIFICATION_APPROVED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Stylists manage their own password over HTTP (QA P-08, BUG-16): the
 * customer reset endpoints now work for a stylist's email, and a signed-in
 * stylist can change their password at /update_styler_password, which a
 * customer token cannot reach.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StylerPasswordJourneyTest {

    private static final Pattern OTP_PATTERN = Pattern.compile("<strong>(\\d{6})</strong>");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean EmailConfig emailConfig;
    @Autowired StylerRepo stylerRepo;
    @Autowired OTPRepo otpRepo;
    @Autowired RefreshTokenRepo refreshTokenRepo;
    @Value("${app.api.key}") String apiKey;

    private String email;
    private String stylerId;

    @BeforeEach
    void stylist() {
        long tag = System.currentTimeMillis() % 100000;
        stylerId = "PW" + tag;
        email = "pw.styler." + tag + "@rapidstylers.test";
        StylerEntity s = new StylerEntity();
        s.setStylerId(stylerId);
        s.setBusinessName("Password Studio");
        s.setFirstname("Pass");
        s.setLastname("Word");
        s.setEmailAddress(email);
        s.setPassword(new AppUtils().encryptPassword("Original1!"));
        s.setVerificationStatus(VERIFICATION_APPROVED);
        s.setStatus("0");
        stylerRepo.save(s);
    }

    @AfterEach
    void cleanup() {
        otpRepo.findAll().stream().filter(o -> email.equals(o.getEmailAddress())).forEach(otpRepo::delete);
        refreshTokenRepo.findAll().stream().filter(t -> stylerId.equals(t.getAccountId())).forEach(refreshTokenRepo::delete);
        stylerRepo.findByStylerId(stylerId).ifPresent(stylerRepo::delete);
    }

    @Test
    void aStylistResetsAForgottenPassword() throws Exception {
        assertEquals("200", body(apiPost("/generate_reset_password_token", Map.of("emailAddress", email), null))
                .get("statusCode").asText());
        String code = lastOtp();
        assertEquals("200", body(apiPost("/verify_otp_code", Map.of("emailAddress", email, "otpCode", code), null))
                .get("statusCode").asText());

        Map<String, String> reset = new LinkedHashMap<>();
        reset.put("emailAddress", email);
        reset.put("password", "NewPass2!");
        reset.put("confirmPassword", "NewPass2!");
        JsonNode done = body(apiPost("/reset_user_password", reset, null));
        assertEquals("200", done.get("statusCode").asText(), "reset must succeed: " + done);

        assertEquals("400", signIn("Original1!").get("statusCode").asText());
        assertEquals("200", signIn("NewPass2!").get("statusCode").asText());
    }

    @Test
    void aSignedInStylistChangesTheirPassword() throws Exception {
        JsonNode otherDevice = signIn("Original1!");
        JsonNode thisDevice = signIn("Original1!");

        Map<String, String> change = new LinkedHashMap<>();
        change.put("oldPassword", "Original1!");
        change.put("password", "NewPass2!");
        change.put("confirmPassword", "NewPass2!");
        JsonNode changed = body(apiPost("/update_styler_password", change, thisDevice.get("token").asText()));
        assertEquals("200", changed.get("statusCode").asText(), "change must succeed: " + changed);
        assertFalse(changed.get("token").asText().isBlank(), "this device gets a fresh token");
        assertFalse(changed.get("refreshToken").asText().isBlank(), "and a fresh refresh token");

        assertEquals(401, apiPost("/auth/refresh",
                Map.of("refreshToken", otherDevice.get("refreshToken").asText()), null).getResponse().getStatus(),
                "other devices are signed out");
        assertEquals("200", signIn("NewPass2!").get("statusCode").asText());
    }

    @Test
    void aWrongOldPasswordIsRefused() throws Exception {
        String token = signIn("Original1!").get("token").asText();
        Map<String, String> change = new LinkedHashMap<>();
        change.put("oldPassword", "Wrong1!x");
        change.put("password", "NewPass2!");
        change.put("confirmPassword", "NewPass2!");

        JsonNode refused = body(apiPost("/update_styler_password", change, token));

        assertEquals("400", refused.get("statusCode").asText());
        assertEquals("Invalid Old Password", refused.get("message").asText());
    }

    @Test
    void theEndpointNeedsAStylistToken() throws Exception {
        Map<String, String> change = Map.of("oldPassword", "Original1!", "password", "NewPass2!", "confirmPassword", "NewPass2!");
        assertEquals(401, apiPost("/update_styler_password", change, null).getResponse().getStatus());
    }

    // ---- Helpers ----------------------------------------------------------

    private JsonNode signIn(String password) throws Exception {
        return body(apiPost("/sign_in", Map.of("emailAddress", email, "password", password), null));
    }

    private String lastOtp() {
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

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult apiPost(String path, Object payload, String token) throws Exception {
        MockHttpServletRequestBuilder request = post("/rapid_stylers" + path)
                .header("x-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }
}

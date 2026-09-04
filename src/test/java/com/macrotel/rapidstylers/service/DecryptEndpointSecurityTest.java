package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.config.EncryptionConfig;
import com.macrotel.rapidstylers.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the /decrypt oracle lock-down over the full HTTP stack: previously any
 * authenticated role (CUSTOMER/STYLER/ADMIN) could decrypt arbitrary ciphertext
 * with the global key. Only ADMIN may reach it now.
 *
 * Tokens are minted with the real JwtUtil (signed like production); no account
 * rows are needed because the role gate and handler never consult the DB.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DecryptEndpointSecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired EncryptionConfig encryptionConfig;
    @MockBean EmailConfig emailConfig;

    @Value("${app.api.key}") String apiKey;

    private static final String CUSTOMER_PLAINTEXT = "CARD-USER-9F2K";

    @Test
    void decryptRejectsCustomerRole() throws Exception {
        String token = jwtUtil.generateToken("decrypt.cust." + System.nanoTime(), "CUSTOMER");
        mockMvc.perform(decryptRequest(token, "\"garbage\""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void decryptRejectsStylerRole() throws Exception {
        String token = jwtUtil.generateToken("decrypt.styl." + System.nanoTime(), "STYLER");
        mockMvc.perform(decryptRequest(token, "\"garbage\""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void decryptRejectsAnonymous() throws Exception {
        mockMvc.perform(decryptRequest(null, "\"garbage\""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanDecryptValidCiphertext() throws Exception {
        String token = jwtUtil.generateToken("decrypt.adm." + System.nanoTime(), "ADMIN");
        String ciphertext = encryptionConfig.encrypt(CUSTOMER_PLAINTEXT);
        mockMvc.perform(decryptRequest(token, "\"" + ciphertext + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value("200"))
                .andExpect(jsonPath("$.data.value").value(CUSTOMER_PLAINTEXT));
    }

    @Test
    void adminDecryptRejectsGarbageCiphertext() throws Exception {
        String token = jwtUtil.generateToken("decrypt.adm." + System.nanoTime(), "ADMIN");
        mockMvc.perform(decryptRequest(token, "\"garbage-not-base64!!!\""))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder decryptRequest(
            String token, String encryptedJson) {
        var request = post("/rapid_stylers/decrypt")
                .header("x-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"encrypted\":" + encryptedJson + "}");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request;
    }
}

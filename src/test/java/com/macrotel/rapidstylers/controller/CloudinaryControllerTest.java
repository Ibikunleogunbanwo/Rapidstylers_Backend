package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import static com.macrotel.rapidstylers.config.AppConstants.ERROR_STATUS_CODE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudinaryControllerTest {

    private CloudinaryController controller;
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        controller = new CloudinaryController();
        rateLimiterService = mock(RateLimiterService.class);
        ReflectionTestUtils.setField(controller, "cloudName", "rapid");
        ReflectionTestUtils.setField(controller, "apiKey", "api-key");
        ReflectionTestUtils.setField(controller, "apiSecret", "secret");
        ReflectionTestUtils.setField(controller, "allowedFolderPrefixes", "profile,id,store,portfolio");
        ReflectionTestUtils.setField(controller, "allowedFormats", "jpg,jpeg,png,webp,gif");
        ReflectionTestUtils.setField(controller, "maxFileSizeBytes", 5242880L);
        ReflectionTestUtils.setField(controller, "rateLimiterService", rateLimiterService);
    }

    @Test
    void uploadSignatureRejectsUnapprovedFolderPrefix() {
        ResponseEntity<BaseResponse> response = controller.getUploadSignature("../admin");

        assertEquals(ERROR_STATUS_CODE, response.getBody().getStatusCode());
    }

    @Test
    void uploadSignatureAllowsConfiguredFolderPrefix() {
        ResponseEntity<BaseResponse> response = controller.getUploadSignature("profile");
        Map<?, ?> params = params(response);

        assertEquals(SUCCESS_STATUS_CODE, response.getBody().getStatusCode());
        assertEquals("rapid_stylers/profile", params.get("folder"));
    }

    /**
     * The point of audit check 16: the type and size limits have to be inside the
     * signature, because Cloudinary only enforces what was signed. This recomputes
     * the hash over the exact expected parameter string, so dropping a constraint
     * from the signature — the failure that would silently return enforcement to
     * the Cloudinary console — cannot pass review.
     */
    @Test
    void uploadSignatureCoversTheUploadConstraints() throws Exception {
        ResponseEntity<BaseResponse> response = controller.getUploadSignature("profile");
        Map<?, ?> params = params(response);

        assertEquals("jpg,jpeg,png,webp,gif", params.get("allowed_formats"));
        assertEquals("5242880", params.get("max_file_size"));

        String expected = sha1("allowed_formats=jpg,jpeg,png,webp,gif"
                + "&folder=rapid_stylers/profile"
                + "&max_file_size=5242880"
                + "&timestamp=" + params.get("timestamp")
                + "secret");
        assertEquals(expected, params.get("signature"));
    }

    /**
     * Whitespace or casing in the configured format list would produce a
     * signature the client cannot reproduce, breaking every upload — so it is
     * normalised before it is signed, and the returned value is the normalised one.
     */
    @Test
    void uploadSignatureNormalisesTheConfiguredFormatList() {
        ReflectionTestUtils.setField(controller, "allowedFormats", " JPG, PNG ,, webp ");

        Map<?, ?> params = params(controller.getUploadSignature("profile"));

        assertEquals("jpg,png,webp", params.get("allowed_formats"));
        assertEquals("jpg,png,webp", ((Map<?, ?>) controller.getUploadSignature("profile").getBody().getData()).get("allowedFormats"));
    }

    /** A blank format list must never sign as empty, which would allow any type. */
    @Test
    void uploadSignatureFallsBackWhenFormatListIsBlank() {
        ReflectionTestUtils.setField(controller, "allowedFormats", " , ");

        Map<?, ?> params = params(controller.getUploadSignature("profile"));

        assertEquals("jpg,jpeg,png,webp,gif", params.get("allowed_formats"));
    }

    @Test
    void uploadSignatureIsRateLimitedByIp() {
        when(rateLimiterService.clientIp()).thenReturn("unknown");
        when(rateLimiterService.isBlocked("cloudinary-signature:unknown", 900, 30)).thenReturn(true);

        ResponseEntity<BaseResponse> response = controller.getUploadSignature("profile");

        assertEquals(ERROR_STATUS_CODE, response.getBody().getStatusCode());
    }

    private Map<?, ?> params(ResponseEntity<BaseResponse> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().getData();
        return (Map<?, ?>) data.get("params");
    }

    private static String sha1(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

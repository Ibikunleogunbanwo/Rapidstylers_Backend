package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

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
        Map<?, ?> data = (Map<?, ?>) response.getBody().getData();

        assertEquals(SUCCESS_STATUS_CODE, response.getBody().getStatusCode());
        assertEquals("rapid_stylers/profile", data.get("folder"));
    }

    @Test
    void uploadSignatureIsRateLimitedByIp() {
        when(rateLimiterService.clientIp()).thenReturn("unknown");
        when(rateLimiterService.isBlocked("cloudinary-signature:unknown", 900, 30)).thenReturn(true);

        ResponseEntity<BaseResponse> response = controller.getUploadSignature("profile");

        assertEquals(ERROR_STATUS_CODE, response.getBody().getStatusCode());
    }
}

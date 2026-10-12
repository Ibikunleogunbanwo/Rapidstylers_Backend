package com.macrotel.rapidstylers.media.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** An admin gets a ten-minute link to a stylist's ID photo, private or (older) public. */
class IdentificationImageServiceTest {

    private IdentificationImageService service;
    private StylerRepo stylerRepo;

    @BeforeEach
    void setUp() {
        CloudinaryService cloudinary = new CloudinaryService();
        ReflectionTestUtils.setField(cloudinary, "cloudName", "demo");
        ReflectionTestUtils.setField(cloudinary, "apiKey", "key123");
        ReflectionTestUtils.setField(cloudinary, "apiSecret", "s3cret");
        stylerRepo = mock(StylerRepo.class);
        when(stylerRepo.findByStylerId(anyString())).thenReturn(Optional.empty());
        service = new IdentificationImageService();
        service.cloudinaryService = cloudinary;
        service.stylerRecords = new StylerRecords(stylerRepo, null);
        service.auditService = new AuditService();
    }

    private void stylistWithId(String url) {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("CM1");
        styler.setIdentificationImageUrl(url);
        when(stylerRepo.findByStylerId("CM1")).thenReturn(Optional.of(styler));
    }

    private Map<?, ?> data(BaseResponse response) {
        assertEquals("200", response.getStatusCode(), response.getMessage());
        return (Map<?, ?>) response.getData();
    }

    @Test
    void aPrivateIdPhotoGetsATenMinuteSignedDownloadLink() {
        stylistWithId("https://res.cloudinary.com/demo/image/authenticated/s--Ab1--/v17/rapid_stylers/id/acct_0123456789abcdef/card.png");

        Map<?, ?> data = data(service.signedIdentificationLink("CM1", "admin@example.com"));

        String url = (String) data.get("url");
        assertTrue(url.startsWith("https://api.cloudinary.com/v1_1/demo/image/download?"), url);
        assertTrue(url.contains("public_id=rapid_stylers%2Fid%2Facct_0123456789abcdef%2Fcard"), url);
        assertTrue(url.contains("type=authenticated"), url);
        assertTrue(url.contains("format=png"), url);
        long left = Duration.between(Instant.now(), Instant.parse((String) data.get("expiresAt"))).getSeconds();
        assertTrue(left > 590 && left <= 600, "expires in " + left + "s");
        assertTrue(url.contains("expires_at=" + Instant.parse((String) data.get("expiresAt")).getEpochSecond()), url);
    }

    @Test
    void anOlderPublicIdPhotoStillOpens() {
        stylistWithId("https://res.cloudinary.com/demo/image/upload/v1/rapid_stylers/id/scan.jpg");

        String url = (String) data(service.signedIdentificationLink("CM1", "admin@example.com")).get("url");

        assertTrue(url.contains("type=upload"), url);
        assertTrue(url.contains("public_id=rapid_stylers%2Fid%2Fscan"), url);
    }

    @Test
    void refusesUnknownStylistsAndMissingImages() {
        assertEquals("400", service.signedIdentificationLink("NOPE", "a").getStatusCode());
        assertEquals("400", service.signedIdentificationLink(" ", "a").getStatusCode());
        stylistWithId("https://example.com/id.jpg");
        assertEquals("400", service.signedIdentificationLink("CM1", "a").getStatusCode());
    }
}

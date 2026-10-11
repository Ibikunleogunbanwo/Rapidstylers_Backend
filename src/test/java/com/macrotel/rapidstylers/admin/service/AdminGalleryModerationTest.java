package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.common.repo.AuditLogRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.media.service.GalleryService;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.styler.dto.StylerPortfolioDTO;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.StylerPortfolioEntity;
import com.macrotel.rapidstylers.styler.pojo.StylerPortfolioData;
import com.macrotel.rapidstylers.styler.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.service.PortfolioModerationService;
import com.macrotel.rapidstylers.styler.service.PortfolioService;
import java.util.List;
import java.util.Map;
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

import static com.macrotel.rapidstylers.config.AppConstants.GALLERY_CATEGORIES;
import static com.macrotel.rapidstylers.config.AppConstants.VERIFICATION_APPROVED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gallery moderation end to end: a new upload waits in the admin queue and
 * stays out of the public gallery; approval puts it in; rejection takes it off
 * the gallery and the public profile, tells the stylist why, and is audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminGalleryModerationTest {

    @Autowired PortfolioModerationService moderation;
    @Autowired PortfolioService portfolioService;
    @Autowired GalleryService galleryService;
    @Autowired StylerRepo stylerRepo;
    @Autowired StylerPortfolioRepo portfolioRepo;
    @Autowired AuditLogRepo auditLogRepo;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean EmailConfig emailConfig;
    @Value("${app.api.key}") String apiKey;

    private final String tag = "AG" + Long.toString(System.nanoTime(), 36).toUpperCase();
    private final String stylerId = tag + "ST";
    private final String category = GALLERY_CATEGORIES.get(0);
    private final String admin = "ADM" + tag;
    private Long photoId;

    @BeforeEach
    void seed() {
        StylerEntity s = new StylerEntity();
        s.setStylerId(stylerId);
        s.setBusinessName("Studio " + tag);
        s.setFirstname("Gal");
        s.setLastname("Lery");
        s.setEmailAddress(stylerId.toLowerCase() + "@rapidstylers.test");
        s.setVerificationStatus(VERIFICATION_APPROVED);
        s.setStatus("0");
        stylerRepo.save(s);
        StylerPortfolioData upload = new StylerPortfolioData();
        upload.setStylerId(stylerId);
        upload.setImageUrl("https://res.cloudinary.com/test/image/upload/" + tag + ".jpg");
        upload.setCategory(category);
        assertEquals("200", portfolioService.createStylerPortfolio(upload).getStatusCode());
        photoId = portfolioRepo.findByStylerId(stylerId).get(0).getId();
    }

    @AfterEach
    void cleanUp() {
        portfolioRepo.findByStylerId(stylerId).forEach(portfolioRepo::delete);
        stylerRepo.findByStylerId(stylerId).ifPresent(stylerRepo::delete);
    }

    @Test
    void aNewUploadWaitsInTheQueueAndApprovalPutsItInTheGallery() {
        assertEquals(StylerPortfolioEntity.PENDING, portfolioRepo.findById(photoId).orElseThrow().getModerationStatus());
        assertTrue(galleryPhotos().isEmpty(), "pending photos are not public");
        assertEquals(1, publicProfile().size(), "the stylist's own profile still shows it");
        assertTrue(queueIds("PENDING").contains(photoId));

        BaseResponse r = moderation.approve(admin, photoId, null);
        assertEquals("200", r.getStatusCode());
        assertEquals(1, galleryPhotos().size());
        assertFalse(queueIds("PENDING").contains(photoId));
        assertTrue(queueIds("APPROVED").contains(photoId));
        verify(emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void rejectionNeedsANoteHidesThePhotoAndTellsTheStylist() {
        moderation.approve(admin, photoId, null);
        assertEquals("400", moderation.reject(admin, photoId, "  ").getStatusCode());
        assertEquals(1, galleryPhotos().size(), "no note, no change");

        BaseResponse r = moderation.reject(admin, photoId, "Blurry <b>photo</b>");
        assertEquals("200", r.getStatusCode());
        assertTrue(galleryPhotos().isEmpty());
        assertTrue(publicProfile().isEmpty(), "rejected photos leave the public profile too");

        @SuppressWarnings("unchecked")
        List<StylerPortfolioDTO> own = (List<StylerPortfolioDTO>) portfolioService.listOwnStylerPortfolio(stylerId).getData();
        assertEquals("REJECTED", own.get(0).getModerationStatus());
        assertEquals("Blurry <b>photo</b>", own.get(0).getModerationNote());

        verify(emailConfig).sendSimpleMail(eq(stylerId.toLowerCase() + "@rapidstylers.test"), anyString(),
                contains("Blurry &lt;b&gt;photo&lt;/b&gt;"));
        assertTrue(auditLogRepo.findTop100ByOrderByCreatedAtDesc().stream()
                .anyMatch(a -> "ADMIN_GALLERY_REJECTED".equals(a.getAction()) && admin.equals(a.getActorId())));

        @SuppressWarnings("unchecked")
        Map<String, Object> history = (Map<String, Object>) moderation.history(photoId).getData();
        assertEquals(2, ((List<?>) history.get("history")).size(), "approved, then rejected");
    }

    @Test
    void unknownPhotosAndBadStatesAreRefused() {
        assertEquals("404", moderation.approve(admin, -1L, null).getStatusCode());
        assertEquals("404", moderation.history(-1L).getStatusCode());
        assertEquals("400", moderation.queue("maybe", 0, 10).getStatusCode());
    }

    @Test
    void onlyAdminsModerate() throws Exception {
        String body = "{\"portfolioId\":" + photoId + "}";
        mockMvc.perform(post("/rapid_stylers/admin/approve_gallery_image").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(stylerId, "STYLER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertEquals(StylerPortfolioEntity.PENDING, portfolioRepo.findById(photoId).orElseThrow().getModerationStatus());
        mockMvc.perform(post("/rapid_stylers/admin/approve_gallery_image").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertEquals(StylerPortfolioEntity.APPROVED, portfolioRepo.findById(photoId).orElseThrow().getModerationStatus());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> galleryPhotos() {
        return (List<Map<String, Object>>) galleryService.searchGallery(category, 50, 1, "studio " + tag.toLowerCase()).getData();
    }

    @SuppressWarnings("unchecked")
    private List<Object> publicProfile() {
        return (List<Object>) portfolioService.listStylerPortfolio(stylerId).getData();
    }

    @SuppressWarnings("unchecked")
    private List<Long> queueIds(String status) {
        Map<String, Object> data = (Map<String, Object>) moderation.queue(status, 0, 100).getData();
        return ((List<Map<String, Object>>) data.get("items")).stream()
                .map(i -> (Long) i.get("id")).collect(java.util.stream.Collectors.toList());
    }
}

package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.repo.BlogPostRepo;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rule this class enforces is one-directional on purpose: an asset may be
 * destroyed only when no row in any image column still holds its URL. Everything
 * else here is about the ways that has to fail softly, because it runs after the
 * user's own operation has already succeeded and must never turn a successful
 * delete into an error.
 */
class ImageReclaimServiceTest {

    private ImageReclaimService reclaimService;
    private CloudinaryService cloudinaryService;
    private StylerRepo stylerRepo;
    private StylerPortfolioRepo stylerPortfolioRepo;
    private BlogPostRepo blogPostRepo;
    private ServiceRepo serviceRepo;

    private static final String ORPHAN_URL =
            "https://res.cloudinary.com/demo/image/upload/v1712345678/rapid_stylers/portfolio/work.jpg";

    @BeforeEach
    void setUp() {
        reclaimService = new ImageReclaimService();
        cloudinaryService = mock(CloudinaryService.class);
        stylerRepo = mock(StylerRepo.class);
        stylerPortfolioRepo = mock(StylerPortfolioRepo.class);
        blogPostRepo = mock(BlogPostRepo.class);
        serviceRepo = mock(ServiceRepo.class);

        reclaimService.cloudinaryService = cloudinaryService;
        reclaimService.stylerRepo = stylerRepo;
        reclaimService.stylerPortfolioRepo = stylerPortfolioRepo;
        reclaimService.blogPostRepo = blogPostRepo;
        reclaimService.serviceRepo = serviceRepo;

        when(cloudinaryService.destroy(anyString())).thenReturn(true);
    }

    @Test
    void destroysAnImageNoRowReferences() {
        when(cloudinaryService.extractPublicId(ORPHAN_URL)).thenReturn("rapid_stylers/portfolio/work");

        assertTrue(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));
        verify(cloudinaryService).destroy("rapid_stylers/portfolio/work");
    }

    @Test
    void keepsAnImageAProfileStillUses() {
        // A profile photo is a reference like any other: someone else's row, or the
        // row this call is not about, still needs the picture to exist.
        when(stylerRepo.countByProfileImageUrl(ORPHAN_URL)).thenReturn(1L);

        assertFalse(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));
        verify(cloudinaryService, never()).destroy(anyString());
    }

    @Test
    void keepsAnImageAnyOtherColumnUses() {
        // Each column that can hold an image is checked, not just the obvious one.
        when(stylerRepo.countByIdentificationImageUrl(ORPHAN_URL)).thenReturn(1L);
        assertFalse(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));

        when(stylerRepo.countByIdentificationImageUrl(ORPHAN_URL)).thenReturn(0L);
        when(stylerPortfolioRepo.countByImageUrl(ORPHAN_URL)).thenReturn(2L);
        assertFalse(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));

        when(stylerPortfolioRepo.countByImageUrl(ORPHAN_URL)).thenReturn(0L);
        when(blogPostRepo.countByImageUrl(ORPHAN_URL)).thenReturn(1L);
        assertFalse(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));

        when(blogPostRepo.countByImageUrl(ORPHAN_URL)).thenReturn(0L);
        when(serviceRepo.countByServiceImageUrl(ORPHAN_URL)).thenReturn(1L);
        assertFalse(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));

        verify(cloudinaryService, never()).destroy(anyString());
    }

    @Test
    void ignoresAnythingThatIsNotOurCloudinaryUrl() {
        // Static images served by this app and images hosted elsewhere have no
        // public id and are none of this service's business.
        assertFalse(reclaimService.reclaimIfUnreferenced("/images/gallery/locs-2.jpg"));
        assertFalse(reclaimService.reclaimIfUnreferenced("https://example.com/photo.jpg"));
        assertFalse(reclaimService.reclaimIfUnreferenced(null));
        assertFalse(reclaimService.reclaimIfUnreferenced("   "));
        verify(cloudinaryService, never()).destroy(anyString());
    }

    @Test
    void neverFailsTheOperationThatTriggeredIt() {
        // A database that cannot answer, or a Cloudinary that refuses, is a reason
        // to log and move on, never a reason to report failure to the user.
        when(stylerRepo.countByProfileImageUrl(anyString()))
                .thenThrow(new RuntimeException("database is down"));

        assertFalse(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));
        verify(cloudinaryService, never()).destroy(anyString());
    }

    @Test
    void reportsWhenCloudinaryRefusedTheDelete() {
        when(cloudinaryService.extractPublicId(ORPHAN_URL)).thenReturn("rapid_stylers/portfolio/work");
        when(cloudinaryService.destroy(anyString())).thenReturn(false);

        assertFalse(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));
    }
}

package com.macrotel.rapidstylers.media.service;

import com.macrotel.rapidstylers.catalog.repo.BlogPostRepo;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.styler.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
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
    private static final String PUBLIC_ID = "rapid_stylers/portfolio/work";
    /** What a stored URL of that asset contains, whichever way it is spelled. */
    private static final String FRAGMENT = "/" + PUBLIC_ID;

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
        when(cloudinaryService.extractPublicId(ORPHAN_URL)).thenReturn(PUBLIC_ID);
    }

    @Test
    void anotherSpellingOfAUsedImageIsStillCountedAsUsed() {
        // The row stores the plain URL; the caller sends a variant (no version, a
        // different extension, a query). The asset is the same, so it stays.
        String variant = "https://res.cloudinary.com/demo/image/upload/rapid_stylers/portfolio/work.png?x";
        when(cloudinaryService.extractPublicId(variant)).thenReturn(PUBLIC_ID);
        when(stylerPortfolioRepo.countByImageUrlContaining(FRAGMENT)).thenReturn(1L);

        assertFalse(reclaimService.reclaimIfUnreferenced(variant));
        verify(cloudinaryService, never()).destroy(anyString());
    }

    @Test
    void deleteByPublicIdOnlyRemovesOrphans() {
        when(stylerRepo.countByProfileImageUrlContaining(FRAGMENT)).thenReturn(1L);
        assertFalse(reclaimService.reclaimPublicIdIfUnreferenced(PUBLIC_ID), "someone's profile photo must be kept");

        when(stylerRepo.countByProfileImageUrlContaining(FRAGMENT)).thenReturn(0L);
        assertTrue(reclaimService.reclaimPublicIdIfUnreferenced(PUBLIC_ID));
    }

    @Test
    void deleteByPublicIdRefusesIdsThisAppNeverIssued() {
        assertFalse(reclaimService.reclaimPublicIdIfUnreferenced("someone_else/photo"));
        assertFalse(reclaimService.reclaimPublicIdIfUnreferenced("rapid_stylers/../other"));
        assertFalse(reclaimService.reclaimPublicIdIfUnreferenced(""));
        assertFalse(reclaimService.reclaimPublicIdIfUnreferenced(null));
        verify(cloudinaryService, never()).destroy(anyString());
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
        when(stylerRepo.countByProfileImageUrlContaining(FRAGMENT)).thenReturn(1L);

        assertFalse(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));
        verify(cloudinaryService, never()).destroy(anyString());
    }

    @Test
    void keepsAnImageAnyOtherColumnUses() {
        // Each column that can hold an image is checked, not just the obvious one.
        when(stylerRepo.countByIdentificationImageUrlContaining(FRAGMENT)).thenReturn(1L);
        assertFalse(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));

        when(stylerRepo.countByIdentificationImageUrlContaining(FRAGMENT)).thenReturn(0L);
        when(stylerPortfolioRepo.countByImageUrlContaining(FRAGMENT)).thenReturn(2L);
        assertFalse(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));

        when(stylerPortfolioRepo.countByImageUrlContaining(FRAGMENT)).thenReturn(0L);
        when(blogPostRepo.countByImageUrlContaining(FRAGMENT)).thenReturn(1L);
        assertFalse(reclaimService.reclaimIfUnreferenced(ORPHAN_URL));

        when(blogPostRepo.countByImageUrlContaining(FRAGMENT)).thenReturn(0L);
        when(serviceRepo.countByServiceImageUrlContaining(FRAGMENT)).thenReturn(1L);
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
        when(stylerRepo.countByProfileImageUrlContaining(anyString()))
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

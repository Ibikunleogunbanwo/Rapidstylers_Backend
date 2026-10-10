package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a public id out of a stored URL is the step that decides whether a
 * delete is even possible, so it has to be exact: too loose and it would send
 * Cloudinary a name that belongs to something else, too strict and every orphan
 * stays where it is.
 */
class CloudinaryServiceTest {

    private final CloudinaryService cloudinaryService = new CloudinaryService();

    @Test
    void readsThePublicIdFromAStoredDeliveryUrl() {
        assertEquals(
                "rapid_stylers/profile/ada",
                cloudinaryService.extractPublicId(
                        "https://res.cloudinary.com/demo/image/upload/v1712345678/rapid_stylers/profile/ada.jpg"));
    }

    @Test
    void readsItWhenCloudinaryOmitsTheVersionSegment() {
        // The version is not always present, and its absence must not shift the id.
        assertEquals(
                "rapid_stylers/id/scan",
                cloudinaryService.extractPublicId(
                        "https://res.cloudinary.com/demo/image/upload/rapid_stylers/id/scan.png"));
    }

    @Test
    void keepsFoldersInsideThePublicId() {
        assertEquals(
                "rapid_stylers/portfolio/deep/nested/work",
                cloudinaryService.extractPublicId(
                        "https://res.cloudinary.com/demo/image/upload/v1/rapid_stylers/portfolio/deep/nested/work.webp"));
    }

    @Test
    void dropsQueryStringsAndExtensions() {
        assertEquals(
                "rapid_stylers/store/cover",
                cloudinaryService.extractPublicId(
                        "https://res.cloudinary.com/demo/image/upload/v9/rapid_stylers/store/cover.jpeg?x=1"));
    }

    @Test
    void refusesUrlsThisAccountDoesNotOwn() {
        // The static gallery the site serves itself, and images hosted elsewhere,
        // must never be mistaken for something this account can destroy.
        assertNull(cloudinaryService.extractPublicId("/images/gallery/locs-2.jpg"));
        assertNull(cloudinaryService.extractPublicId("https://example.com/image/upload/x.jpg"));
        assertNull(cloudinaryService.extractPublicId("https://res.cloudinary.com/demo/video/upload/v1/clip.mp4"));
        assertNull(cloudinaryService.extractPublicId(""));
        assertNull(cloudinaryService.extractPublicId("   "));
        assertNull(cloudinaryService.extractPublicId(null));
    }

    @Test
    void doesNotCallCloudinaryWhenItIsNotConfigured() {
        // No credentials means no request at all: attempting one per image would
        // just queue failures, and the honest answer is that nothing was destroyed.
        assertFalse(cloudinaryService.isConfigured());
        assertFalse(cloudinaryService.destroy("rapid_stylers/profile/ada"));
    }

    @Test
    void ignoresAnEmptyPublicId() {
        assertFalse(cloudinaryService.destroy(""));
        assertFalse(cloudinaryService.destroy(null));
    }

    @Test
    void stillReportsWhenCredentialsArePresent() throws Exception {
        // Configured means "we would talk to Cloudinary", which is what stops the
        // unconfigured short-circuit from silently swallowing every delete.
        var cloudName = CloudinaryService.class.getDeclaredField("cloudName");
        var apiKey = CloudinaryService.class.getDeclaredField("apiKey");
        var apiSecret = CloudinaryService.class.getDeclaredField("apiSecret");
        cloudName.setAccessible(true);
        apiKey.setAccessible(true);
        apiSecret.setAccessible(true);
        cloudName.set(cloudinaryService, "demo");
        apiKey.set(cloudinaryService, "key");
        apiSecret.set(cloudinaryService, "secret");

        assertTrue(cloudinaryService.isConfigured());
    }
}

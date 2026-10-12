package com.macrotel.rapidstylers.media.service;

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

    // ── Private (authenticated) ID photos ─────────────────────────────

    private static final String SIGNED_ID_URL =
            "https://res.cloudinary.com/demo/image/authenticated/s--Ab1_cD-2--/v1712345678/rapid_stylers/id/acct_0123456789abcdef/card.png";

    @Test
    void readsThePublicIdAndTypeOfAPrivateUpload() {
        assertEquals("rapid_stylers/id/acct_0123456789abcdef/card", cloudinaryService.extractPublicId(SIGNED_ID_URL));
        assertEquals("authenticated", cloudinaryService.deliveryType(SIGNED_ID_URL));
        assertEquals("upload", cloudinaryService.deliveryType(
                "https://res.cloudinary.com/demo/image/upload/v1/rapid_stylers/id/scan.png"));
        assertNull(cloudinaryService.deliveryType("/images/gallery/locs-2.jpg"));
    }

    @Test
    void aListRowNeverCarriesThePermanentSignature() {
        assertEquals("https://res.cloudinary.com/demo/image/authenticated/v1712345678/rapid_stylers/id/acct_0123456789abcdef/card.png",
                CloudinaryService.withoutDeliverySignature(SIGNED_ID_URL));
        String publicUrl = "https://res.cloudinary.com/demo/image/upload/v1/rapid_stylers/id/scan.png";
        assertEquals(publicUrl, CloudinaryService.withoutDeliverySignature(publicUrl));
        assertNull(CloudinaryService.withoutDeliverySignature(null));
    }

    @Test
    void readsTheFileFormat() {
        assertEquals("png", CloudinaryService.fileFormat(SIGNED_ID_URL));
        assertEquals("jpeg", CloudinaryService.fileFormat("https://res.cloudinary.com/d/image/upload/a/b.JPEG?x=1"));
        assertNull(CloudinaryService.fileFormat("https://res.cloudinary.com/d/image/upload/a/b"));
    }

    /**
     * The download link is signed the way Cloudinary checks it: every parameter
     * but api_key, sorted, joined with '&', the secret appended, SHA-1. This
     * recomputes it from the link itself, so a parameter that is sent but not
     * signed (or the reverse) fails here instead of with a live 401.
     */
    @Test
    void signsAnExpiringPrivateDownloadLink() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(cloudinaryService, "cloudName", "demo");
        org.springframework.test.util.ReflectionTestUtils.setField(cloudinaryService, "apiKey", "key123");
        org.springframework.test.util.ReflectionTestUtils.setField(cloudinaryService, "apiSecret", "s3cret");

        String url = cloudinaryService.privateDownloadUrl("rapid_stylers/id/x/card", "png", "authenticated", 1790000000L);

        assertTrue(url.startsWith("https://api.cloudinary.com/v1_1/demo/image/download?api_key=key123&"), url);
        java.util.Map<String, String> query = new java.util.LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            query.put(kv[0], java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals("rapid_stylers/id/x/card", query.get("public_id"));
        assertEquals("authenticated", query.get("type"));
        assertEquals("1790000000", query.get("expires_at"));
        assertEquals("png", query.get("format"));
        String signed = "expires_at=1790000000&format=png&public_id=rapid_stylers/id/x/card"
                + "&timestamp=" + query.get("timestamp") + "&type=authenticated" + "s3cret";
        assertEquals(sha1(signed), query.get("signature"));
    }

    @Test
    void anOlderPublicIdPhotoGetsTheSameKindOfLink() {
        org.springframework.test.util.ReflectionTestUtils.setField(cloudinaryService, "cloudName", "demo");
        org.springframework.test.util.ReflectionTestUtils.setField(cloudinaryService, "apiKey", "key123");
        org.springframework.test.util.ReflectionTestUtils.setField(cloudinaryService, "apiSecret", "s3cret");

        String url = cloudinaryService.privateDownloadUrl("rapid_stylers/id/scan", null, null, 1790000000L);

        assertTrue(url.contains("&type=upload&"), url);
        assertFalse(url.contains("format="), url);
    }

    @Test
    void noLinkWithoutCredentials() {
        assertNull(cloudinaryService.privateDownloadUrl("rapid_stylers/id/scan", "png", "authenticated", 1L));
    }

    private static String sha1(String input) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

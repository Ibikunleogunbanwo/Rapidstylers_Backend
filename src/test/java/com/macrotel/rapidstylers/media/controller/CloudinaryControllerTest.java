package com.macrotel.rapidstylers.media.controller;

import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

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
     * The point of audit check 16: the type limit has to be inside the
     * signature, because Cloudinary only enforces what was signed. This recomputes
     * the hash over the exact expected parameter string, so dropping a constraint
     * from the signature cannot pass review.
     *
     * It also pins the signed set to parameters Cloudinary's upload API
     * accepts. Cloudinary leaves unknown parameters out of the string it
     * checks, so signing max_file_size (not an upload parameter) failed every
     * live upload with "Invalid Signature" (Cloudinary reported signing
     * allowed_formats, folder and timestamp only).
     */
    @Test
    void uploadSignatureCoversOnlyParametersCloudinarySigns() throws Exception {
        ResponseEntity<BaseResponse> response = controller.getUploadSignature("profile");
        Map<?, ?> params = params(response);

        assertEquals("jpg,jpeg,png,webp,gif", params.get("allowed_formats"));
        assertEquals(java.util.Set.of("api_key", "timestamp", "folder", "allowed_formats", "transformation", "signature"),
                params.keySet());
        assertEquals("c_limit,h_2000,w_2000", params.get("transformation"));

        String expected = sha1("allowed_formats=jpg,jpeg,png,webp,gif"
                + "&folder=rapid_stylers/profile"
                + "&timestamp=" + params.get("timestamp")
                + "&transformation=c_limit,h_2000,w_2000"
                + "secret");
        assertEquals(expected, params.get("signature"));
        // The size ceiling still reaches the client, outside the signed fields.
        assertEquals("5242880", ((Map<?, ?>) response.getBody().getData()).get("maxFileSize"));
    }

    /**
     * ID photos are uploaded private: type=authenticated is sent and signed (in
     * its sorted place, after transformation), so a client cannot drop it and
     * still upload into the ID folder.
     */
    @Test
    void idPhotosAreUploadedAsPrivateAssets() throws Exception {
        Map<?, ?> params = params(controller.getUploadSignature("id"));

        assertEquals("authenticated", params.get("type"));
        assertEquals(java.util.Set.of("api_key", "timestamp", "folder", "allowed_formats", "transformation", "type", "signature"),
                params.keySet());
        assertEquals(sha1("allowed_formats=jpg,jpeg,png,webp,gif"
                + "&folder=rapid_stylers/id"
                + "&timestamp=" + params.get("timestamp")
                + "&transformation=c_limit,h_2000,w_2000"
                + "&type=authenticated"
                + "secret"), params.get("signature"));
    }

    @Test
    void otherFoldersStayPublic() {
        for (String prefix : new String[]{"profile", "store", "portfolio", ""}) {
            assertEquals(null, params(controller.getUploadSignature(prefix)).get("type"), prefix);
        }
    }

    /** With the size cap switched off, nothing about it is sent or signed. */
    @Test
    void blankIncomingTransformationIsNeitherSentNorSigned() throws Exception {
        ReflectionTestUtils.setField(controller, "incomingTransformation", " ");

        Map<?, ?> params = params(controller.getUploadSignature("profile"));

        assertEquals(java.util.Set.of("api_key", "timestamp", "folder", "allowed_formats", "signature"), params.keySet());
        assertEquals(sha1("allowed_formats=jpg,jpeg,png,webp,gif&folder=rapid_stylers/profile&timestamp="
                + params.get("timestamp") + "secret"), params.get("signature"));
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

    // ── Each caller's own folder ──────────────────────────────────────

    private void signedInAs(String subject, String role) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(subject, null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))));
    }

    @org.junit.jupiter.api.AfterEach
    void signOut() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void aSignUpUploadsIntoItsOwnFolder() {
        com.macrotel.rapidstylers.media.service.UploadOwnership ownership = new com.macrotel.rapidstylers.media.service.UploadOwnership();
        ReflectionTestUtils.setField(controller, "uploadOwnership", ownership);
        signedInAs("chloe@example.com", "SIGNUP_UPLOAD");

        Map<?, ?> params = params(controller.getUploadSignature("profile"));

        assertEquals("rapid_stylers/profile/" + ownership.folderFor("SIGNUP_UPLOAD", "chloe@example.com"), params.get("folder"));
    }

    @Test
    void nobodyButAnAdminCanDeleteSomeoneElsesUpload() {
        com.macrotel.rapidstylers.media.service.UploadOwnership ownership = new com.macrotel.rapidstylers.media.service.UploadOwnership();
        com.macrotel.rapidstylers.media.service.ImageReclaimService reclaim = org.mockito.Mockito.mock(com.macrotel.rapidstylers.media.service.ImageReclaimService.class);
        ReflectionTestUtils.setField(controller, "uploadOwnership", ownership);
        ReflectionTestUtils.setField(controller, "imageReclaimService", reclaim);
        String theirs = "rapid_stylers/profile/" + ownership.folderFor("SIGNUP_UPLOAD", "someone@example.com") + "/face";
        String mine = "rapid_stylers/profile/" + ownership.folderFor("SIGNUP_UPLOAD", "chloe@example.com") + "/face";
        when(reclaim.reclaimPublicIdIfUnreferenced(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        signedInAs("chloe@example.com", "SIGNUP_UPLOAD");
        assertEquals(ERROR_STATUS_CODE, controller.deleteCloudinaryImage(Map.of("publicId", theirs)).getBody().getStatusCode());
        assertEquals(SUCCESS_STATUS_CODE, controller.deleteCloudinaryImage(Map.of("publicId", mine)).getBody().getStatusCode());
        org.mockito.Mockito.verify(reclaim, org.mockito.Mockito.never()).reclaimPublicIdIfUnreferenced(theirs);

        signedInAs("ADMIN1", "ADMIN");
        assertEquals(SUCCESS_STATUS_CODE, controller.deleteCloudinaryImage(Map.of("publicId", theirs)).getBody().getStatusCode());
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

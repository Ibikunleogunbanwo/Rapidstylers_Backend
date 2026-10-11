package com.macrotel.rapidstylers.media.controller;

import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.media.service.ImageReclaimService;
import com.macrotel.rapidstylers.media.service.UploadOwnership;
import com.macrotel.rapidstylers.security.access.Roles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.macrotel.rapidstylers.config.AppConstants.ERROR_STATUS_CODE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * Issues Cloudinary signed-upload credentials so the frontend can upload
 * images DIRECTLY to Cloudinary (never proxying bytes through this server).
 *
 * The client then POSTs multipart — the `params` map below verbatim, plus the
 * file — to https://api.cloudinary.com/v1_1/{cloudName}/image/upload and
 * receives the secure CDN URL, which it saves on the entity.
 *
 * `params` is the signed parameter set: it carries the folder and the allowed
 * formats, so the client cannot widen either without breaking the signature.
 * The size ceiling is returned separately (`maxFileSize`) for the client to
 * check before uploading: Cloudinary's upload API has no max_file_size
 * parameter, so signing one made every upload fail with "Invalid Signature".
 *
 * Accepts an optional folderPrefix to partition uploads:
 *   ?folderPrefix=profile   → rapid_stylers/profile
 *   ?folderPrefix=id        → rapid_stylers/id
 *   ?folderPrefix=store     → rapid_stylers/store
 *   (no prefix)             → rapid_stylers
 *
 * Inside that folder each caller gets their own (see UploadOwnership), so
 * rapid_stylers/profile/signup_3f9a.../ holds only one sign-up's photos.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class CloudinaryController {


    @Value("${cloudinary.cloud-name:}")
    private String cloudName;

    @Value("${cloudinary.api-key:}")
    private String apiKey;

    @Value("${cloudinary.api-secret:}")
    private String apiSecret;

    @Value("${cloudinary.allowed-folder-prefixes:profile,id,store,portfolio}")
    private String allowedFolderPrefixes;

    @Value("${cloudinary.allowed-formats:jpg,jpeg,png,webp,gif}")
    private String allowedFormats;

    @Value("${cloudinary.max-file-size-bytes:5242880}")
    private long maxFileSizeBytes;

    /**
     * Cloudinary's upload API has no file-size parameter, so the size the
     * client checks (maxFileSize) can be bypassed. This signed "incoming
     * transformation" is the server-side bound: whatever is sent, Cloudinary
     * stores at most 2000x2000 pixels, so a stored image stays a few MB at
     * most. Blank turns it off.
     */
    @Value("${cloudinary.incoming-transformation:c_limit,h_2000,w_2000}")
    private String incomingTransformation = "c_limit,h_2000,w_2000";

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private ImageReclaimService imageReclaimService;

    @Autowired(required = false)
    private UploadOwnership uploadOwnership;

    @GetMapping("/get_upload_signature")
    public ResponseEntity<BaseResponse> getUploadSignature(
            @RequestParam(value = "folderPrefix", required = false) String folderPrefix) {
        BaseResponse response = new BaseResponse();
        try {
            String rateLimitKey = "cloudinary-signature:" + rateLimiterService.clientIp();
            if (rateLimiterService != null && rateLimiterService.isBlocked(rateLimitKey, 900, 30)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Too many upload signature requests. Please try again later.");
                response.setData(Collections.emptyList());
                return ApiResponses.respond(response);
            }

            long timestamp = Instant.now().getEpochSecond();
            String baseFolder = "rapid_stylers";
            String sanitizedPrefix = sanitizeFolderPrefix(folderPrefix);
            if (!sanitizedPrefix.isBlank() && !allowedPrefixes().contains(sanitizedPrefix)) {
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Upload folder is not allowed");
                response.setData(Collections.emptyList());
                return ApiResponses.respond(response);
            }
            if (rateLimiterService != null) {
                rateLimiterService.record(rateLimitKey, 900);
            }
            String folder = sanitizedPrefix.isBlank() ? baseFolder : baseFolder + "/" + sanitizedPrefix;
            String[] caller = caller();
            String ownerFolder = uploadOwnership == null || caller == null ? null
                    : uploadOwnership.folderFor(caller[1], caller[0]);
            if (ownerFolder != null) {
                folder = folder + "/" + ownerFolder;
            }

            // The format and folder constraints are part of the SIGNED
            // parameter set, so Cloudinary enforces them itself: a client that
            // drops or loosens them fails signature verification. Only
            // parameters the upload API accepts may be signed; Cloudinary drops
            // unknown ones before checking, so signing one breaks every upload. Until this change the only
            // enforcement lived in a Cloudinary console upload preset, which is
            // invisible from this repository — a check that silently "passes"
            // review because nothing here can see it (audit check 16).
            String formats = normalizedFormats();

            // Cloudinary signs the exact params sent with the upload, sorted
            // alphabetically, with the API secret appended, then SHA-1 hashed.
            String transformation = incomingTransformation == null ? "" : incomingTransformation.trim();
            String toSign = "allowed_formats=" + formats
                    + "&folder=" + folder
                    + "&timestamp=" + timestamp
                    + (transformation.isEmpty() ? "" : "&transformation=" + transformation)
                    + apiSecret;
            String signature = sha1(toSign);

            // Returned ready to send as multipart fields. The client forwards
            // this map verbatim, so a constraint added here cannot drift from
            // what was signed.
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("api_key", apiKey);
            params.put("timestamp", String.valueOf(timestamp));
            params.put("folder", folder);
            params.put("allowed_formats", formats);
            if (!transformation.isEmpty()) {
                params.put("transformation", transformation);
            }
            params.put("signature", signature);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("cloudName", cloudName);
            data.put("params", params);
            data.put("allowedFormats", formats);
            // Not signed (see above): the client checks it before uploading.
            data.put("maxFileSize", String.valueOf(maxFileSizeBytes));

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Successful");
            response.setData(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new BaseResponse(true));
        }
        return ApiResponses.respond(response);
    }

    private String sanitizeFolderPrefix(String folderPrefix) {
        if (folderPrefix == null || folderPrefix.isBlank()) {
            return "";
        }
        return folderPrefix.replaceAll("[^a-zA-Z0-9_-]", "");
    }

    private Set<String> allowedPrefixes() {
        return Arrays.stream(allowedFolderPrefixes.split(","))
                .map(String::trim)
                .filter(prefix -> !prefix.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * Cloudinary expects a space-free, comma-separated format list, and the
     * value must be byte-identical to what the client sends or the signature
     * will not verify — so it is normalised once, here, and both signed and
     * returned from this single result.
     */
    private String normalizedFormats() {
        String formats = Arrays.stream(allowedFormats.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(format -> !format.isBlank())
                .collect(Collectors.joining(","));
        return formats.isBlank() ? "jpg,jpeg,png,webp,gif" : formats;
    }

    /**
     * Deletes an uploaded image that nothing uses. The frontend calls it to clean
     * up photos uploaded for a sign-up or portfolio save that then failed. It
     * only ever removes an orphan: an image any profile, ID, portfolio, blog or
     * service still points at is kept. And only the caller's own uploads: an
     * admin may delete any orphan, everyone else only files in their own folder.
     */
    @PostMapping("/delete_cloudinary_image")
    public ResponseEntity<BaseResponse> deleteCloudinaryImage(@RequestBody Map<String, String> requestBody) {
        String publicId = requestBody.getOrDefault("publicId", "");
        BaseResponse response = new BaseResponse();
        String[] caller = caller();
        boolean admin = caller != null && Roles.ADMIN.equals(caller[1]);
        if (!admin && uploadOwnership != null
                && (caller == null || !uploadOwnership.owns(caller[1], caller[0], publicId))) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Image was not deleted: it is not one of your uploads.");
            response.setData(Collections.emptyList());
            return ApiResponses.respond(response);
        }
        if (imageReclaimService.reclaimPublicIdIfUnreferenced(publicId)) {
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Image deleted");
        } else {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Image was not deleted: it is still in use or already gone.");
        }
        response.setData(Collections.emptyList());
        return ApiResponses.respond(response);
    }

    /** The signed-in caller as {subject, role}, or null outside a request with a token. */
    private static String[] caller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                return new String[]{auth.getName(), name.substring(5)};
            }
        }
        return null;
    }

    private String sha1(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

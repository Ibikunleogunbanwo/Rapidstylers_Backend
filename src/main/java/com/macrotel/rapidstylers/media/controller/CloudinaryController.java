package com.macrotel.rapidstylers.media.controller;

import com.macrotel.rapidstylers.controller.ApiResponses;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.media.service.ImageReclaimService;
import com.macrotel.rapidstylers.service.RateLimiterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * `params` is the signed parameter set: it carries the folder, the allowed
 * formats and the size ceiling, so the client cannot widen any of them without
 * breaking the signature.
 *
 * Accepts an optional folderPrefix to partition uploads:
 *   ?folderPrefix=profile   → rapid_stylers/profile
 *   ?folderPrefix=id        → rapid_stylers/id
 *   ?folderPrefix=store     → rapid_stylers/store
 *   (no prefix)             → rapid_stylers
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

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private ImageReclaimService imageReclaimService;

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

            // The upload constraints are part of the SIGNED parameter set, so
            // Cloudinary enforces them itself: a client that drops them,
            // loosens them, or sends a disallowed type/size fails signature
            // verification or is rejected outright. Until this change the only
            // enforcement lived in a Cloudinary console upload preset, which is
            // invisible from this repository — a check that silently "passes"
            // review because nothing here can see it (audit check 16).
            String formats = normalizedFormats();

            // Cloudinary signs the exact params sent with the upload, sorted
            // alphabetically, with the API secret appended, then SHA-1 hashed.
            String toSign = "allowed_formats=" + formats
                    + "&folder=" + folder
                    + "&max_file_size=" + maxFileSizeBytes
                    + "&timestamp=" + timestamp + apiSecret;
            String signature = sha1(toSign);

            // Returned ready to send as multipart fields. The client forwards
            // this map verbatim, so a constraint added here cannot drift from
            // what was signed.
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("api_key", apiKey);
            params.put("timestamp", String.valueOf(timestamp));
            params.put("folder", folder);
            params.put("allowed_formats", formats);
            params.put("max_file_size", String.valueOf(maxFileSizeBytes));
            params.put("signature", signature);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("cloudName", cloudName);
            data.put("params", params);
            data.put("allowedFormats", formats);
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
     * service still points at is kept, so nobody can delete someone else's photo
     * by sending its public id.
     */
    @PostMapping("/delete_cloudinary_image")
    public ResponseEntity<BaseResponse> deleteCloudinaryImage(@RequestBody Map<String, String> requestBody) {
        String publicId = requestBody.getOrDefault("publicId", "");
        BaseResponse response = new BaseResponse();
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

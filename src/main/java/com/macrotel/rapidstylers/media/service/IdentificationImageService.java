package com.macrotel.rapidstylers.media.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_MESSAGE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * Hands an admin a short-lived link to a stylist's government ID photo.
 *
 * ID photos are private Cloudinary assets (type=authenticated), so the URL
 * stored on the stylist does not open them for anyone. An admin reviewing a
 * stylist asks this service for a link that works for ten minutes. Photos
 * uploaded before ID uploads became private are public assets; they get the
 * same kind of expiring link, so the admin screen treats both alike.
 */
@Service
public class IdentificationImageService {

    private static final Logger LOG = Logger.getLogger(IdentificationImageService.class.getName());

    /** How long a link stays usable. */
    static final long LINK_LIFETIME_SECONDS = 600;

    // Package-private so a unit test can hand in stand-ins without a Spring context.
    @Autowired
    CloudinaryService cloudinaryService;

    @Autowired
    StylerRecords stylerRecords;

    @Autowired
    AuditService auditService = new AuditService();

    /** {url, expiresAt} for the stylist's ID photo, or an error answer. */
    public BaseResponse signedIdentificationLink(String stylerId, String adminId) {
        BaseResponse response = new BaseResponse(true);
        try {
            if (stylerId == null || stylerId.isBlank()) {
                return Responses.errorResponse(response, "Stylist id is required");
            }
            Optional<StylerEntity> styler = stylerRecords.findByStylerId(stylerId.trim());
            if (styler.isEmpty()) {
                return Responses.errorResponse(response, "Invalid Styler Id");
            }
            String stored = styler.get().getIdentificationImageUrl();
            String publicId = cloudinaryService.extractPublicId(stored);
            if (publicId == null) {
                return Responses.errorResponse(response, "This stylist has no identification image on file");
            }
            long expiresAt = Instant.now().getEpochSecond() + LINK_LIFETIME_SECONDS;
            String url = cloudinaryService.privateDownloadUrl(publicId, CloudinaryService.fileFormat(stored),
                    cloudinaryService.deliveryType(stored), expiresAt);
            if (url == null) {
                return Responses.errorResponse(response, "Image storage is not configured");
            }
            auditService.audit(adminId, "ADMIN", "ADMIN_VIEW_IDENTIFICATION", "STYLER", styler.get().getStylerId(),
                    "Signed identification link issued");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", url);
            data.put("expiresAt", Instant.ofEpochSecond(expiresAt).toString());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch (Exception ex) {
            LOG.warning("Identification link failed: " + ex.getMessage());
        }
        return response;
    }
}

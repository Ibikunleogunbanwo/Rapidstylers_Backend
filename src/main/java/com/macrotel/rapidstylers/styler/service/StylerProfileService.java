package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.catalog.service.CatalogRecords;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.media.service.CloudinaryService;
import com.macrotel.rapidstylers.media.service.ImageReclaimService;
import com.macrotel.rapidstylers.media.service.UploadOwnership;
import com.macrotel.rapidstylers.security.access.Roles;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.pojo.StylerBusinessInfoData;
import com.macrotel.rapidstylers.styler.pojo.StylerPersonalInfoData;
import com.macrotel.rapidstylers.styler.pojo.StylerPhotosData;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_MESSAGE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * A signed-in stylist reading and editing their own profile: personal details,
 * business details (with the account's time zone) and photos.
 *
 * <p>The stylist is always the one in the token, never an id in the request,
 * so nobody can edit someone else's profile. Edits apply at once and are
 * written to the audit log; none of them sends an approved stylist back to
 * review. The email address and the ID document are not editable here.
 */
@Service
public class StylerProfileService {

    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    CustomerRecords customerRecords;
    @Autowired
    CatalogRecords catalogRecords;
    @Autowired
    GeocodingService geocodingService;
    @Autowired
    GoogleTimezoneService googleTimezoneService;
    @Autowired(required = false)
    LocationCacheService locationCacheService;
    @Autowired(required = false)
    ReadCacheService readCacheService;
    @Autowired
    ImageReclaimService imageReclaimService;
    @Autowired
    CloudinaryService cloudinaryService;
    @Autowired
    UploadOwnership uploadOwnership = new UploadOwnership();
    @Autowired
    AuditService auditService;
    /** Security notices by email; optional so hand-built tests need no outbox. */
    @Autowired(required = false)
    OutboxEventService outboxEventService;

    /** The stylist's own editable profile. */
    public BaseResponse profile(String stylerId) {
        Optional<StylerEntity> found = stylerRepo.findByStylerId(stylerId);
        if (found.isEmpty()) {
            return Responses.errorResponse(new BaseResponse(true), "Invalid Styler Id");
        }
        return ok(found.get(), SUCCESS_MESSAGE);
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse updatePersonalInfo(String stylerId, StylerPersonalInfoData data) {
        BaseResponse response = new BaseResponse(true);
        Optional<StylerEntity> found = stylerRepo.findByStylerId(stylerId);
        if (found.isEmpty()) {
            return Responses.errorResponse(response, "Invalid Styler Id");
        }
        StylerEntity styler = found.get();
        String phone = data.getPhoneNumber().trim();
        if (!phone.equals(styler.getPhoneNumber())
                && (stylerRepo.findByPhoneNumber(phone).filter(other -> !stylerId.equals(other.getStylerId())).isPresent()
                || customerRecords.findByPhoneNumber(phone).isPresent())) {
            return Responses.errorResponse(response, "Phone number already registered");
        }
        boolean nameChanged = !Objects.equals(data.getFirstname().trim(), styler.getFirstname())
                || !Objects.equals(data.getLastname().trim(), styler.getLastname());
        boolean phoneChanged = !phone.equals(styler.getPhoneNumber());
        styler.setFirstname(data.getFirstname().trim());
        styler.setLastname(data.getLastname().trim());
        styler.setPhoneNumber(phone);
        stylerRepo.save(styler);
        saved(styler, "STYLER_PERSONAL_INFO_UPDATED", "Name or phone number changed");
        if (phoneChanged || nameChanged) {
            notice(styler, "Your personal details were updated",
                    phoneChanged && nameChanged ? "The name and phone number on your RapidStylers account were changed."
                            : phoneChanged ? "The phone number on your RapidStylers account was changed to " + phone + "."
                            : "The name on your RapidStylers account was changed.");
        }
        return ok(styler, "Personal information saved");
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse updateBusinessInfo(String stylerId, StylerBusinessInfoData data) {
        BaseResponse response = new BaseResponse(true);
        Optional<StylerEntity> found = stylerRepo.findByStylerId(stylerId);
        if (found.isEmpty()) {
            return Responses.errorResponse(response, "Invalid Styler Id");
        }
        StylerEntity styler = found.get();
        if (!serviceTypeExists(data.getServiceTypeId())) {
            return Responses.errorResponse(response, "Invalid Service Type, Contact Admin");
        }
        String chosenZone = blankToNull(data.getTimeZone());
        if (chosenZone != null && !isRegionZone(chosenZone)) {
            return Responses.errorResponse(response, "Time zone is not valid. Use a place name such as America/Edmonton.");
        }
        String street = data.getStreetAddress().trim();
        String unit = blankToNull(data.getUnit());
        String city = data.getCity().trim();
        String province = data.getProvince().trim();
        String postalCode = blankToNull(data.getPostalCode());
        String country = data.getCountry() == null || data.getCountry().isBlank() ? styler.getCountry() : data.getCountry().trim();
        boolean moved = !Objects.equals(street, styler.getStreetAddress()) || !Objects.equals(unit, blankToNull(styler.getUnit()))
                || !Objects.equals(city, styler.getCity()) || !Objects.equals(province, styler.getProvince())
                || !Objects.equals(postalCode, blankToNull(styler.getPostalCode())) || !Objects.equals(country, styler.getCountry());
        String oneLine = oneLineAddress(street, unit, city, province, postalCode, country);
        Map<String, Object> geo = null;
        if (moved) {
            geo = geocodingService.geocode(oneLine);
            if (geo == null || geo.get("latitude") == null || geo.get("longitude") == null) {
                return Responses.errorResponse(response, "We could not find that address. Check it and try again.");
            }
        }

        boolean renamed = !Objects.equals(AppUtils.sanitizeText(data.getBusinessName().trim()), styler.getBusinessName());
        styler.setBusinessName(AppUtils.sanitizeText(data.getBusinessName().trim()));
        styler.setDescription(data.getDescription() == null ? null : AppUtils.sanitizeText(data.getDescription().trim()));
        styler.setServiceTypeId(data.getServiceTypeId().trim());
        styler.setStreetAddress(street);
        styler.setUnit(unit);
        styler.setCity(city);
        styler.setProvince(province);
        styler.setPostalCode(postalCode);
        styler.setCountry(country);
        styler.setBusinessAddress(oneLine);
        if (geo != null) {
            styler.setLatitude((Double) geo.get("latitude"));
            styler.setLongitude((Double) geo.get("longitude"));
        }
        if (chosenZone != null) {
            styler.setTimeZone(chosenZone);
            styler.setTimeZoneSource(VendorZoneResolver.SOURCE_STYLIST);
        } else if (moved && !VendorZoneResolver.SOURCE_STYLIST.equals(styler.getTimeZoneSource())) {
            String derived = googleTimezoneService.timeZoneId(styler.getLatitude(), styler.getLongitude());
            styler.setTimeZone(derived != null ? derived : VendorZoneResolver.zoneForProvince(province).getId());
            styler.setTimeZoneSource(derived != null ? VendorZoneResolver.SOURCE_GOOGLE : VendorZoneResolver.SOURCE_PROVINCE);
        }
        stylerRepo.save(styler);
        if (moved && locationCacheService != null) {
            locationCacheService.indexStyler(styler.getStylerId(), styler.getLongitude(), styler.getLatitude());
        }
        saved(styler, "STYLER_BUSINESS_INFO_UPDATED", moved ? "Business details and address changed" : "Business details changed");
        if (moved || renamed) {
            notice(styler, "Your business details were updated",
                    moved && renamed ? "Your business name and address were changed. Clients now see " + oneLine + "."
                            : moved ? "Your business address was changed. Clients now see " + oneLine + "."
                            : "Your business name was changed to " + styler.getBusinessName() + ".");
        }
        return ok(styler, "Business information saved");
    }

    /**
     * Replaces the profile and/or cover photo. Each must be this stylist's own
     * upload; the photo it replaces is deleted once nothing else uses it.
     */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse updatePhotos(String stylerId, StylerPhotosData data) {
        BaseResponse response = new BaseResponse(true);
        Optional<StylerEntity> found = stylerRepo.findByStylerId(stylerId);
        if (found.isEmpty()) {
            return Responses.errorResponse(response, "Invalid Styler Id");
        }
        String profile = blankToNull(data.getProfileImageUrl());
        String cover = blankToNull(data.getCoverImageUrl());
        if (profile == null && cover == null) {
            return Responses.errorResponse(response, "Choose a photo to upload");
        }
        if ((profile != null && !ownUpload(stylerId, profile)) || (cover != null && !ownUpload(stylerId, cover))) {
            return Responses.errorResponse(response, "Upload the photo again from this page, then save.");
        }
        StylerEntity styler = found.get();
        String oldProfile = styler.getProfileImageUrl();
        String oldCover = styler.getCoverImageUrl();
        if (profile != null) {
            styler.setProfileImageUrl(profile);
        }
        if (cover != null) {
            styler.setCoverImageUrl(cover);
        }
        stylerRepo.save(styler);
        if (profile != null && !profile.equals(oldProfile)) {
            imageReclaimService.reclaimIfUnreferenced(oldProfile);
        }
        if (cover != null && !cover.equals(oldCover)) {
            imageReclaimService.reclaimIfUnreferenced(oldCover);
        }
        saved(styler, "STYLER_PHOTOS_UPDATED", (profile != null ? "Profile photo" : "") + (profile != null && cover != null ? " and " : "")
                + (cover != null ? "cover photo" : "") + " changed");
        return ok(styler, "Photos saved");
    }

    private boolean ownUpload(String stylerId, String url) {
        String publicId = cloudinaryService.extractPublicId(url.trim());
        return publicId != null && uploadOwnership.owns(Roles.STYLER, stylerId, publicId);
    }

    private boolean serviceTypeExists(String serviceTypeId) {
        try {
            return catalogRecords.findServiceById(Long.parseLong(serviceTypeId.trim())).isPresent();
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** A real place-based zone such as America/Edmonton, not an offset like +05:00. */
    static boolean isRegionZone(String zone) {
        try {
            return zone.contains("/") && ZoneId.of(zone) != null;
        } catch (Exception ex) {
            return false;
        }
    }

    private void notice(StylerEntity styler, String headline, String detail) {
        if (outboxEventService == null || styler.getEmailAddress() == null || styler.getEmailAddress().isBlank()) return;
        outboxEventService.accountNotice(styler.getStylerId(), styler.getEmailAddress(), styler.getFirstname(), headline, detail);
    }

    private void saved(StylerEntity styler, String action, String detail) {
        if (readCacheService != null) {
            readCacheService.evict(ReadCacheService.KEY_STYLER_DTO + styler.getStylerId());
        }
        auditService.audit(styler.getStylerId(), "STYLER", action, "STYLER", styler.getStylerId(), detail);
    }

    private BaseResponse ok(StylerEntity styler, String message) {
        BaseResponse response = new BaseResponse(true);
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(message);
        response.setData(view(styler));
        return response;
    }

    /** What the edit screens show: everything a stylist can change, plus what they can't, read-only. */
    static Map<String, Object> view(StylerEntity styler) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stylerId", styler.getStylerId());
        data.put("firstname", styler.getFirstname());
        data.put("lastname", styler.getLastname());
        data.put("emailAddress", styler.getEmailAddress());
        data.put("phoneNumber", styler.getPhoneNumber());
        data.put("businessName", styler.getBusinessName());
        data.put("description", styler.getDescription());
        data.put("serviceTypeId", styler.getServiceTypeId());
        data.put("streetAddress", styler.getStreetAddress());
        data.put("unit", styler.getUnit());
        data.put("city", styler.getCity());
        data.put("province", styler.getProvince());
        data.put("postalCode", styler.getPostalCode());
        data.put("country", styler.getCountry());
        data.put("businessAddress", styler.getBusinessAddress());
        data.put("timeZone", styler.getTimeZone());
        data.put("timeZoneChosenByStylist", VendorZoneResolver.SOURCE_STYLIST.equals(styler.getTimeZoneSource()));
        data.put("profileImageUrl", styler.getProfileImageUrl());
        data.put("coverImageUrl", styler.getCoverImageUrl());
        data.put("verificationStatus", styler.getVerificationStatus());
        return data;
    }

    private static String oneLineAddress(String street, String unit, String city, String province, String postalCode, String country) {
        StringBuilder sb = new StringBuilder(street);
        if (unit != null) sb.append(" ").append(unit);
        sb.append(", ").append(city).append(", ").append(province);
        if (postalCode != null) sb.append(" ").append(postalCode);
        if (country != null && !country.isBlank()) sb.append(", ").append(country);
        return sb.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

package com.macrotel.rapidstylers.dto;

import lombok.Data;

@Data
public class StylerAccountDTO {
    private String firstname;
    private String lastname;
    private String emailAddress;
    private String stylerId;
    private String phoneNumber;
    private String serviceTypeId;
    private String serviceTypeName;
    private String visibilityStatus;
    private String accountStatus;
    private String profileImageUrl;
    private String businessName;
    private String businessAddress;
    private String province;
    // IANA zone the vendor's weekly hours live in (null = app default).
    private String timeZone;
    private String description;
    // Structured Canadian address
    private String streetAddress;
    private String unit;
    private String city;
    private String postalCode;
    private String country;
    private Double latitude;
    private Double longitude;
    private Double distanceKm;
    private Double includedTravelKm;
    private String baseTravelFee;
    // true when the stylist can receive payouts (Connect onboarding COMPLETE,
    // or payments are not configured at all). Drives the marketplace "payments
    // pending" flag and the booking block.
    private Boolean payoutReady;

    // Review aggregates (computed at DTO-build time) so list cards show real ratings.
    private Double averageRating;
    private Long reviewCount;
    // When the account was created (yyyy-MM-dd). A profile with no reviews needs
    // it to tell a genuinely new professional from an established one who has
    // simply never been reviewed — without it the only honest option is to claim
    // nothing, and every review-less profile reads as "just joined".
    private String dateRegistered;
    // Professional verification: PENDING / APPROVED / REJECTED / SUSPENDED
    private String verificationStatus;

    /**
     * Weekly trading hours and the blocked dates still ahead of them, as
     * [{dayOfWeek, startTime, endTime}] and [{blockedDate, reason}].
     *
     * Sent on list and search rows (featured, saved, category, province, city)
     * so a card can say whether the professional is open right now, read on the
     * professional's own clock, instead of falling back to a bare presence
     * badge. The same two lists already ride along on the profile payload under
     * these names, so the client reads one shape everywhere.
     *
     * These are populated only by `AppService.listRowWithHours`, never inside the
     * cached DTO build. The DTO is cached for minutes and shared by every
     * surface, so baking hours into it would both freeze them there and hand the
     * same instance to callers who must not modify it.
     */
    private java.util.List<Object> availability;
    private java.util.List<Object> exceptions;
}

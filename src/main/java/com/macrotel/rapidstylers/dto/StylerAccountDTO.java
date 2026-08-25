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
    private String extraTravelRatePerKm;
    private Double maxServiceDistanceKm;
    // true when the stylist can receive payouts (Connect onboarding COMPLETE,
    // or payments are not configured at all). Drives the marketplace "payments
    // pending" flag and the booking block.
    private Boolean payoutReady;

    // Review aggregates (computed at DTO-build time) so list cards show real ratings.
    private Double averageRating;
    private Long reviewCount;
    // Professional verification: PENDING / APPROVED / REJECTED / SUSPENDED
    private String verificationStatus;
}

package com.macrotel.rapidstylers.entity;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.pojo.StylerData;
import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "stylers")
public class StylerEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String firstname;
    private String lastname;
    private String emailAddress;
    private String password;
    private String phoneNumber;
    private String address;
    private String identificationId;
    private String businessName;
    private String serviceTypeId;
    private String country;
    private String province;
    private String businessAddress;
    // Structured Canadian address (new registrations)
    private String streetAddress;
    private String unit;
    private String city;
    private String postalCode;
    private Double latitude;
    private Double longitude;
    private String profileImageUrl;
    private String identificationImageUrl;
    private String isOnline;
    private String insertedDt;
    private String status;
    private String stylerId;
    private String description;
    private Double includedTravelKm;
    private String extraTravelRatePerKm;
    private Double maxServiceDistanceKm;
    // Professional verification workflow: PENDING / APPROVED / REJECTED / SUSPENDED.
    // New registrations start PENDING and only APPROVED stylers are visible in
    // public search and bookable. Distinct from `status` (active/inactive account).
    private String verificationStatus;

    // Stripe Connect payouts: the connected Express account id and its onboarding
    // status (NOT_STARTED / PENDING / COMPLETE). Only COMPLETE stylers receive
    // transfers when payments are captured.
    private String stripeConnectAccountId;
    private String connectOnboardingStatus;
    private String connectDisabledReason;

    public StylerEntity() {
    }
    public StylerEntity(StylerData stylerData) {
        AppUtils appUtils = new AppUtils();
        this.firstname = stylerData.getFirstname();
        this.lastname = stylerData.getLastname();
        this.emailAddress = stylerData.getEmailAddress();
        this.password = appUtils.encryptPassword(stylerData.getPassword());
        this.address = stylerData.getAddress();
        this.identificationId = stylerData.getIdentificationTypeId();
        this.businessName = stylerData.getBusinessName();
        this.serviceTypeId = stylerData.getServiceTypeId();
        this.country = stylerData.getCountry();
        this.province = stylerData.getBusinessProvince();
        this.businessAddress = stylerData.getBusinessAddress();
        this.streetAddress = stylerData.getStreetAddress();
        this.unit = stylerData.getUnit();
        this.city = stylerData.getCity();
        this.postalCode = stylerData.getPostalCode();
        this.latitude = stylerData.getLatitude();
        this.longitude = stylerData.getLongitude();
        this.profileImageUrl = stylerData.getProfileImageUrl();
        this.identificationImageUrl = stylerData.getIdentificationImageUrl();
        this.insertedDt = String.valueOf(LocalDate.now());
        this.phoneNumber = stylerData.getPhoneNumber();
        this.stylerId = stylerData.getFirstname().toUpperCase().charAt(0)+""+stylerData.getLastname().toUpperCase().charAt(0)+appUtils.randomDigit(4);
        this.status = "0";
        this.isOnline = "1";
        this.verificationStatus = "PENDING";
        this.includedTravelKm = stylerData.getIncludedTravelKm() == null ? 15.0 : stylerData.getIncludedTravelKm();
        this.extraTravelRatePerKm = stylerData.getExtraTravelRatePerKm() == null ? "0.00" : stylerData.getExtraTravelRatePerKm();
        this.maxServiceDistanceKm = stylerData.getMaxServiceDistanceKm();
    }
}

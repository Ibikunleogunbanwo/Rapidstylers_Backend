package com.macrotel.rapidstylers.styler.pojo;

import com.macrotel.rapidstylers.config.InputFields;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import lombok.Data;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@Data
public class StylerData {
    @NotEmpty(message = "Firstname cannot be empty")
    @Pattern(regexp = PERSON_NAME_REGEX, message = "First " + PERSON_NAME_MESSAGE)
    private String firstname;
    @NotEmpty(message = "Lastname cannot be empty")
    @Pattern(regexp = PERSON_NAME_REGEX, message = "Last " + PERSON_NAME_MESSAGE)
    private String lastname;
    @NotEmpty(message = "Email Address cannot be empty")
    @Email(message = "Enter a valid email address")
    private String emailAddress;
    @NotEmpty(message = "Country cannot be empty")
    @Pattern(regexp = CHARACTER_VALIDATION_REGEX, message = "Country must be at least 3 characters")
    private String country;
    @NotEmpty(message = "State cannot be empty")
    private String state;
    @NotEmpty(message = "Address cannot be empty")
    private String address;
    @NotEmpty(message = "PhoneNumber cannot be empty")
    @Pattern(regexp = PHONE_NUMBER_VALIDATION_REGEX, message = PHONE_NUMBER_MESSAGE)
    private String phoneNumber;
    @NotEmpty(message = "Password cannot be empty")
    @Pattern(regexp = PASSWORD_PATTERN, message = "Password must be at least 8 characters with 1 uppercase, 1 lowercase, 1 digit, and 1 special character")
    private String password;
    @NotEmpty(message = "Valid ID Type cannot be empty")
    private String identificationTypeId;
    @NotEmpty(message = "Identification Image cannot be empty")
    private String identificationImageUrl;
    @NotEmpty(message = "Profile Image cannot be empty")
    private String profileImageUrl;
    @NotEmpty(message = "Business Name cannot be empty")
    private String businessName;
    @NotEmpty(message = "Service Type Id cannot be empty")
    private String serviceTypeId;
    @NotEmpty(message = "Business Address cannot be empty")
    private String businessAddress;
    @NotEmpty(message = "Business Province cannot be empty")
    @Pattern(regexp = PROVINCE_REGEX, message = PROVINCE_MESSAGE)
    private String businessProvince;
    private String description;
    // Structured Canadian address (new fields)
    private String streetAddress;
    private String unit;
    @Pattern(regexp = CITY_REGEX, message = CITY_MESSAGE)
    private String city;
    @Pattern(regexp = POSTAL_CODE_REGEX, message = POSTAL_CODE_MESSAGE)
    private String postalCode;
    private Double latitude;
    private Double longitude;
    private Double includedTravelKm;
    private String baseTravelFee;
    @javax.validation.constraints.AssertTrue(message = "You must agree to the Terms and Conditions")
    private boolean agreeToTerms;

    // Trimmed (and the phone stripped of formatting) before the rules above run.
    public void setFirstname(String firstname) { this.firstname = InputFields.trim(firstname); }
    public void setLastname(String lastname) { this.lastname = InputFields.trim(lastname); }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = InputFields.phone(phoneNumber); }
    public void setBusinessProvince(String businessProvince) { this.businessProvince = InputFields.province(businessProvince); }
    // City and postal code are optional here: blank means not given.
    public void setCity(String city) { this.city = blankToNull(city); }
    public void setPostalCode(String postalCode) { this.postalCode = blankToNull(postalCode); }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

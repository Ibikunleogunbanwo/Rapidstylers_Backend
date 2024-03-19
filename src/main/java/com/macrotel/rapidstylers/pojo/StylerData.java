package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@Data
public class StylerData {
    @NotEmpty(message = "Firstname cannot be empty")
    @Pattern(regexp = CHARACTER_VALIDATION_REGEX, message = "Firstname must be at least 3 characters")
    private String firstname;
    @NotEmpty(message = "Lastname cannot be empty")
    @Pattern(regexp = CHARACTER_VALIDATION_REGEX, message = "Lastname must be at least 3 characters")
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
    @Pattern(regexp = NUMBER_VALIDATION_REGEX, message = "Phone Number can only be digit")
    private String phoneNumber;
    @NotEmpty(message = "Password cannot be empty")
    @Pattern(regexp = PASSWORD_PATTERN, message = "Password should contain 1 uppercase, 1 lowercase, 1 number, 1 special character and 6 character length")
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
    private String businessProvince;

}

package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@Data
public class UserData {
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
    @Pattern(regexp = PHONE_NUMBER_VALIDATION_REGEX, message = "Enter a valid Canadian phone number (10 digits, or 11 with leading 1)")
    private String phoneNumber;
    @NotEmpty(message = "Password cannot be empty")
    @Pattern(regexp = PASSWORD_PATTERN, message = "Password must be at least 8 characters with 1 uppercase, 1 lowercase, 1 digit, and 1 special character")
    private String password;

}

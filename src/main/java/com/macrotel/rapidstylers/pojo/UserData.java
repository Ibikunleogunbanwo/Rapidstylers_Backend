package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@Data
public class UserData {
    private String firstname;
    private String lastname;
    @NotEmpty(message = "Email Address cannot be empty")
    @Email(message = "Enter a valid email address")
    private String emailAddress;
    private String country;
    private String state;
    private String address;
    private String phoneNumber;
    @NotEmpty(message = "Password cannot be empty")
    @Pattern(regexp = PASSWORD_PATTERN, message = "Password must be at least 8 characters with 1 uppercase, 1 lowercase, 1 digit, and 1 special character")
    private String password;
    @javax.validation.constraints.AssertTrue(message = "You must agree to the Terms and Conditions")
    private boolean agreeToTerms;

}

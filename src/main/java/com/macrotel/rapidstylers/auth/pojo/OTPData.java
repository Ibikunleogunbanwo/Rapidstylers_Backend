package com.macrotel.rapidstylers.auth.pojo;

import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.Email;

@Data
public class OTPData {
    @Email(message = "Enter a valid email address")
    private String emailAddress;
    /** Stands in for the address after step 1 (see SignupTokenService). */
    private String signupToken;

    @AssertTrue(message = "Email Address cannot be empty")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmailOrToken() {
        return (emailAddress != null && !emailAddress.isBlank()) || (signupToken != null && !signupToken.isBlank());
    }
}

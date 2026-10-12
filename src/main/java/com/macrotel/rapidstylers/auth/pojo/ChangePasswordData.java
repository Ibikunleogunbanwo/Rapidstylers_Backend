package com.macrotel.rapidstylers.auth.pojo;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import lombok.Data;

import static com.macrotel.rapidstylers.config.AppConstants.PASSWORD_PATTERN;

/** A signed-in stylist changing their password. The account comes from the token, never the body. */
@Data
public class ChangePasswordData {
    @NotEmpty(message = "Old Password cannot be empty")
    private String oldPassword;
    @NotEmpty(message = "Password cannot be empty")
    @Pattern(regexp = PASSWORD_PATTERN, message = "Password must be at least 8 characters with 1 uppercase, 1 lowercase, 1 digit, and 1 special character")
    private String password;
    @NotEmpty(message = "Confirm Password cannot be empty")
    private String confirmPassword;
}

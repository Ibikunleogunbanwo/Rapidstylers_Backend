package com.macrotel.rapidstylers.styler.pojo;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import lombok.Data;

import static com.macrotel.rapidstylers.config.AppConstants.PERSON_NAME_REGEX;
import static com.macrotel.rapidstylers.config.AppConstants.PHONE_NUMBER_VALIDATION_REGEX;

/** A stylist changing their name or phone number. The email is changed elsewhere, with a code. */
@Data
public class StylerPersonalInfoData {
    @NotEmpty(message = "Firstname cannot be empty")
    @Pattern(regexp = PERSON_NAME_REGEX, message = "First name must be at least 3 characters: letters, spaces, hyphens or apostrophes")
    private String firstname;
    @NotEmpty(message = "Lastname cannot be empty")
    @Pattern(regexp = PERSON_NAME_REGEX, message = "Last name must be at least 3 characters: letters, spaces, hyphens or apostrophes")
    private String lastname;
    @NotEmpty(message = "PhoneNumber cannot be empty")
    @Pattern(regexp = PHONE_NUMBER_VALIDATION_REGEX, message = "Enter a valid Canadian phone number (10 digits, or 11 with leading 1)")
    private String phoneNumber;
}

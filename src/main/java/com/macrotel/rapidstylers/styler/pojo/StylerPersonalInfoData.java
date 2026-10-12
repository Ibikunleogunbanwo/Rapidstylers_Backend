package com.macrotel.rapidstylers.styler.pojo;

import com.macrotel.rapidstylers.config.InputFields;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import lombok.Data;

import static com.macrotel.rapidstylers.config.AppConstants.PERSON_NAME_MESSAGE;
import static com.macrotel.rapidstylers.config.AppConstants.PERSON_NAME_REGEX;
import static com.macrotel.rapidstylers.config.AppConstants.PHONE_NUMBER_MESSAGE;
import static com.macrotel.rapidstylers.config.AppConstants.PHONE_NUMBER_VALIDATION_REGEX;

/** A stylist changing their name or phone number. The email is changed elsewhere, with a code. */
@Data
public class StylerPersonalInfoData {
    @NotEmpty(message = "Firstname cannot be empty")
    @Pattern(regexp = PERSON_NAME_REGEX, message = "First " + PERSON_NAME_MESSAGE)
    private String firstname;
    @NotEmpty(message = "Lastname cannot be empty")
    @Pattern(regexp = PERSON_NAME_REGEX, message = "Last " + PERSON_NAME_MESSAGE)
    private String lastname;
    @NotEmpty(message = "PhoneNumber cannot be empty")
    @Pattern(regexp = PHONE_NUMBER_VALIDATION_REGEX, message = PHONE_NUMBER_MESSAGE)
    private String phoneNumber;

    // Trimmed (and the phone stripped of formatting) before the rules above run.
    public void setFirstname(String firstname) { this.firstname = InputFields.trim(firstname); }
    public void setLastname(String lastname) { this.lastname = InputFields.trim(lastname); }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = InputFields.phone(phoneNumber); }
}

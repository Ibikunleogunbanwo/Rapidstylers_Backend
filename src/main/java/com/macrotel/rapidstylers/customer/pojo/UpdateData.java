package com.macrotel.rapidstylers.customer.pojo;

import com.macrotel.rapidstylers.config.InputFields;
import javax.validation.constraints.Email;
import javax.validation.constraints.Pattern;
import lombok.Data;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@Data
public class UpdateData {
    @Pattern(regexp = BLANK_OR + PERSON_NAME_REGEX, message = "First " + PERSON_NAME_MESSAGE)
    private String firstname;
    @Pattern(regexp = BLANK_OR + PERSON_NAME_REGEX, message = "Last " + PERSON_NAME_MESSAGE)
    private String lastname;
    @Email(message = "Enter a valid email address")
    private String emailAddress;
    private String country;
    // The province.
    @Pattern(regexp = BLANK_OR + PROVINCE_REGEX, message = PROVINCE_MESSAGE)
    private String state;
    private String address;
    @Pattern(regexp = BLANK_OR + PHONE_NUMBER_VALIDATION_REGEX, message = PHONE_NUMBER_MESSAGE)
    private String phoneNumber;

    // Names, province and phone are optional, but checked when given: trimmed
    // (the phone stripped of formatting) first, and blank still means not given.
    public void setFirstname(String firstname) { this.firstname = InputFields.trim(firstname); }
    public void setLastname(String lastname) { this.lastname = InputFields.trim(lastname); }
    public void setState(String state) { this.state = InputFields.province(state); }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = InputFields.phone(phoneNumber); }
}

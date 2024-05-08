package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.Pattern;

import static com.macrotel.rapidstylers.config.AppConstants.CHARACTER_VALIDATION_REGEX;
import static com.macrotel.rapidstylers.config.AppConstants.PHONE_NUMBER_VALIDATION_REGEX;

@Data
public class UpdateData {
    private String firstname;
    private String lastname;
    @Email(message = "Enter a valid email address")
    private String emailAddress;
    private String country;
    private String state;
    private String address;
    private String phoneNumber;
}

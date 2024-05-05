package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import static com.macrotel.rapidstylers.config.AppConstants.AMOUNT_VALIDATION_REGEX;

@Data
public class SubServiceData {
    @NotEmpty(message = "StylerId cannot be empty")
    private String stylerId;
    @NotEmpty(message = "Sub Service name cannot be empty")
    private String name;
    @NotEmpty(message = "Price cannot be empty")
    @Pattern(regexp = AMOUNT_VALIDATION_REGEX , message = "Invalid amount format")
    private String price;
}

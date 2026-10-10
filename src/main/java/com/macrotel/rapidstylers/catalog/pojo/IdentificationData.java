package com.macrotel.rapidstylers.catalog.pojo;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import lombok.Data;

import static com.macrotel.rapidstylers.config.AppConstants.CHARACTER_VALIDATION_REGEX;

@Data
public class IdentificationData {
    @NotEmpty(message = "Identification name cannot be empty")
    @Pattern(regexp = CHARACTER_VALIDATION_REGEX)
    private String identificationName;
    private String id;
}

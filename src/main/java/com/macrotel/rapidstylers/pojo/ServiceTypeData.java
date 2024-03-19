package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import static com.macrotel.rapidstylers.config.AppConstants.CHARACTER_VALIDATION_REGEX;

@Data
public class ServiceTypeData {
    @NotEmpty(message = "Service name cannot be empty")
    @Pattern(regexp = CHARACTER_VALIDATION_REGEX)
    private String serviceName;
    private String id;
}

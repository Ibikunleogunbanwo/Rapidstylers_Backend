package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import static com.macrotel.rapidstylers.config.AppConstants.AMOUNT_VALIDATION_REGEX;
import static com.macrotel.rapidstylers.config.AppConstants.MAX_SERVICE_DURATION_MINUTES;
import static com.macrotel.rapidstylers.config.AppConstants.MIN_SERVICE_DURATION_MINUTES;

@Data
public class ServiceUpdateData {
    private Long id;

    @NotEmpty(message = "Sub Service name cannot be empty")
    private String name;

    @NotEmpty(message = "Price cannot be empty")
    @Pattern(regexp = AMOUNT_VALIDATION_REGEX, message = "Invalid amount format")
    private String price;

    @Min(value = MIN_SERVICE_DURATION_MINUTES, message = "Duration must be at least 15 minutes")
    @Max(value = MAX_SERVICE_DURATION_MINUTES, message = "Duration cannot exceed 480 minutes")
    private Integer durationMinutes;
}

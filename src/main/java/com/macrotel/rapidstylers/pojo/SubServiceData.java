package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import static com.macrotel.rapidstylers.config.AppConstants.AMOUNT_VALIDATION_REGEX;
import static com.macrotel.rapidstylers.config.AppConstants.DEFAULT_SERVICE_DURATION_MINUTES;
import static com.macrotel.rapidstylers.config.AppConstants.MAX_SERVICE_DURATION_MINUTES;
import static com.macrotel.rapidstylers.config.AppConstants.MIN_SERVICE_DURATION_MINUTES;

@Data
public class SubServiceData {
    // Set from the authenticated JWT by the controller; it is intentionally not client-supplied.
    private String stylerId;
    @NotEmpty(message = "Sub Service name cannot be empty")
    private String name;
    @NotEmpty(message = "Price cannot be empty")
    @Pattern(regexp = AMOUNT_VALIDATION_REGEX , message = "Invalid amount format")
    private String price;
    // Optional for old clients; service creation applies the 60-minute default.
    @Min(value = MIN_SERVICE_DURATION_MINUTES, message = "Duration must be at least 15 minutes")
    @Max(value = MAX_SERVICE_DURATION_MINUTES, message = "Duration cannot exceed 480 minutes")
    private Integer durationMinutes;

    public Integer getDurationMinutes() {
        return durationMinutes == null ? DEFAULT_SERVICE_DURATION_MINUTES : durationMinutes;
    }

    @AssertTrue(message = "Duration must be in 15-minute increments")
    public boolean isDurationOnQuarterHour() {
        return durationMinutes == null || durationMinutes % 15 == 0;
    }
}

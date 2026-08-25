package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Data
public class AvailabilityData {
    @NotBlank(message = "Day of week is required")
    @Pattern(regexp = "[0-6]", message = "Day of week must be 0 (Sunday) to 6 (Saturday)")
    private String dayOfWeek;

    @NotBlank(message = "Start time is required")
    @Pattern(regexp = "^([01]?[0-9]|2[0-3]):[0-5][0-9]$", message = "Start time must be in HH:mm 24-hour format")
    private String startTime;

    @NotBlank(message = "End time is required")
    @Pattern(regexp = "^([01]?[0-9]|2[0-3]):[0-5][0-9]$", message = "End time must be in HH:mm 24-hour format")
    private String endTime;
}

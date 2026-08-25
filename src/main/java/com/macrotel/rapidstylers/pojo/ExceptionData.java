package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Data
public class ExceptionData {
    @NotBlank(message = "Blocked date is required")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Date must be in YYYY-MM-DD format")
    private String blockedDate;

    private String reason; // Optional: "Vacation", "Sick day", etc.
}

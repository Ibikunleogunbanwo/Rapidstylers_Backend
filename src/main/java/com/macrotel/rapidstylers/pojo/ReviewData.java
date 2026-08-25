package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import static com.macrotel.rapidstylers.config.AppConstants.NUMBER_VALIDATION_REGEX;

@Data
public class ReviewData {
    // userId is derived from the JWT subject by the controller — never from the client.
    private String userId;
    @NotEmpty(message = "Booking Id cannot be empty")
    private String bookingId;
    @NotEmpty(message = "Styler Id cannot be empty")
    private String stylerId;
    @NotEmpty(message = "Rating Score cannot be empty")
    @Pattern(regexp = "^[1-5]$", message = "Rating Score must be between 1 and 5")
    private String ratingScore;
    @NotEmpty(message = "Review message cannot be empty")
    private String reviewMessage;
}

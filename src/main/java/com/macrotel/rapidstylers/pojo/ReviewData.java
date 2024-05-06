package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import static com.macrotel.rapidstylers.config.AppConstants.NUMBER_VALIDATION_REGEX;

@Data
public class ReviewData {
    @NotEmpty(message = "User Id cannot be empty")
    private String userId;
    @NotEmpty(message = "Styler Id cannot be empty")
    private String stylerId;
    @NotEmpty(message = "Rating Score cannot be empty")
    @Pattern(regexp = NUMBER_VALIDATION_REGEX, message = "Rating Score can only be number")
    private String ratingScore;
    @NotEmpty(message = "Review message cannot be empty")
    private String reviewMessage;
}

package com.macrotel.rapidstylers.styler.pojo;

import java.math.BigDecimal;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import lombok.Data;

import static com.macrotel.rapidstylers.config.AppConstants.AMOUNT_VALIDATION_REGEX;
import static com.macrotel.rapidstylers.config.AppConstants.DEFAULT_SERVICE_DURATION_MINUTES;
import static com.macrotel.rapidstylers.config.AppConstants.MAX_SERVICE_DURATION_MINUTES;
import static com.macrotel.rapidstylers.config.AppConstants.MAX_SERVICE_PRICE;
import static com.macrotel.rapidstylers.config.AppConstants.MIN_SERVICE_DURATION_MINUTES;

@Data
public class SubServiceData {
    static final String PRICE_FORMAT_MESSAGE = "Enter the price as a number, for example 45 or 45.50";
    static final String PRICE_RANGE_MESSAGE = "Price must be more than $0 and no more than $10,000";
    // Set from the authenticated JWT by the controller; it is intentionally not client-supplied.
    private String stylerId;
    @NotEmpty(message = "Sub Service name cannot be empty")
    private String name;
    @NotEmpty(message = "Price cannot be empty")
    @Pattern(regexp = AMOUNT_VALIDATION_REGEX , message = PRICE_FORMAT_MESSAGE)
    private String price;
    // Optional for old clients; service creation applies the 60-minute default.
    @Min(value = MIN_SERVICE_DURATION_MINUTES, message = "Duration must be at least 15 minutes")
    @Max(value = MAX_SERVICE_DURATION_MINUTES, message = "Duration cannot exceed 480 minutes")
    private Integer durationMinutes;
    /** True when the price is a "from" price and clients book it through a quote. */
    private Boolean priceOnQuote;

    public Integer getDurationMinutes() {
        return durationMinutes == null ? DEFAULT_SERVICE_DURATION_MINUTES : durationMinutes;
    }

    @AssertTrue(message = "Duration must be in 15-minute increments")
    public boolean isDurationOnQuarterHour() {
        return durationMinutes == null || durationMinutes % 15 == 0;
    }

    @AssertTrue(message = PRICE_RANGE_MESSAGE)
    public boolean isPriceInRange() {
        return priceInRange(price);
    }

    /** True for a price above 0 and at most MAX_SERVICE_PRICE; a malformed price is left to the format rule. */
    static boolean priceInRange(String price) {
        if (price == null || !price.matches(AMOUNT_VALIDATION_REGEX)) return true;
        BigDecimal amount = new BigDecimal(price.replace(",", ""));
        return amount.signum() > 0 && amount.compareTo(BigDecimal.valueOf(MAX_SERVICE_PRICE)) <= 0;
    }
}

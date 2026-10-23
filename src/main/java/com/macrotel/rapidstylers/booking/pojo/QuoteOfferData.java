package com.macrotel.rapidstylers.booking.pojo;

import javax.validation.constraints.*;
import lombok.Data;

/** A stylist's price for a quote request. */
@Data
public class QuoteOfferData {
    @NotBlank(message = "Quote id is required")
    private String quoteRef;
    @NotBlank(message = "Enter a price")
    @Pattern(regexp = "^\\d{1,5}(\\.\\d{1,2})?$", message = "Enter a price such as 150 or 149.99")
    private String price;
    @NotNull(message = "Enter how long it takes")
    private Integer durationMinutes;
    @Size(max = 500, message = "Keep the note under 500 characters")
    private String note;
    @Min(value = 1, message = "A quote must stay open for at least a day")
    @Max(value = 14, message = "A quote can stay open for up to 14 days")
    private Integer validDays;
}

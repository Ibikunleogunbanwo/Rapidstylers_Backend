package com.macrotel.rapidstylers.booking.pojo;

import javax.validation.constraints.*;
import lombok.Data;

/** A customer asking a stylist to price a job. The customer id comes from the token. */
@Data
public class QuoteRequestData {
    @NotBlank(message = "Choose a stylist")
    @Size(max = 64, message = "Stylist id is too long")
    private String stylerId;
    @NotBlank(message = "Choose the service this is closest to")
    @Pattern(regexp = "^\\d+$", message = "Service id must be numeric")
    private String subServiceId;
    @NotBlank(message = "Describe what you would like")
    @Size(min = 10, max = 1000, message = "Describe what you would like in 10 to 1000 characters")
    private String description;
    @Size(max = 200, message = "Keep your preferred dates under 200 characters")
    private String preferredDates;
    @Pattern(regexp = "^(salon|visitBarber|homeService)?$", message = "Choose a salon visit or a home visit")
    private String serviceTime;
    /** Reference photos the customer uploaded for this request; checked on the server. */
    @Size(max = 3, message = "Add up to 3 photos")
    private java.util.List<@Size(max = 400, message = "Photo link is too long") String> photoUrls;
    @Min(value = 1, message = "At least one person")
    @Max(value = 20, message = "Up to 20 people per quote")
    private Integer noOfPeople;
}

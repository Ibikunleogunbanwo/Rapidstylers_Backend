package com.macrotel.rapidstylers.styler.pojo;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import lombok.Data;

/**
 * A stylist changing their business details. A new address is located again,
 * and the time zone follows it unless the stylist names one (an IANA id such
 * as America/Edmonton), which then stays theirs.
 */
@Data
public class StylerBusinessInfoData {
    @NotEmpty(message = "Business Name cannot be empty")
    @Size(max = 100, message = "Keep the business name under 100 characters")
    private String businessName;
    @Size(max = 1000, message = "Keep the description under 1000 characters")
    private String description;
    @NotEmpty(message = "Service Type Id cannot be empty")
    private String serviceTypeId;
    @NotEmpty(message = "Street address cannot be empty")
    @Size(max = 200, message = "Keep the street address under 200 characters")
    private String streetAddress;
    @Size(max = 50, message = "Keep the unit under 50 characters")
    private String unit;
    @NotEmpty(message = "City cannot be empty")
    @Size(max = 100, message = "Keep the city under 100 characters")
    private String city;
    @NotEmpty(message = "Business Province cannot be empty")
    @Size(max = 100, message = "Keep the province under 100 characters")
    private String province;
    @Size(max = 20, message = "Keep the postal code under 20 characters")
    private String postalCode;
    @Size(max = 100, message = "Keep the country under 100 characters")
    private String country;
    /** Optional. Blank keeps the zone that follows the address. */
    @Size(max = 64, message = "Time zone is not valid")
    private String timeZone;
}

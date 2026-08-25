package com.macrotel.rapidstylers.dto;

import lombok.Data;

@Data
public class CardDetailsDTO {
    private String cardName;
    // Display-only fields — never the full PAN or CVV.
    private String last4;
    private String brand;
    private Long expMonth;
    private Long expYear;
}

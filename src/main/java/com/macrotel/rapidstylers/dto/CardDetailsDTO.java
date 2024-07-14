package com.macrotel.rapidstylers.dto;

import lombok.Data;

@Data
public class CardDetailsDTO {
    private String cardName;
    private String cardNumber;
    private String cvv;
    private String expiryDate;
}

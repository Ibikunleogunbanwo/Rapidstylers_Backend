package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class CardDetailsData {
    @NotEmpty(message = "User Id cannot be empty")
    private String userId;
    @NotEmpty(message = "Card Name cannot be empty")
    private String cardName;
    @NotEmpty(message = "Card Number cannot be empty")
    private String cardNumber;
    @NotEmpty(message = "CVV cannot be empty")
    private String cvv;
    @NotEmpty(message = "Expiry Date cannot be empty")
    private String expiryDate;
}

package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class CardDetailsData {
    // userId is derived from the JWT subject by the controller — never from the client.
    private String userId;
    @NotEmpty(message = "Cardholder name cannot be empty")
    private String cardName;
    // The Stripe PaymentMethod id produced by the frontend's confirmCardSetup
    // call. Raw card numbers and CVVs are never sent to or stored by this API.
    @NotEmpty(message = "Payment method is required")
    private String paymentMethodId;
}

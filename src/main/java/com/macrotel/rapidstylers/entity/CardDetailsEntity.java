package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Card-on-file metadata. Raw card numbers, CVVs and expiry dates are NEVER
 * stored here — they are collected by Stripe's Elements iframe and this table
 * only keeps the Stripe customer/payment-method references plus display-only
 * card info (last4, brand, expiry).
 */
@Data
@Entity
@Table(name = "card_details")
public class CardDetailsEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String userId;
    private String cardName;
    private String stripeCustomerId;
    private String stripePaymentMethodId;
    private String last4;
    private String brand;
    private Long expMonth;
    private Long expYear;
    private String updatedDate;

    public CardDetailsEntity() {
        this.updatedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss"));
    }
}

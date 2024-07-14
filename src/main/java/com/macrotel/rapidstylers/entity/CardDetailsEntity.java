package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "card_details")
public class CardDetailsEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String userId;
    private String cardName;
    private String cardNumber;
    private String cvv;
    private String expiryDate;
    private String updatedDate;

    public CardDetailsEntity() {
        this.updatedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss"));
    }
}

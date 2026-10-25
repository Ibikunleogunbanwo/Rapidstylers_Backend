package com.macrotel.rapidstylers.booking.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** One line in the short conversation on a quote, from the customer or the stylist. Times are UTC. */
@Data
@Entity
@Table(name = "quote_messages")
public class QuoteMessageEntity implements Serializable {
    public static final String CUSTOMER = "CUSTOMER";
    public static final String STYLIST = "STYLIST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "quote_ref", nullable = false, length = 16)
    private String quoteRef;
    @Column(name = "sender_role", nullable = false, length = 16)
    private String senderRole;
    @Column(nullable = false, length = 500)
    private String body;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

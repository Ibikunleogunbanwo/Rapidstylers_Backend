package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;

@Data
@Entity
@Table(name = "refunds")
public class RefundEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String refundId;

    @Column(nullable = false, length = 100)
    private String appointmentId;

    private String paymentIntentId;

    /** Display currency amount, e.g. "25.00". Stored as text to match paymentAmount. */
    @Column(nullable = false, length = 20)
    private String amount;

    @Column(length = 1000)
    private String reason;

    /** REQUESTED -> COMPLETED, or FAILED when the Stripe refund could not be created. */
    @Column(nullable = false, length = 20)
    private String status;

    private String stripeRefundId;

    @Column(length = 2000)
    private String failureCode;

    /** Admin id that initiated the refund, or SYSTEM for automatic refunds. */
    @Column(nullable = false, length = 100)
    private String createdBy;

    @Column(nullable = false, length = 30)
    private String createdAt;

    @Column(length = 30)
    private String completedAt;
}

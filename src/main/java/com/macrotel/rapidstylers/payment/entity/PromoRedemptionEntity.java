package com.macrotel.rapidstylers.payment.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** One booking that used a promo code. */
@Data
@Entity
@Table(name = "promo_redemptions")
public class PromoRedemptionEntity implements Serializable {
    public static final String APPLIED = "APPLIED";
    public static final String RELEASED = "RELEASED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "promo_id", nullable = false)
    private Long promoId;
    @Column(nullable = false, length = 40)
    private String code;
    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;
    @Column(name = "appointment_id", nullable = false, length = 100, unique = true)
    private String appointmentId;
    @Column(name = "discount_cents", nullable = false)
    private Long discountCents;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

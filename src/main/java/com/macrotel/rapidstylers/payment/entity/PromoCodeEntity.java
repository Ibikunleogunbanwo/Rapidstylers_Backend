package com.macrotel.rapidstylers.payment.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** A promo code an admin set up. See {@code PromotionService}. */
@Data
@Entity
@Table(name = "promo_codes")
public class PromoCodeEntity implements Serializable {
    public static final String PERCENT = "PERCENT";
    public static final String FIXED = "FIXED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** Upper case, letters, digits and dashes. */
    @Column(nullable = false, length = 40, unique = true)
    private String code;
    @Column(length = 300)
    private String description;
    /** PERCENT (value 1-100) or FIXED (value in cents). */
    @Column(name = "discount_type", nullable = false, length = 10)
    private String discountType;
    @Column(name = "discount_value", nullable = false)
    private Long discountValue;
    /** Cap on a PERCENT discount, in cents. */
    @Column(name = "max_discount_cents")
    private Long maxDiscountCents;
    @Column(name = "min_booking_cents")
    private Long minBookingCents;
    /** UTC; null: from now. */
    @Column(name = "starts_at")
    private LocalDateTime startsAt;
    /** UTC; null: never. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    /** Bookings in total; null: no limit. */
    @Column(name = "max_redemptions")
    private Integer maxRedemptions;
    @Column(name = "max_per_customer", nullable = false)
    private Integer maxPerCustomer = 1;
    @Column(name = "first_booking_only", nullable = false)
    private boolean firstBookingOnly;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "created_by", length = 100)
    private String createdBy;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Tracks recovery of a stylist payout transfer after a completed booking is
 * cancelled and refunded. A reversal can fail (e.g. the stylist already
 * withdrew the funds), so attempts are persisted and retried by a scheduled
 * job until funds become available or the attempt budget is exhausted.
 */
@Data
@Entity
@Table(name = "payout_reversals")
public class PayoutReversalEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic lock — prevents two retry passes from racing the same record. */
    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 32)
    private String reversalId;

    @Column(nullable = false, length = 100)
    private String appointmentId;

    @Column(nullable = false, length = 100)
    private String transferId;

    /** Display amount being recovered, e.g. "112.50". Informational. */
    @Column(length = 20)
    private String amount;

    /** PENDING -> REVERSED, or FAILED (retryable) -> PERMANENTLY_FAILED after max attempts. */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(length = 2000)
    private String lastError;

    private String stripeReversalId;

    @Column(nullable = false, length = 30)
    private String createdAt;

    @Column(length = 30)
    private String reversedAt;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();
}

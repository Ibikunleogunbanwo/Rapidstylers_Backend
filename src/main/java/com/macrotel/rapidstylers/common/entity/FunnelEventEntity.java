package com.macrotel.rapidstylers.common.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** One person reaching one sign-up funnel step. See {@code FunnelService}. */
@Data
@Entity
@Table(name = "funnel_events")
public class FunnelEventEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 30)
    private String step;
    /** CUSTOMER or STYLIST; null for a visit, which is before anyone picks. */
    @Column(length = 20)
    private String role;
    /** SHA-256 of the browser id, email or account id. */
    @Column(nullable = false, length = 64)
    private String subject;
    /** The reporting-zone calendar day, yyyy-MM-dd. */
    @Column(name = "day_key", nullable = false, length = 10)
    private String dayKey;
    /** UTC. */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
    @Column(name = "dedupe_key", nullable = false, length = 120, unique = true)
    private String dedupeKey;
}

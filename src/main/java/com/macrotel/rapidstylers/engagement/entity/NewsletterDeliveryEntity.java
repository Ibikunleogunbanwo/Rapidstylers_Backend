package com.macrotel.rapidstylers.engagement.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** One campaign going to one subscriber. */
@Data
@Entity
@Table(name = "newsletter_deliveries")
public class NewsletterDeliveryEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;
    @Column(name = "subscriber_id", nullable = false)
    private Long subscriberId;
    @Column(nullable = false, length = 254)
    private String email;
    /** PENDING (waiting its turn), QUEUED (handed to the mail queue), SENT, FAILED or SKIPPED. */
    @Column(nullable = false, length = 20)
    private String status;
    @Column(length = 500)
    private String error;
    @Column(name = "queued_at")
    private LocalDateTime queuedAt;
    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}

package com.macrotel.rapidstylers.engagement.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** One newsletter an admin writes, aims at an audience and schedules. */
@Data
@Entity
@Table(name = "newsletter_campaigns")
public class NewsletterCampaignEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, length = 150)
    private String subject;
    /** Plain text; blank lines become paragraphs when sent. */
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String body;
    /** ALL, CUSTOMER, STYLER or GUEST. */
    @Column(name = "audience_role", nullable = false, length = 20)
    private String audienceRole;
    /** Comma-separated provinces; empty means everywhere. */
    @Column(length = 500)
    private String provinces;
    /** DRAFT, SCHEDULED, SENDING, SENT or CANCELLED. */
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;
    @Column(name = "started_at")
    private LocalDateTime startedAt;
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
    @Column(name = "created_by", length = 100)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

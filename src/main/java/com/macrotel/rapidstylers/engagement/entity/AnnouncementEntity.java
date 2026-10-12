package com.macrotel.rapidstylers.engagement.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** One admin announcement as it was sent (see NotificationService#sendAnnouncement). */
@Data
@Entity
@Table(name = "announcements")
public class AnnouncementEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String title;
    @Column(nullable = false, length = 1000)
    private String message;
    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;
    /** The one customer it went to; null when it went to every active customer. */
    @Column(name = "recipient_email", length = 190)
    private String recipientEmail;
    /** Server local time. */
    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;
    /** The sending admin (their account email, which admin tokens carry). */
    @Column(name = "sent_by", length = 190)
    private String sentBy;
}

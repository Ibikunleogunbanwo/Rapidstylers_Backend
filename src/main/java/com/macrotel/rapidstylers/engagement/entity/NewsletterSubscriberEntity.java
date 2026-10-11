package com.macrotel.rapidstylers.engagement.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** One email address and whether it agreed to receive the newsletter. */
@Data
@Entity
@Table(name = "newsletter_subscribers")
public class NewsletterSubscriberEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** Lower-case. */
    @Column(nullable = false, length = 254, unique = true)
    private String email;
    /** PENDING (waiting for the confirm click), SUBSCRIBED or UNSUBSCRIBED. */
    @Column(nullable = false, length = 20)
    private String status;
    /** CUSTOMER, STYLER or GUEST (signed up from the website without an account). */
    @Column(nullable = false, length = 20)
    private String role;
    @Column(name = "account_id", length = 100)
    private String accountId;
    @Column(length = 60)
    private String province;
    /** Where they signed up, e.g. "website" or "account". */
    @Column(length = 40)
    private String source;
    /** The exact wording they agreed to, and when. */
    @Column(name = "consent_text", length = 500)
    private String consentText;
    @Column(name = "consent_at")
    private LocalDateTime consentAt;
    @Column(name = "confirm_token", length = 64, unique = true)
    private String confirmToken;
    @Column(name = "confirm_sent_at")
    private LocalDateTime confirmSentAt;
    @Column(name = "unsubscribe_token", nullable = false, length = 64, unique = true)
    private String unsubscribeToken;
    @Column(name = "unsubscribed_at")
    private LocalDateTime unsubscribedAt;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

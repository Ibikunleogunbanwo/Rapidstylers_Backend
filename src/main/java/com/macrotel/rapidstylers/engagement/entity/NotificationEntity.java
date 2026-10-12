package com.macrotel.rapidstylers.engagement.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "user_notifications")
public class NotificationEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String stylerId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    /**
     * Server local time (the JVM zone), "yyyy-MM-dd HH:mm:ss". Responses also
     * carry it as an instant, createdAtUtc (see NotificationTimes).
     */
    @Column(nullable = false)
    private String createdAt;

    /**
     * Whose inbox this row is in. CUSTOMER: the customer userId (stylerId only
     * names the stylist it is about). STYLIST: the stylist stylerId, with an
     * empty userId so no customer query can match it.
     */
    @Column(name = "recipient_role", nullable = false, length = 16)
    private String recipientRole = RECIPIENT_CUSTOMER;

    public static final String RECIPIENT_CUSTOMER = "CUSTOMER";
    public static final String RECIPIENT_STYLIST = "STYLIST";

    public NotificationEntity() {
    }

    public NotificationEntity(String userId, String stylerId, String type, String title, String message) {
        this.userId = userId;
        this.stylerId = stylerId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.read = false;
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** A notification for a stylist's own inbox. */
    public static NotificationEntity forStylist(String stylerId, String type, String title, String message) {
        NotificationEntity notification = new NotificationEntity("", stylerId, type, title, message);
        notification.recipientRole = RECIPIENT_STYLIST;
        return notification;
    }
}

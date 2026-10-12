package com.macrotel.rapidstylers.engagement.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "support_tickets")
public class SupportTicketEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String userId;
    @Column(nullable = false)
    private String subject;
    @Column(nullable = false, length = 2000)
    private String message;
    @Column(nullable = false)
    private String status;
    @Column(length = 2000)
    private String adminResponse;
    @Column(nullable = false)
    private String createdAt;
    @Column(nullable = false)
    private String updatedAt;
    /** CUSTOMER or STYLIST: which kind of account userId names. */
    @Column(name = "sender_role", length = 16)
    private String senderRole;

    public SupportTicketEntity() {
    }

    public SupportTicketEntity(String userId, String subject, String message) {
        this(userId, "CUSTOMER", subject, message);
    }

    public SupportTicketEntity(String userId, String senderRole, String subject, String message) {
        this.userId = userId;
        this.senderRole = senderRole;
        this.subject = subject;
        this.message = message;
        this.status = "OPEN";
        this.createdAt = now();
        this.updatedAt = this.createdAt;
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}

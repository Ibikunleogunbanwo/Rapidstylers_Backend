package com.macrotel.rapidstylers.engagement.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** An address no newsletter may ever go to: it bounced, complained, or an admin blocked it. */
@Data
@Entity
@Table(name = "email_suppressions")
public class EmailSuppressionEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** Lower-case. */
    @Column(nullable = false, length = 254, unique = true)
    private String email;
    /** BOUNCE, COMPLAINT or MANUAL. */
    @Column(nullable = false, length = 40)
    private String reason;
    @Column(length = 300)
    private String note;
    @Column(name = "created_by", length = 100)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

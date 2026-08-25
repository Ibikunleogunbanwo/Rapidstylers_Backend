package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String actorId;
    @Column(nullable = false)
    private String actorRole;
    @Column(nullable = false)
    private String action;
    @Column(nullable = false)
    private String resourceType;
    @Column(nullable = false)
    private String resourceId;
    @Column(length = 2000)
    private String details;
    @Column(nullable = false)
    private String createdAt;

    public AuditLogEntity() {
    }

    public AuditLogEntity(String actorId, String actorRole, String action, String resourceType, String resourceId, String details) {
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.details = details;
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}

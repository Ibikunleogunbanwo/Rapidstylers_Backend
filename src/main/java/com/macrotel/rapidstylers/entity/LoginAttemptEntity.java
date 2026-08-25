package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "login_attempts")
public class LoginAttemptEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String accountType;

    @Column(length = 100)
    private String accountId;

    @Column(nullable = false, length = 255)
    private String emailAddress;

    @Column(nullable = false)
    private Boolean success;

    @Column(length = 100)
    private String ipAddress;

    @Column(length = 512)
    private String userAgent;

    @Column(length = 100)
    private String failureReason;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}

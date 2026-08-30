package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * A database-backed admin account. The bootstrap admin (ADMIN_EMAIL /
 * ADMIN_PASSWORD from the environment) is seeded here on first boot so the
 * existing flow keeps working; additional admins can be created via the
 * AdminAccountController. Passwords are stored only as BCrypt hashes.
 */
@Data
@Entity
@Table(name = "admin_accounts")
public class AdminAccountEntity implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 190)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, length = 32)
    private String role = "ADMIN";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public AdminAccountEntity() {
    }
}
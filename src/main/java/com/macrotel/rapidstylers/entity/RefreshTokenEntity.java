package com.macrotel.rapidstylers.entity;

import lombok.Data;
import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 64)
    private String role;

    @Column(nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(nullable = false)
    private String familyId;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public RefreshTokenEntity() {}
}

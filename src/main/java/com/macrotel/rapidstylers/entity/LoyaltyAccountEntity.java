package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "loyalty_accounts", uniqueConstraints = @UniqueConstraint(name = "uk_loyalty_user", columnNames = "user_id"))
public class LoyaltyAccountEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private String userId;
    @Column(nullable = false)
    private int points;
    @Column(nullable = false)
    private String referralCode;
    @Column(nullable = false)
    private String createdAt;
    @Column(nullable = false)
    private String updatedAt;

    public LoyaltyAccountEntity() {
    }

    public LoyaltyAccountEntity(String userId, String referralCode) {
        this.userId = userId;
        this.referralCode = referralCode;
        this.points = 0;
        this.createdAt = now();
        this.updatedAt = this.createdAt;
    }

    public void addPoints(int amount) {
        this.points += Math.max(0, amount);
        this.updatedAt = now();
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}

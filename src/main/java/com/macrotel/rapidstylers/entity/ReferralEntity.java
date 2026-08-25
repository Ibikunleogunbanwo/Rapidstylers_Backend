package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "referrals", uniqueConstraints = @UniqueConstraint(name = "uk_referral_referred_user", columnNames = "referred_user_id"))
public class ReferralEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "referrer_user_id", nullable = false)
    private String referrerUserId;
    @Column(name = "referred_user_id", nullable = false)
    private String referredUserId;
    @Column(nullable = false)
    private String referralCode;
    @Column(nullable = false)
    private String status;
    @Column(nullable = false)
    private String createdAt;

    public ReferralEntity() {
    }

    public ReferralEntity(String referrerUserId, String referredUserId, String referralCode) {
        this.referrerUserId = referrerUserId;
        this.referredUserId = referredUserId;
        this.referralCode = referralCode;
        this.status = "PENDING";
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}

package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "reviews", uniqueConstraints = @UniqueConstraint(name = "uk_review_booking", columnNames = "booking_id"))
public class ReviewEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String stylerId;
    private String userName;
    private int ratingScore;
    private String message;
    private String createdAt;
    private String userId;
    // The completed booking this review is tied to (new reviews require it).
    @Column(name = "booking_id")
    private String bookingId;
    // New reviews start pending moderation; only APPROVED reviews are public.
    @Column(nullable = false)
    private String moderationStatus = "PENDING";

    public ReviewEntity() {
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss"));
    }
}

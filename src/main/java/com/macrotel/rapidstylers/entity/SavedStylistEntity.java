package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(
        name = "saved_stylists",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_saved_stylist_user_styler",
                columnNames = {"user_id", "styler_id"}
        )
)
public class SavedStylistEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "styler_id", nullable = false)
    private String stylerId;

    @Column(nullable = false)
    private String createdAt;

    public SavedStylistEntity() {
    }

    public SavedStylistEntity(String userId, String stylerId) {
        this.userId = userId;
        this.stylerId = stylerId;
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss"));
    }
}

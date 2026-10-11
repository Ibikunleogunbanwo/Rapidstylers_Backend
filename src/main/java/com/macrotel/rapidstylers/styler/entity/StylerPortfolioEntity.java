package com.macrotel.rapidstylers.styler.entity;

import com.macrotel.rapidstylers.styler.pojo.StylerPortfolioData;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "portfolios")
public class StylerPortfolioEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String stylerId;
    private String imageUrl;
    private String name;
    private String status;
    private String createdAt;
    // Gallery category this work belongs to (e.g. braids, nail tech, makeup).
    private String category;
    /** PENDING until an admin decides; only APPROVED photos appear in the public gallery. */
    @Column(name = "moderation_status", length = 20)
    private String moderationStatus = PENDING;
    /** The admin's note on the decision (required when rejecting; shown to the stylist). */
    @Column(name = "moderation_note", length = 500)
    private String moderationNote;
    @Column(name = "moderated_by", length = 100)
    private String moderatedBy;
    @Column(name = "moderated_at")
    private LocalDateTime moderatedAt;

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    public StylerPortfolioEntity() {
    }

    public StylerPortfolioEntity(StylerPortfolioData stylerPortfolioData) {
        this.stylerId = stylerPortfolioData.getStylerId();
        this.imageUrl = stylerPortfolioData.getImageUrl();
        this.name = stylerPortfolioData.getName();
        this.category = stylerPortfolioData.getCategory();
        this.status = "0";
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss"));

    }

}

package com.macrotel.rapidstylers.styler.dto;

import lombok.Data;

@Data
public class StylerPortfolioDTO {
    private Long id;
    private String imageUrl;
    private String name;
    private String category;
    private String status;
    private String createdAt;
    /** PENDING, APPROVED or REJECTED. */
    private String moderationStatus;
    /** The admin's note; only filled in on the stylist's own portfolio. */
    private String moderationNote;
}

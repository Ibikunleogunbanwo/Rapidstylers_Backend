package com.macrotel.rapidstylers.engagement.dto;

import lombok.Data;

@Data
public class StylerReviewDTO {
    private String stylerId;
    private String userName;
    private String ratingScore;
    private String message;
    private String createdAt;
    private String userId;
    private String bookingId;
}

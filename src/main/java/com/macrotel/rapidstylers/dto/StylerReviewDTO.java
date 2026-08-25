package com.macrotel.rapidstylers.dto;

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

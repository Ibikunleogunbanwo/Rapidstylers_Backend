package com.macrotel.rapidstylers.dto;

import lombok.Data;

@Data
public class StylerPortfolioDTO {
    private Long id;
    private String imageUrl;
    private String name;
    private String category;
    private String status;
    private String createdAt;
}

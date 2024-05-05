package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class StylerPortfolioData {
    @NotEmpty(message = "StylerId cannot be empty")
    private String stylerId;
    @NotEmpty(message = "Image URL cannot be empty")
    private String imageUrl;
    @NotEmpty(message = "Portfolio Name cannot be empty")
    private String name;
}

package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class StylerPortfolioData {
    // stylerId is derived from the JWT subject by the controller — never from the client.
    private String stylerId;
    @NotEmpty(message = "Image URL cannot be empty")
    private String imageUrl;
    private String name;
    @NotEmpty(message = "Category cannot be empty")
    private String category;
}

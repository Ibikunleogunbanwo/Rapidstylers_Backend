package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class PortfolioActionData {
    @NotNull(message = "Portfolio id cannot be empty")
    private Long portfolioId;
}

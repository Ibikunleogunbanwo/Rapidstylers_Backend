package com.macrotel.rapidstylers.styler.pojo;

import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PortfolioActionData {
    @NotNull(message = "Portfolio id cannot be empty")
    private Long portfolioId;
}

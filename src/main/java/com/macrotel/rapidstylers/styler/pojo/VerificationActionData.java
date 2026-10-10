package com.macrotel.rapidstylers.styler.pojo;

import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerificationActionData {
    @NotBlank(message = "stylerId is required")
    private String stylerId;

    @NotBlank(message = "action is required")
    private String action;
}

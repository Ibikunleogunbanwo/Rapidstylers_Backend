package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class VerificationActionData {
    @NotBlank(message = "stylerId is required")
    private String stylerId;

    @NotBlank(message = "action is required")
    private String action;
}

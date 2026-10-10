package com.macrotel.rapidstylers.styler.dto;

import lombok.Data;

@Data
public class SubServiceDTO {
    private String name;
    private String id;
    private String price;
    private Integer durationMinutes;
    private String status;
    private String createdAt;
}

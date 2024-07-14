package com.macrotel.rapidstylers.dto;

import lombok.Data;

@Data
public class FeedBackDTO {
    private String id;
    private String emailAddress;
    private String message;
    private String messageType;
    private String insertedDt;
    private UserAccountDTO userData;
}

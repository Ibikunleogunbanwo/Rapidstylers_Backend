package com.macrotel.rapidstylers.engagement.dto;

import com.macrotel.rapidstylers.customer.dto.UserAccountDTO;
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

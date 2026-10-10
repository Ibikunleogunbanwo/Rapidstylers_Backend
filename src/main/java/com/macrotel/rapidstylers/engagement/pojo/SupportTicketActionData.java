package com.macrotel.rapidstylers.engagement.pojo;

import javax.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class SupportTicketActionData {
    private Long ticketId;
    @NotEmpty(message = "Status cannot be empty")
    private String status;
    private String adminResponse;
}

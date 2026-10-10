package com.macrotel.rapidstylers.engagement.pojo;

import javax.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class SupportTicketData {
    @NotEmpty(message = "Subject cannot be empty")
    private String subject;
    @NotEmpty(message = "Message cannot be empty")
    private String message;
}

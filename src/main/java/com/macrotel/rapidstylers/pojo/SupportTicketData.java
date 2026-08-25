package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class SupportTicketData {
    @NotEmpty(message = "Subject cannot be empty")
    private String subject;
    @NotEmpty(message = "Message cannot be empty")
    private String message;
}

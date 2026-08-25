package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class SupportTicketActionData {
    private Long ticketId;
    @NotEmpty(message = "Status cannot be empty")
    private String status;
    private String adminResponse;
}

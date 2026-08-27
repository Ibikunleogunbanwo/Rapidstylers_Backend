package com.macrotel.rapidstylers.pojo;

import lombok.Data;

@Data
public class RefundRequestData {
    private String appointmentId;
    /** Optional — a partial refund amount. Omitted/null refunds the full captured amount. */
    private String amount;
    private String reason;
}

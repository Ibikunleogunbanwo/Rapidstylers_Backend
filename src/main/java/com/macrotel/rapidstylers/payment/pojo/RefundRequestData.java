package com.macrotel.rapidstylers.payment.pojo;

import lombok.Data;

@Data
public class RefundRequestData {
    private String appointmentId;
    /** Optional — a partial refund amount. Omitted/null refunds the full captured amount. */
    private String amount;
    private String reason;
    /**
     * Optional — when the stylist has already been paid for this booking, true
     * takes their part of this refund back from them; false or omitted leaves
     * their pay alone and the platform covers the whole refund.
     */
    private Boolean recoverStylistShare;
}

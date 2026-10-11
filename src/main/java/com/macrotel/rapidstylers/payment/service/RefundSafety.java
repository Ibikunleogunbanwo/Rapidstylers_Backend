package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.payment.entity.RefundEntity;
import com.stripe.exception.StripeException;

/**
 * Rules for refunds whose outcome at Stripe is not known.
 *
 * <p>Picture posting a cheque and never hearing back: the money may or may
 * not have left. A refund whose Stripe reply was lost (timeout, network drop,
 * Stripe 5xx) is kept as {@link #UNCONFIRMED} with its original refund id,
 * and the next refund attempt on that booking finishes that same refund
 * (found by its key at Stripe, or re-sent under the same idempotency key)
 * instead of starting a second one.
 */
final class RefundSafety {

    /** Sent to Stripe, but whether it went through is not known yet. */
    static final String UNCONFIRMED = "UNCONFIRMED";

    private RefundSafety() {
    }

    /** One stable key per refund row: every retry of that refund reuses it. */
    static String idempotencyKey(RefundEntity refund) {
        return "refund_" + refund.getPaymentIntentId() + "_" + refund.getRefundId();
    }

    /**
     * True only when Stripe answered and refused (a 4xx such as an invalid
     * amount or a rate limit), so no refund exists. A lost reply, a network
     * error, a Stripe 5xx, an idempotency conflict (409) or any non-Stripe
     * failure leaves the outcome unknown.
     */
    static boolean definitelyNotRefunded(Exception ex) {
        if (!(ex instanceof StripeException)) {
            return false;
        }
        Integer status = ((StripeException) ex).getStatusCode();
        return status != null && status >= 400 && status < 500 && status != 409;
    }
}

package com.macrotel.rapidstylers.payment.controller;

import com.macrotel.rapidstylers.payment.service.PaymentService;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Stripe's webhook: Stripe calls this, signed, when a payment, refund, dispute or Connect account changes. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class StripeWebhookController {
    private static final Logger LOG = Logger.getLogger(StripeWebhookController.class.getName());

    @Autowired
    PaymentService paymentService;

    /** Stripe webhook (signature-verified). Public: no API key or JWT required. */
    @PostMapping("/stripe/webhook")
    public ResponseEntity<String> stripeWebhook(@RequestBody String payload,
                                                @RequestHeader(value = "Stripe-Signature", required = false) String signature){
        if(signature == null || signature.isEmpty()){
            // A delivery with no signature has no event id to name, and it is the
            // one rejection the service never sees. Logged here so the app log
            // shows every hit on this endpoint, whoever made it.
            LOG.warning("Stripe webhook rejected: id=unknown type=unknown reason=missing Stripe-Signature header");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing Stripe-Signature header");
        }
        try {
            paymentService.handleStripeWebhook(payload, signature);
            return ResponseEntity.ok("ok");
        } catch(IllegalArgumentException ex){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        } catch(Exception ex){
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Webhook handling failed");
        }
    }
}

package com.macrotel.rapidstylers.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.SetupIntent;
import com.stripe.model.Transfer;
import com.stripe.net.Webhook;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.TransferCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Locale;
import java.util.Map;

/**
 * Stripe integration for card-on-file payments.
 *
 * No raw card data ever touches this application. The frontend collects card
 * details inside Stripe's own Elements iframe, we create a SetupIntent here,
 * and the resulting PaymentMethod reference (plus a Stripe Customer) is the
 * only thing persisted. Booking creates a PaymentIntent with manual capture:
 * funds are authorized but only moved when the stylist completes the
 * appointment; decline/cancel releases the hold.
 *
 * When STRIPE_SECRET_KEY is empty the whole payment surface is disabled and
 * booking falls back to the previous no-payment dev behavior.
 */
@Service
public class StripeService {

    /**
     * Mode selector: "test" or "live". Empty keeps the legacy single-key
     * behavior (app.stripe.secret-key / STRIPE_SECRET_KEY), so existing
     * deployments keep working unchanged while both key sets are added.
     */
    @Value("${app.stripe.mode:}")
    private String mode;

    // Legacy single key set — used when mode is empty.
    @Value("${app.stripe.secret-key:}")
    private String secretKey;
    @Value("${app.stripe.webhook-secret:}")
    private String webhookSecret;
    @Value("${app.stripe.connect-webhook-secret:}")
    private String connectWebhookSecret;

    // Test-mode key set.
    @Value("${app.stripe.test.secret-key:}")
    private String testSecretKey;
    @Value("${app.stripe.test.webhook-secret:}")
    private String testWebhookSecret;
    @Value("${app.stripe.test.connect-webhook-secret:}")
    private String testConnectWebhookSecret;

    // Live-mode key set.
    @Value("${app.stripe.live.secret-key:}")
    private String liveSecretKey;
    @Value("${app.stripe.live.webhook-secret:}")
    private String liveWebhookSecret;
    @Value("${app.stripe.live.connect-webhook-secret:}")
    private String liveConnectWebhookSecret;

    @Value("${app.stripe.currency:cad}")
    private String currency;

    @PostConstruct
    void init() {
        resolveActiveKeys();
        if (isConfigured()) {
            Stripe.apiKey = secretKey;
        }
    }

    /**
     * Picks the active key set. STRIPE_MODE=test/live selects that set and
     * ONLY that set — an explicitly chosen mode never falls back to the other
     * mode's keys, so a misconfigured .env fails closed (payments disabled)
     * instead of silently charging the wrong environment. An empty mode keeps
     * the legacy single-key behavior for existing deployments.
     */
    private void resolveActiveKeys() {
        String selected = trimToEmpty(mode).toLowerCase(Locale.ROOT);
        if ("test".equals(selected)) {
            secretKey = trimToEmpty(testSecretKey);
            webhookSecret = trimToEmpty(testWebhookSecret);
            connectWebhookSecret = trimToEmpty(testConnectWebhookSecret);
        } else if ("live".equals(selected)) {
            secretKey = trimToEmpty(liveSecretKey);
            webhookSecret = trimToEmpty(liveWebhookSecret);
            connectWebhookSecret = trimToEmpty(liveConnectWebhookSecret);
        }
        // else: legacy fields keep their @Value-injected values.
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public boolean isConfigured() {
        return secretKey != null && !secretKey.trim().isEmpty();
    }

    public String currency() {
        return currency == null || currency.trim().isEmpty() ? "cad" : currency.toLowerCase(Locale.ROOT);
    }

    /** Creates a Stripe Customer for the account, or returns the existing one by id. */
    public Customer getOrCreateCustomer(String stripeCustomerId, String email, String name) throws StripeException {
        if (stripeCustomerId != null && !stripeCustomerId.isEmpty()) {
            return Customer.retrieve(stripeCustomerId);
        }
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(email)
                .setName(name)
                .putMetadata("platform", "rapidstylers")
                .build();
        return Customer.create(params);
    }

    /** Returns a SetupIntent clientSecret for saving a card via Stripe Elements. */
    public String createSetupIntentClientSecret(String customerId) throws StripeException {
        SetupIntentCreateParams params = SetupIntentCreateParams.builder()
                .setCustomer(customerId)
                .addPaymentMethodType("card")
                .build();
        SetupIntent setupIntent = SetupIntent.create(params);
        return setupIntent.getClientSecret();
    }

    /**
     * Attaches a PaymentMethod (created by the frontend via confirmCardSetup) to
     * the customer. Returns the card's display-only details.
     */
    public CardDisplay attachPaymentMethod(String customerId, String paymentMethodId) throws StripeException {
        PaymentMethod pm = PaymentMethod.retrieve(paymentMethodId);
        PaymentMethodAttachParams attachParams = PaymentMethodAttachParams.builder()
                .setCustomer(customerId)
                .build();
        pm = pm.attach(attachParams);
        PaymentMethod.Card card = pm.getCard();
        if (card == null) {
            throw new StripeException("Payment method is not a card", null, null, 0) {};
        }
        return new CardDisplay(
                card.getLast4(),
                card.getBrand() == null ? "card" : card.getBrand().toLowerCase(Locale.ROOT),
                card.getExpMonth(),
                card.getExpYear()
        );
    }

    /** Creates a Connect Express account so a stylist can receive payouts. */
    public Account createExpressAccount(String stylerId, String email, String businessName,
                                        String firstName, String lastName) throws StripeException {
        AccountCreateParams.Builder builder = AccountCreateParams.builder()
                .setType(AccountCreateParams.Type.EXPRESS)
                .setEmail(email)
                .setCapabilities(AccountCreateParams.Capabilities.builder()
                        .setTransfers(AccountCreateParams.Capabilities.Transfers.builder().setRequested(true).build())
                        .build())
                .putMetadata("stylerId", stylerId)
                .putMetadata("platform", "rapidstylers");
        if(firstName != null && !firstName.isBlank()){
            builder.setIndividual(AccountCreateParams.Individual.builder()
                    .setFirstName(firstName)
                    .setLastName(lastName == null ? "" : lastName)
                    .build());
        }
        if(businessName != null && !businessName.isBlank()){
            builder.setBusinessProfile(AccountCreateParams.BusinessProfile.builder().setName(businessName).build());
        }
        return Account.create(builder.build());
    }

    /** Returns a Stripe-hosted onboarding link for a Connect account. */
    public AccountLink createAccountLink(String accountId, String refreshUrl, String returnUrl) throws StripeException {
        return AccountLink.create(AccountLinkCreateParams.builder()
                .setAccount(accountId)
                .setRefreshUrl(refreshUrl)
                .setReturnUrl(returnUrl)
                .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .build());
    }

    /**
     * Authorizes the booking amount against the customer's saved card.
     * Capture method is MANUAL: the hold is settled when the stylist accepts
     * the booking. The later completion transition handles the Connect transfer.
     * The connectAccountId and feeCents parameters remain part of the service
     * contract for compatibility, but funds stay on the platform until completion.
     */
    public PaymentIntent authorizeBookingPayment(String customerId, String paymentMethodId,
                                                 long amountCents, String appointmentId,
                                                 String connectAccountId, long feeCents) throws StripeException {
        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(currency())
                .setCustomer(customerId)
                .setPaymentMethod(paymentMethodId)
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                .setConfirmationMethod(PaymentIntentCreateParams.ConfirmationMethod.AUTOMATIC)
                .setConfirm(true)
                .setOffSession(true)
                .putMetadata("appointmentId", appointmentId == null ? "" : appointmentId)
                .putMetadata("platform", "rapidstylers");
        // Keep the charge on the platform account. The stylist transfer is
        // created only after the appointment is completed, so accepting or
        // capturing a booking never makes payout funds eligible early.
        return PaymentIntent.create(builder.build());
    }

    /** Transfers the stylist's net share after the appointment is completed. */
    public Transfer transferStylistShare(String connectAccountId, long amountCents, String appointmentId)
            throws StripeException {
        if(connectAccountId == null || connectAccountId.isBlank() || amountCents <= 0){
            return null;
        }
        return Transfer.create(TransferCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(currency())
                .setDestination(connectAccountId)
                .putMetadata("appointmentId", appointmentId == null ? "" : appointmentId)
                .putMetadata("platform", "rapidstylers")
                .build());
    }

    /** Settles a previously authorized hold (stylist completed the appointment). */
    public PaymentIntent captureBookingPayment(String paymentIntentId) throws StripeException {
        PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
        if ("requires_capture".equals(intent.getStatus())) {
            return intent.capture();
        }
        return intent;
    }

    /** Releases an authorized hold without charging (appointment declined or cancelled). */
    public PaymentIntent releaseBookingPayment(String paymentIntentId) throws StripeException {
        PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
        if ("requires_capture".equals(intent.getStatus())) {
            return intent.cancel();
        }
        return intent;
    }

    /**
     * Verifies and returns a webhook event. Throws a RuntimeException when the
     * signature does not match the configured webhook secret.
     */
    public Event verifyWebhookEvent(String payload, String signatureHeader) {
        IllegalArgumentException firstFailure;
        try {
            return Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (Exception ex) {
            firstFailure = new IllegalArgumentException("Invalid webhook signature");
        }

        // Platform and connected-account destinations have different signing
        // secrets. Accept either, but never bypass signature verification.
        if (connectWebhookSecret != null && !connectWebhookSecret.trim().isEmpty()
                && !connectWebhookSecret.equals(webhookSecret)) {
            try {
                return Webhook.constructEvent(payload, signatureHeader, connectWebhookSecret);
            } catch (Exception ignored) {
                // Preserve the same client-safe error for both failures.
            }
        }
        throw firstFailure;
    }

    /** Display-only card metadata persisted instead of raw card data. */
    public static class CardDisplay {
        public final String last4;
        public final String brand;
        public final Long expMonth;
        public final Long expYear;

        CardDisplay(String last4, String brand, Long expMonth, Long expYear) {
            this.last4 = last4;
            this.brand = brand;
            this.expMonth = expMonth;
            this.expYear = expYear;
        }
    }
}

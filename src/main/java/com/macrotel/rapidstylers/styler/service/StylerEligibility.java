package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.payment.service.StripeService;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Answers 'can customers see and book this stylist?' (approved, has an address, payouts ready).
 */
@Component
public class StylerEligibility {
    @Autowired
    StripeService stripeService;

    // ── Professional verification workflow (admin) ─────────────────────

    public boolean isApprovedStyler(StylerEntity styler){
        return VERIFICATION_APPROVED.equals(styler.getVerificationStatus());
    }

    /**
     * True when Stripe payments are live. Null-safe so unit tests that build
     * this class without its collaborators treat payments as off (dev mode).
     */
    private boolean isStripePaymentsLive(){
        return stripeService != null && stripeService.isConfigured();
    }

    /**
     * True when a styler may appear to CUSTOMERS: approved, reachable at a
     * street address, and — when payments are live — Connect onboarding
     * complete, i.e. a profile a customer can actually book end to end. Every
     * customer-facing surface (category tabs, name/province/city/nearby search,
     * the public profile page and the saved list) filters on this rather than
     * approval alone: showing an approved stylist whom booking then rejects is a
     * service failure, not a detail. Admin surfaces (verification queue,
     * approval counts) keep approval-only semantics via
     * {@link #isApprovedStyler}, and approval itself is refused without an
     * address so the queue can never produce a listed-but-unbookable profile.
     */
    public boolean isBookableStyler(StylerEntity styler){
        if(!isApprovedStyler(styler)){
            return false;
        }
        // Visiting the stylist is the default way a booking is delivered, so a
        // profile with nowhere to go cannot be booked. Without this a customer
        // could pay for an appointment and be handed no destination.
        if(!hasStreetAddress(styler)){
            return false;
        }
        return !isStripePaymentsLive() || "COMPLETE".equals(styler.getConnectOnboardingStatus());
    }

    /**
     * Whether a professional's profile says where a customer actually goes.
     * Either address field counts: signup writes the Google-formatted address
     * into {@code businessAddress}, and the structured registration also keeps
     * {@code streetAddress}.
     */
    public static boolean hasStreetAddress(StylerEntity styler){
        if(styler == null) return false;
        return isPresentText(styler.getBusinessAddress()) || isPresentText(styler.getStreetAddress());
    }

    public static boolean isPresentText(String value){
        return value != null && !value.trim().isEmpty();
    }
}

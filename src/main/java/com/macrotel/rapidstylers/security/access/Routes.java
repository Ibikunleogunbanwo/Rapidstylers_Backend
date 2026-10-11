package com.macrotel.rapidstylers.security.access;

/**
 * The guest list: which routes anyone may call and which need a given role.
 * SecurityConfig applies these lists; this class holds data only.
 *
 * Security is closed by default. A route that appears in none of these lists
 * still works, but only for a signed-in account of any role (for example
 * /auth/logout). So forgetting to list a new route fails safe: it answers 401
 * to anonymous callers instead of quietly being public.
 *
 * The x-api-key gate (ApiKeyFilter) applies on top of all of this.
 */
public final class Routes {

    private Routes() {
    }

    /** Framework routes that are not controllers: health probe, error page, API docs. */
    public static final String[] FRAMEWORK_PUBLIC = {
            "/actuator/**",
            "/error",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
    };

    /**
     * Browsing: the catalog, search and location helpers. Public, and the only
     * POST routes (beyond admin work) the read-only demo site still answers.
     */
    public static final String[] BROWSE = {
            "/rapid_stylers/list_identification",
            "/rapid_stylers/list_service",
            "/rapid_stylers/list_sub_service",
            "/rapid_stylers/list_portfolio",
            "/rapid_stylers/list_review",
            "/rapid_stylers/gallery",
            "/rapid_stylers/list_blog",
            "/rapid_stylers/single_blog",
            "/rapid_stylers/list_all_stylers",
            "/rapid_stylers/single_styler",
            "/rapid_stylers/search_styler",
            "/rapid_stylers/search_by_province",
            "/rapid_stylers/search_by_city",
            "/rapid_stylers/search_by_service",
            "/rapid_stylers/search_nearby",
            "/rapid_stylers/booking_estimate",
            "/rapid_stylers/place_autocomplete",
            "/rapid_stylers/place_details",
            "/rapid_stylers/detect-location",
            "/rapid_stylers/reverse-geocode",
            "/rapid_stylers/service_areas",
            "/rapid_stylers/site_content",
            "/rapid_stylers/site_seo",
    };

    /** Anyone may call these: sign-up, sign-in, browsing ({@link #BROWSE}), the Stripe webhook. */
    public static final String[] PUBLIC = join(new String[]{
            // Sign-up, sign-in and password reset
            "/rapid_stylers/generate_sign_up_otp_code",
            "/rapid_stylers/verify_otp_code",
            "/rapid_stylers/create_user_account",
            "/rapid_stylers/styler_generate_otp",
            "/rapid_stylers/styler_verify_otp",
            "/rapid_stylers/create_styler",
            "/rapid_stylers/sign_in",
            "/rapid_stylers/google_sign_in",
            "/rapid_stylers/admin_sign_in",
            "/rapid_stylers/auth/refresh",
            "/rapid_stylers/generate_reset_password_token",
            "/rapid_stylers/reset_user_password",

            // Sign-up funnel visit beacon
            "/rapid_stylers/funnel_visit",

            // Newsletter form and the links in its emails
            "/rapid_stylers/newsletter_subscribe",
            "/rapid_stylers/newsletter_confirm",
            "/rapid_stylers/newsletter_unsubscribe",
            "/rapid_stylers/newsletter_preferences",

            // Signed by Stripe instead of a token
            "/rapid_stylers/stripe/webhook",
    }, BROWSE);

    public static final String[] ADMIN = {
            // Catalog maintenance
            "/rapid_stylers/create_service",
            "/rapid_stylers/update_service",
            "/rapid_stylers/delete_service",
            "/rapid_stylers/create_blog",
            "/rapid_stylers/update_blog",
            "/rapid_stylers/delete_blog",
            "/rapid_stylers/create_identification",
            "/rapid_stylers/update_identification",
            "/rapid_stylers/delete_identification",

            // Stylist verification and moderation
            "/rapid_stylers/admin/styler_verification_queue",
            "/rapid_stylers/admin/update_styler_verification",
            "/rapid_stylers/admin/styler_business_summaries",
            "/rapid_stylers/admin/styler_connect_statuses",
            "/rapid_stylers/admin/all_portfolios",
            "/rapid_stylers/admin/delete_portfolio_image",
            "/rapid_stylers/admin/review_moderation_queue",
            "/rapid_stylers/admin/update_review_moderation",
            "/rapid_stylers/admin/support_tickets",
            "/rapid_stylers/admin/update_support_ticket",
            "/rapid_stylers/list_feedback",

            // Operations
            "/rapid_stylers/admin/kpis",
            "/rapid_stylers/admin/cache_stats",
            "/rapid_stylers/admin/audit_logs",
            "/rapid_stylers/admin/test_email",
            "/rapid_stylers/admin/send_announcement",
            "/rapid_stylers/admin/settings/commission",
            "/rapid_stylers/admin/recovery_campaigns",
            "/rapid_stylers/admin/failed_events",
            "/rapid_stylers/admin/failed_events/{id}/retry",
            // Decryption oracle: any other role could decrypt arbitrary ciphertext under the app key.
            "/rapid_stylers/decrypt",

            // Payments
            "/rapid_stylers/admin/refund",
            "/rapid_stylers/admin/refunds",
            "/rapid_stylers/admin/refund_context",
            "/rapid_stylers/admin/service_disputes",
            "/rapid_stylers/admin/unpaid_payouts",
            "/rapid_stylers/admin/pay_stylist_now",
            "/rapid_stylers/admin/resolve_service_dispute",
            "/rapid_stylers/admin/payment_reconciliation",

            // Bookings
            "/rapid_stylers/admin/bookings",
            "/rapid_stylers/admin/booking_detail",
            "/rapid_stylers/admin/bookings_export",

            // Customers
            "/rapid_stylers/admin/customers",
            "/rapid_stylers/admin/customer_detail",
            "/rapid_stylers/admin/suspend_customer",
            "/rapid_stylers/admin/reinstate_customer",

            // Revenue
            "/rapid_stylers/admin/revenue",
            "/rapid_stylers/admin/revenue_export",

            // Gallery moderation
            "/rapid_stylers/admin/gallery_moderation",
            "/rapid_stylers/admin/gallery_image_history",
            "/rapid_stylers/admin/approve_gallery_image",
            "/rapid_stylers/admin/reject_gallery_image",

            // Sign-up funnel
            "/rapid_stylers/admin/signup_funnel",

            // Locations
            "/rapid_stylers/admin/service_areas",
            "/rapid_stylers/admin/save_service_area",
            "/rapid_stylers/admin/delete_service_area",

            // Promotions
            "/rapid_stylers/admin/promo_codes",
            "/rapid_stylers/admin/save_promo_code",
            "/rapid_stylers/admin/promo_redemptions",

            // Website content
            "/rapid_stylers/admin/site_content",
            "/rapid_stylers/admin/site_content_item",
            "/rapid_stylers/admin/save_site_content",
            "/rapid_stylers/admin/publish_site_content",
            "/rapid_stylers/admin/unpublish_site_content",
            "/rapid_stylers/admin/site_content_history",
            "/rapid_stylers/admin/restore_site_content",
            "/rapid_stylers/admin/preview_email_template",

            // Newsletter
            "/rapid_stylers/admin/newsletter_subscribers",
            "/rapid_stylers/admin/email_suppressions",
            "/rapid_stylers/admin/add_email_suppression",
            "/rapid_stylers/admin/remove_email_suppression",
            "/rapid_stylers/admin/newsletter_campaigns",
            "/rapid_stylers/admin/newsletter_campaign",
            "/rapid_stylers/admin/save_newsletter_campaign",
            "/rapid_stylers/admin/newsletter_audience",
            "/rapid_stylers/admin/schedule_newsletter_campaign",
            "/rapid_stylers/admin/cancel_newsletter_campaign",
            "/rapid_stylers/admin/send_newsletter_test",

            // Admin accounts
            "/rapid_stylers/admin/accounts",
            "/rapid_stylers/admin/accounts/{id}/password",
            "/rapid_stylers/admin/accounts/{id}/disable",
            "/rapid_stylers/admin/accounts/{id}/enable",
    };

    public static final String[] STYLER = {
            "/rapid_stylers/styler_sign_out",

            // Services and portfolio
            "/rapid_stylers/create_sub_service",
            "/rapid_stylers/update_sub_service",
            "/rapid_stylers/styler_own_sub_services",
            "/rapid_stylers/create_portfolio",
            "/rapid_stylers/styler_own_portfolio",
            "/rapid_stylers/delete_portfolio_image",

            // Availability and travel
            "/rapid_stylers/styler_availability",
            "/rapid_stylers/update_styler_availability",
            "/rapid_stylers/styler_availability_exceptions",
            "/rapid_stylers/add_availability_exception",
            "/rapid_stylers/delete_availability_exception",
            "/rapid_stylers/styler/travel_settings",
            "/rapid_stylers/styler/profile",
            "/rapid_stylers/styler/update_personal_info",
            "/rapid_stylers/styler/update_business_info",
            "/rapid_stylers/styler/update_photos",
            "/rapid_stylers/update_styler_travel_settings",

            // Appointments
            "/rapid_stylers/styler_appointments",
            "/rapid_stylers/accept_appointment",
            "/rapid_stylers/decline_appointment",
            "/rapid_stylers/complete_appointment",
            "/rapid_stylers/styler_cancel_appointment",

            // Business
            "/rapid_stylers/styler/connect_account",
            "/rapid_stylers/styler/connect_status",
            "/rapid_stylers/styler/payouts",
            "/rapid_stylers/styler/business_summary",
            "/rapid_stylers/styler/reviews",
    };

    public static final String[] CUSTOMER = {
            // Profile and card
            "/rapid_stylers/user_data",
            "/rapid_stylers/update_user_data",
            "/rapid_stylers/update_user_password",
            "/rapid_stylers/update_card_details",
            "/rapid_stylers/card_setup_intent",

            // Bookings
            "/rapid_stylers/book_appointment",
            "/rapid_stylers/cancel_appointment",
            "/rapid_stylers/confirm_service",
            "/rapid_stylers/dispute_service",
            "/rapid_stylers/retry_appointment_payment",
            "/rapid_stylers/check_promo_code",
            "/rapid_stylers/user_appointments",
            "/rapid_stylers/user_pending_appointments",

            // Engagement
            "/rapid_stylers/create_review",
            "/rapid_stylers/add_feedback",
            "/rapid_stylers/saved_stylists",
            "/rapid_stylers/save_stylist",
            "/rapid_stylers/remove_saved_stylist",
            "/rapid_stylers/notifications",
            "/rapid_stylers/notifications/read",
            "/rapid_stylers/notifications/read_all",
            "/rapid_stylers/notifications/delete",
            "/rapid_stylers/notifications/delete_all",
            "/rapid_stylers/notification_preferences",
            "/rapid_stylers/support_tickets",
            "/rapid_stylers/loyalty_account",
            "/rapid_stylers/apply_referral",
    };

    /**
     * Photo uploads: a signed-in stylist or admin, or a stylist still signing up
     * who shows the upload pass from their email code. Deleting only ever removes
     * an image nothing uses (see ImageReclaimService).
     */
    public static final String[] UPLOAD = {
            "/rapid_stylers/get_upload_signature",
            "/rapid_stylers/delete_cloudinary_image",
    };

    private static String[] join(String[] first, String[] second) {
        String[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}

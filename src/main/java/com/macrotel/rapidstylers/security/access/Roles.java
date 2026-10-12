package com.macrotel.rapidstylers.security.access;

/**
 * The role names a token can carry. Three are accounts; the fourth is a pass
 * that is not an account at all.
 */
public final class Roles {

    private Roles() {
    }

    public static final String CUSTOMER = "CUSTOMER";
    public static final String STYLER = "STYLER";
    /** An owner of the admin console: every admin screen, money and settings included. */
    public static final String ADMIN = "ADMIN";

    /**
     * A day-to-day admin: moderation, verification, support and read-only views
     * of bookings and customers (Routes.STAFF), nothing that moves money or
     * changes settings (Routes.ADMIN).
     */
    public static final String MANAGER = "MANAGER";

    /** Every role that belongs to a real, signed-in account. */
    public static final String[] ACCOUNTS = {CUSTOMER, STYLER, ADMIN, MANAGER};

    /** The admin console roles: an owner or a manager. */
    public static final String[] STAFF = {ADMIN, MANAGER};

    /** True for a role that signs in to the admin console (ADMIN or MANAGER). */
    public static boolean isStaff(String role) {
        return ADMIN.equals(role) || MANAGER.equals(role);
    }

    /**
     * The token role for an admin_accounts row: MANAGER only when the row says
     * so, otherwise ADMIN (the column's default, and every account before
     * managers existed).
     */
    public static String adminAccountRole(String stored) {
        return stored != null && MANAGER.equalsIgnoreCase(stored.trim()) ? MANAGER : ADMIN;
    }

    /**
     * A short-lived pass handed out when a stylist proves their email during
     * sign-up. It lets them upload their profile and ID photos before their
     * account exists, and nothing else.
     */
    public static final String SIGNUP_UPLOAD = "SIGNUP_UPLOAD";
}

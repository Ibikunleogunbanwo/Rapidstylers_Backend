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
    public static final String ADMIN = "ADMIN";

    /** Every role that belongs to a real, signed-in account. */
    public static final String[] ACCOUNTS = {CUSTOMER, STYLER, ADMIN};

    /**
     * A short-lived pass handed out when a stylist proves their email during
     * sign-up. It lets them upload their profile and ID photos before their
     * account exists, and nothing else.
     */
    public static final String SIGNUP_UPLOAD = "SIGNUP_UPLOAD";
}

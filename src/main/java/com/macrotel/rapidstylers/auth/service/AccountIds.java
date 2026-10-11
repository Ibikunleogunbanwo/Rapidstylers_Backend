package com.macrotel.rapidstylers.auth.service;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import org.springframework.stereotype.Component;

/**
 * Hands out customer and stylist ids. Both kinds of account share one id space,
 * because the id is the subject of every sign-in token.
 */
@Component
public class AccountIds {
    private final AppUtils appUtils = new AppUtils();
    private final CustomerRecords customerRecords;
    private final StylerRecords stylerRecords;

    public AccountIds(CustomerRecords customerRecords, StylerRecords stylerRecords) {
        this.customerRecords = customerRecords;
        this.stylerRecords = stylerRecords;
    }

    /**
     * A new customer id: first initial, four random digits, last initial (or U and
     * four digits), never one an account already holds.
     */
    public String newCustomerId(String fname, String lname) {
        java.util.function.Supplier<String> candidate = (!fname.isEmpty() && !lname.isEmpty())
                ? () -> fname.charAt(0) + appUtils.randomDigit(4) + lname.charAt(0)
                : () -> "U" + appUtils.randomDigit(4);
        return freeAccountId(candidate.get(), candidate);
    }

    /**
     * The account id is the subject of every sign-in token, so two accounts
     * sharing one would read and act on each other's bookings, cards and payouts.
     * The ids are short (four random digits), so collisions are real: keep
     * drawing until the id is free in both account tables.
     */
    public String freeAccountId(String first, java.util.function.Supplier<String> next) {
        String id = first;
        for (int attempt = 0; attempt < 100; attempt++) {
            if (customerRecords.findByUserId(id).isEmpty() && stylerRecords.findByStylerId(id).isEmpty()) {
                return id;
            }
            id = next.get();
        }
        throw new IllegalStateException("No free account id after 100 attempts");
    }

    /**
     * The capital A-Z letter an account id takes from a name. Accents are dropped
     * (É gives E) and a name with no Latin letter gives X, so ids stay plain
     * ASCII and safe in links whatever alphabet the name is written in.
     */
    public static String initial(String name) {
        String plain = java.text.Normalizer.normalize(name == null ? "" : name, java.text.Normalizer.Form.NFD)
                .replaceAll("[^A-Za-z]", "");
        return plain.isEmpty() ? "X" : plain.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }
}

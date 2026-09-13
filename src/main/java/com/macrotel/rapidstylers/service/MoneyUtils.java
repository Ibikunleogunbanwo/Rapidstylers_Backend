package com.macrotel.rapidstylers.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Static money conversion/format helpers shared across services. Prices are
 * carried as display strings ("165.00"); Stripe needs minor units (cents).
 */
public final class MoneyUtils {

    private MoneyUtils() {
    }

    /** Parses a display price, tolerating commas; 2 decimal places, HALF_UP. */
    public static BigDecimal amount(String value) {
        String normalized = value == null ? "0" : value.replace(",", "").trim();
        return new BigDecimal(normalized).setScale(2, RoundingMode.HALF_UP);
    }

    /** Formats a BigDecimal as a plain 2-decimal-place string. */
    public static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Converts a display price like "165.00" into Stripe's minor-unit amount (cents). */
    public static long centsFromPrice(String price) {
        if (price == null || price.trim().isEmpty()) return 0L;
        try {
            return new BigDecimal(price.replaceAll("[^0-9.]", ""))
                    .multiply(BigDecimal.valueOf(100)).longValue();
        } catch (Exception ex) {
            return 0L;
        }
    }

    /** Formats minor-unit cents as a 2-decimal-place string. */
    public static String moneyCents(long cents) {
        return money(BigDecimal.valueOf(cents).movePointLeft(2));
    }
}

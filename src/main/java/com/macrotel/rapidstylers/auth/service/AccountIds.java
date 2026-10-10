package com.macrotel.rapidstylers.auth.service;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.springframework.stereotype.Component;

/**
 * Hands out customer and stylist ids. Both kinds of account share one id space,
 * because the id is the subject of every sign-in token.
 */
@Component
public class AccountIds {
    private final AppUtils appUtils = new AppUtils();
    private final UserRepo userRepo;
    private final StylerRepo stylerRepo;

    public AccountIds(UserRepo userRepo, StylerRepo stylerRepo) {
        this.userRepo = userRepo;
        this.stylerRepo = stylerRepo;
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
            if (userRepo.findByUserId(id).isEmpty() && stylerRepo.findByStylerId(id).isEmpty()) {
                return id;
            }
            id = next.get();
        }
        throw new IllegalStateException("No free account id after 100 attempts");
    }
}

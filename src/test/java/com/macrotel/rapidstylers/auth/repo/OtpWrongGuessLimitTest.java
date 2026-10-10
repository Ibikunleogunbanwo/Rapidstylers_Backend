package com.macrotel.rapidstylers.auth.repo;

import com.macrotel.rapidstylers.auth.entity.OTPEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the wrong-guess counter against real MySQL, because its correctness rests
 * on MySQL applying SET clauses left to right: the CASE must see the new count.
 */
@SpringBootTest
class OtpWrongGuessLimitTest {

    private static final int MAX = 5;

    @Autowired
    OTPRepo otpRepo;

    private Long otpId;

    @AfterEach
    void cleanUp() {
        if (otpId != null) {
            otpRepo.deleteById(otpId);
        }
    }

    @Test
    void theCodeClosesOnTheLastAllowedWrongGuessAndStaysClosed() {
        OTPEntity otp = new OTPEntity();
        otp.setEmailAddress("guess.limit." + System.nanoTime() + "@rapidstylers.test");
        otp.setPurpose("USER SIGN UP");
        otp.setCode("not-a-real-hash");
        otpId = otpRepo.save(otp).getId();

        for (int guess = 1; guess < MAX; guess++) {
            assertEquals(1, otpRepo.recordWrongGuess(otpId, MAX));
            assertEquals(OTPEntity.OPEN, otpRepo.findById(otpId).orElseThrow().getIsUsed(),
                    "the code must stay open after wrong guess " + guess);
        }

        assertEquals(1, otpRepo.recordWrongGuess(otpId, MAX));
        OTPEntity closed = otpRepo.findById(otpId).orElseThrow();
        assertEquals(OTPEntity.LOCKED, closed.getIsUsed());
        assertEquals(MAX, closed.getFailedAttempts());

        // A closed code is no longer offered for verification, and is not counted again.
        assertEquals(0, otpRepo.recordWrongGuess(otpId, MAX));
        assertTrue(otpRepo.findLatestUnusedByEmail(closed.getEmailAddress()).isEmpty());
    }
}

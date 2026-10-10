package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.engagement.entity.LoyaltyAccountEntity;
import com.macrotel.rapidstylers.engagement.repo.LoyaltyAccountRepo;
import com.macrotel.rapidstylers.engagement.repo.ReferralRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A friend's code is applied once, and pays the friend when the first booking completes. */
@SpringBootTest
class ReferralFlowTest {

    @Autowired LoyaltyService loyaltyService;
    @Autowired LoyaltyAccountRepo loyaltyAccountRepo;
    @Autowired ReferralRepo referralRepo;

    private final String friend = "REFA" + System.nanoTime();
    private final String newcomer = "REFB" + System.nanoTime();

    @AfterEach
    void cleanUp() {
        referralRepo.findByReferredUserId(newcomer).ifPresent(referralRepo::delete);
        loyaltyAccountRepo.findByUserId(friend).ifPresent(loyaltyAccountRepo::delete);
        loyaltyAccountRepo.findByUserId(newcomer).ifPresent(loyaltyAccountRepo::delete);
    }

    @Test
    void theCodeIsAppliedOnceAndRewardsTheFriendOnCompletion() {
        String code = "RS-T" + Long.toString(System.nanoTime(), 36).toUpperCase();
        loyaltyAccountRepo.save(new LoyaltyAccountEntity(friend, code));

        assertEquals("200", loyaltyService.createReferral(newcomer, code.toLowerCase()).getStatusCode());
        assertEquals("Referral already applied", loyaltyService.createReferral(newcomer, code).getMessage());
        assertEquals("Invalid referral code", loyaltyService.createReferral(friend, code).getMessage());

        loyaltyService.awardCompletionPoints(newcomer, "APPT-REF");
        loyaltyService.awardCompletionPoints(newcomer, "APPT-REF2");

        assertEquals(25, loyaltyAccountRepo.findByUserId(friend).get().getPoints());
        assertEquals(20, loyaltyAccountRepo.findByUserId(newcomer).get().getPoints());
        assertEquals("COMPLETED", referralRepo.findByReferredUserId(newcomer).get().getStatus());
    }
}

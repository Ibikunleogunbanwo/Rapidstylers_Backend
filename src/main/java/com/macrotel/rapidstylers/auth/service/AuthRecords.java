package com.macrotel.rapidstylers.auth.service;

import com.macrotel.rapidstylers.auth.entity.OTPEntity;
import com.macrotel.rapidstylers.auth.repo.OTPRepo;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The auth domain's front door for other domains: email codes. Only auth
 * code reaches {@link OTPRepo} directly.
 */
@Service
public class AuthRecords {

    @Autowired
    OTPRepo otpRepo;

    public AuthRecords() {
    }

    public AuthRecords(OTPRepo otpRepo) {
        this.otpRepo = otpRepo;
    }

    /** The most recently verified code for this email and purpose, if any. */
    public Optional<OTPEntity> verifiedOtpFor(String emailAddress, String purpose) {
        return otpRepo.verifyOtpSuccessForPurpose(emailAddress, purpose);
    }

    /** One row per sign-up email: email, last stage date, stage, converted (admin funnel). */
    public java.util.List<Object[]> findRecoveryCampaigns() {
        return otpRepo.findRecoveryCampaigns();
    }

    /** Sign-ups that started but never finished, for the follow-up emails. */
    public java.util.List<Object[]> findSignupAttemptCandidates() {
        return otpRepo.findSignupAttemptCandidates();
    }

    public void markLatestSignupStage(String email, Integer stage, java.time.LocalDateTime now) {
        otpRepo.markLatestSignupStage(email, stage, now);
    }

    public int deleteOlderThan(String cutoff) {
        return otpRepo.deleteOlderThan(cutoff);
    }
}

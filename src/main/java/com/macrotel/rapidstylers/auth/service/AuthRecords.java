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
}

package com.macrotel.rapidstylers.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stylist sign-up photo pass has to outlast a person who pauses before the
 * last step (the photos only upload then), yet end before the sweep that treats
 * unused sign-up photos as abandoned, or a slow sign-up could lose its photos.
 */
class SignupUploadPassLifetimeTest {

    @Test
    void thePassOutlastsAPausedSignUp() {
        assertTrue(OtpService.SIGNUP_UPLOAD_TTL.compareTo(Duration.ofHours(6)) >= 0);
    }

    @Test
    void thePassEndsBeforeUnusedSignUpPhotosAreReclaimed() {
        // ImageReclaimService's app.uploads.abandoned-after-hours default.
        assertTrue(OtpService.SIGNUP_UPLOAD_TTL.compareTo(Duration.ofHours(24)) < 0);
    }
}

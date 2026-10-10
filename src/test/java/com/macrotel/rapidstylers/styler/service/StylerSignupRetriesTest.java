package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.pojo.StylerData;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A stylist sign-up sent again after a lost response is told to sign in. */
class StylerSignupRetriesTest {

    private StylerSignupRetries retries;
    private StylerEntity existing;

    @BeforeEach
    void setUp() {
        retries = new StylerSignupRetries();
        retries.stylerRepo = mock(StylerRepo.class);
        existing = new StylerEntity();
        existing.setEmailAddress("chloe@example.com");
        existing.setPhoneNumber("5875550100");
        existing.setProfileImageUrl("https://res.cloudinary.com/x/image/upload/rapid_stylers/profile/face.jpg");
        existing.setIdentificationImageUrl("https://res.cloudinary.com/x/image/upload/rapid_stylers/id/card.jpg");
        existing.setTermsAcceptedAt(LocalDateTime.now().minusMinutes(3));
        when(retries.stylerRepo.findByEmailAddress("chloe@example.com")).thenReturn(Optional.of(existing));
    }

    private StylerData sameSignup() {
        StylerData data = new StylerData();
        data.setEmailAddress("chloe@example.com");
        data.setPhoneNumber("5875550100");
        data.setProfileImageUrl(existing.getProfileImageUrl());
        data.setIdentificationImageUrl(existing.getIdentificationImageUrl());
        return data;
    }

    @Test
    void theSameSignUpAgainIsToldItsAccountExists() {
        BaseResponse response = retries.alreadyCreated(sameSignup());

        assertEquals("200", response.getStatusCode());
        assertEquals("Your account was already created. Please sign in.", response.getMessage());
        assertEquals(true, ((Map<?, ?>) response.getData()).get("alreadyCreated"));
    }

    @Test
    void anotherPersonUsingTheEmailStillGetsTheUsualRefusal() {
        StylerData other = sameSignup();
        other.setPhoneNumber("4035550199");

        assertNull(retries.alreadyCreated(other));
    }

    @Test
    void anOldAccountIsNotARetry() {
        existing.setTermsAcceptedAt(LocalDateTime.now().minusDays(3));

        assertNull(retries.alreadyCreated(sameSignup()));
    }
}

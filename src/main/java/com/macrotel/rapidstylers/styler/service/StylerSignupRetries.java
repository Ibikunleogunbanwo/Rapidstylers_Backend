package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.pojo.StylerData;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * A stylist sign-up sent again after its response was lost. Without this the
 * retry reads "email already exists" and the stylist cannot tell whether their
 * account was made; with it they are told to sign in.
 */
@Component
public class StylerSignupRetries {

    @Autowired
    StylerRepo stylerRepo;

    /**
     * The same sign-up sent twice (the first response was lost): an account with
     * this email and phone number, created in the last day, with these photos.
     */
    public BaseResponse alreadyCreated(StylerData stylerData){
        Optional<StylerEntity> existing = stylerData.getEmailAddress() == null ? Optional.empty()
                : stylerRepo.findByEmailAddress(stylerData.getEmailAddress());
        if(existing.isEmpty()) return null;
        StylerEntity styler = existing.get();
        boolean sameSignup = java.util.Objects.equals(styler.getPhoneNumber(), stylerData.getPhoneNumber())
                && java.util.Objects.equals(styler.getProfileImageUrl(), stylerData.getProfileImageUrl())
                && java.util.Objects.equals(styler.getIdentificationImageUrl(), stylerData.getIdentificationImageUrl())
                && styler.getTermsAcceptedAt() != null
                && styler.getTermsAcceptedAt().isAfter(LocalDateTime.now().minusDays(1));
        if(!sameSignup) return null;
        BaseResponse response = new BaseResponse(true);
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage("Your account was already created. Please sign in.");
        response.setData(Collections.singletonMap("alreadyCreated", true));
        return response;
    }
}

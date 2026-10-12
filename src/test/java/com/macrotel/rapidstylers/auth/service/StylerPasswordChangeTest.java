package com.macrotel.rapidstylers.auth.service;

import com.macrotel.rapidstylers.auth.pojo.ChangePasswordData;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.security.access.Roles;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.util.Optional;
import javax.validation.Validation;
import javax.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A signed-in stylist changes their own password (POST /update_styler_password):
 * the old one must match, the account is the token's, every other device is
 * signed out and this one gets a fresh session — as for customers.
 */
class StylerPasswordChangeTest {

    private static final String STYLER_ID = "JS1234";
    private AuthService authService;
    private StylerRepo stylerRepo;
    private RefreshTokenService refreshTokenService;
    private StylerEntity styler;

    @BeforeEach
    void setUp() {
        authService = new AuthService();
        stylerRepo = mock(StylerRepo.class);
        refreshTokenService = mock(RefreshTokenService.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        authService.stylerRecords = new StylerRecords(stylerRepo, null);
        authService.refreshTokenService = refreshTokenService;
        authService.jwtUtil = jwtUtil;
        authService.appUtils = new AppUtils();
        styler = new StylerEntity();
        styler.setStylerId(STYLER_ID);
        styler.setPassword(new AppUtils().encryptPassword("OldPass1!"));
        when(stylerRepo.findByStylerId(STYLER_ID)).thenReturn(Optional.of(styler));
        when(jwtUtil.generateToken(STYLER_ID, Roles.STYLER)).thenReturn("fresh-token");
        when(refreshTokenService.issue(STYLER_ID, Roles.STYLER)).thenReturn("fresh-refresh");
    }

    @Test
    void changesThePasswordAndSignsOutOtherDevices() {
        BaseResponse response = authService.updateStylerPassword(change("OldPass1!", "NewPass1!", "NewPass1!"), STYLER_ID);

        assertEquals("200", response.getStatusCode());
        assertEquals("Password Change Successfully", response.getMessage());
        assertTrue(new AppUtils().passwordMatches("NewPass1!", styler.getPassword()));
        verify(stylerRepo).save(styler);
        verify(refreshTokenService).revokeAllForAccount(STYLER_ID, Roles.STYLER);
        assertEquals("fresh-token", response.getToken());
        assertEquals("fresh-refresh", response.getRefreshToken());
    }

    @Test
    void aWrongOldPasswordChangesNothing() {
        BaseResponse response = authService.updateStylerPassword(change("Wrong1!", "NewPass1!", "NewPass1!"), STYLER_ID);

        assertEquals("400", response.getStatusCode());
        assertEquals("Invalid Old Password", response.getMessage());
        verify(stylerRepo, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForAccount(anyString(), anyString());
    }

    @Test
    void aMismatchedConfirmationChangesNothing() {
        BaseResponse response = authService.updateStylerPassword(change("OldPass1!", "NewPass1!", "NewPass2!"), STYLER_ID);

        assertEquals("400", response.getStatusCode());
        assertTrue(response.getMessage().contains("does not match"));
        verify(stylerRepo, never()).save(any());
    }

    @Test
    void anUnknownStylerIsRefused() {
        when(stylerRepo.findByStylerId("NOPE")).thenReturn(Optional.empty());

        BaseResponse response = authService.updateStylerPassword(change("OldPass1!", "NewPass1!", "NewPass1!"), "NOPE");

        assertEquals("400", response.getStatusCode());
        verify(stylerRepo, never()).save(any());
    }

    @Test
    void theNewPasswordMustMeetTheSignUpRule() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertFalse(validator.validate(change("OldPass1!", "weak", "weak")).isEmpty());
        assertFalse(validator.validate(change("", "NewPass1!", "NewPass1!")).isEmpty());
        assertTrue(validator.validate(change("OldPass1!", "NewPass1!", "NewPass1!")).isEmpty());
    }

    private ChangePasswordData change(String oldPassword, String password, String confirmPassword) {
        ChangePasswordData data = new ChangePasswordData();
        data.setOldPassword(oldPassword);
        data.setPassword(password);
        data.setConfirmPassword(confirmPassword);
        return data;
    }
}

package com.macrotel.rapidstylers.auth.controller;

import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.macrotel.rapidstylers.auth.pojo.OTPData;
import com.macrotel.rapidstylers.auth.service.AuthService;
import com.macrotel.rapidstylers.auth.service.OtpService;
import com.macrotel.rapidstylers.auth.service.SignupTokenService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.pojo.UserData;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

class SignupTokenFlowTest {

    private AuthController controller;
    private OtpService otpService;
    private AuthService authService;
    private final SignupTokenService tokens = new SignupTokenService("test-secret-for-signup-tokens-0123456789");

    private static BaseResponse ok(Object data) {
        BaseResponse response = new BaseResponse();
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage("ok");
        response.setData(data);
        return response;
    }

    @BeforeEach
    void setUp() {
        controller = new AuthController();
        otpService = mock(OtpService.class);
        authService = mock(AuthService.class);
        controller.otpService = otpService;
        controller.authService = authService;
        controller.signupTokenService = tokens;
    }

    @Test
    void stepOneAnswersWithATokenInsteadOfTheAddress() {
        when(otpService.generateSignUpOtpCode(any())).thenReturn(ok(Map.of()));
        OTPData data = new OTPData();
        data.setEmailAddress("ada@example.com");
        ResponseEntity<BaseResponse> response = controller.generateSignUpOtpCode(data);
        String token = ((Map<?, ?>) response.getBody().getData()).get("signupToken").toString();
        assertEquals("ada@example.com", tokens.resolve(token).orElseThrow());
    }

    @Test
    void laterStepsSendOnlyTheTokenAndNeverGetTheAddressBack() {
        String token = tokens.issue("ada@example.com");
        when(otpService.verifyUserOTP("ada@example.com", "123456")).thenReturn(ok(Map.of("emailAddress", "ada@example.com")));
        ResponseEntity<BaseResponse> verified = controller.verifyOtpCode(Map.of("signupToken", token, "otpCode", "123456"));
        Map<?, ?> data = (Map<?, ?>) verified.getBody().getData();
        assertFalse(data.containsKey("emailAddress"), "the response must not echo the address");
        assertTrue(data.containsKey("signupToken"));

        when(authService.userSignUp(any())).thenReturn(ok(Map.of()));
        UserData user = new UserData();
        user.setSignupToken(token);
        controller.createUserAccount(user);
        ArgumentCaptor<UserData> created = ArgumentCaptor.forClass(UserData.class);
        verify(authService).userSignUp(created.capture());
        assertEquals("ada@example.com", created.getValue().getEmailAddress());
    }

    @Test
    void anExpiredOrForgedTokenStopsTheStep() {
        UserData user = new UserData();
        user.setSignupToken("st1.forged");
        assertEquals(400, controller.createUserAccount(user).getStatusCodeValue());
        verify(authService, never()).userSignUp(any());
        assertEquals(400, controller.verifyOtpCode(Map.of("signupToken", "st1.forged", "otpCode", "1")).getStatusCodeValue());
        verify(otpService, never()).verifyUserOTP(any(), eq("1"));
    }
}

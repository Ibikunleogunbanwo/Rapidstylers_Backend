package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.RefundRequestData;
import com.macrotel.rapidstylers.service.AppService;
import com.macrotel.rapidstylers.service.StepUpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the admin refund step-up gate (X-Step-Up-Password): a valid ADMIN
 * session alone must NOT be enough to move money. Mirrors the live verification —
 * /admin/refund without the header returns 403; with the correct password it passes
 * the gate and proceeds to the refund logic.
 */
class AdminRefundStepUpTest {

    private static final String ADMIN_ID = "admin@rapidstylers.com";
    private static final String CORRECT_PASSWORD = "correct-admin-pass";

    private ApplicationController controller;
    private StepUpService stepUpService;
    private AppService appService;

    @BeforeEach
    void setUp() {
        controller = new ApplicationController();
        stepUpService = mock(StepUpService.class);
        appService = mock(AppService.class);
        ReflectionTestUtils.setField(controller, "stepUpService", stepUpService);
        ReflectionTestUtils.setField(controller, "appService", appService);
    }

    private MockHttpServletRequest adminSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("role", "ADMIN");
        request.setAttribute("accountId", ADMIN_ID);
        return request;
    }

    @Test
    void missingStepUpHeaderReturns403() {
        MockHttpServletRequest request = adminSession(); // no X-Step-Up-Password header
        when(stepUpService.verify(anyString(), isNull())).thenReturn(false);

        ResponseEntity<BaseResponse> res =
                controller.adminRefund(new RefundRequestData(), request);

        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertEquals("403", res.getBody().getStatusCode());
        assertTrue(res.getBody().getMessage().contains("Re-authentication required"));
        // the money-movement path must never run without step-up
        verify(appService, never()).adminRefund(anyString(), any());
    }

    @Test
    void wrongPasswordStillReturns403() {
        MockHttpServletRequest request = adminSession();
        request.addHeader("X-Step-Up-Password", "wrong-password");
        when(stepUpService.verify(anyString(), anyString())).thenReturn(false);

        ResponseEntity<BaseResponse> res =
                controller.adminRefund(new RefundRequestData(), request);

        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertEquals("403", res.getBody().getStatusCode());
        verify(appService, never()).adminRefund(anyString(), any());
    }

    @Test
    void correctPasswordPassesGateAndProceedsToRefund() {
        MockHttpServletRequest request = adminSession();
        request.addHeader("X-Step-Up-Password", CORRECT_PASSWORD);
        when(stepUpService.verify(ADMIN_ID, CORRECT_PASSWORD)).thenReturn(true);
        BaseResponse success = new BaseResponse();
        success.setStatusCode("200");
        success.setMessage("Successful");
        when(appService.adminRefund(eq(ADMIN_ID), any())).thenReturn(success);

        ResponseEntity<BaseResponse> res =
                controller.adminRefund(new RefundRequestData(), request);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("200", res.getBody().getStatusCode());
        // the gate must have opened: password verified AND refund path reached
        verify(stepUpService).verify(ADMIN_ID, CORRECT_PASSWORD);
        verify(appService).adminRefund(eq(ADMIN_ID), any());
    }

    @Test
    void nonAdminSessionCannotReachStepUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("role", "CUSTOMER");
        request.setAttribute("accountId", "cust@example.com");

        ResponseEntity<BaseResponse> res =
                controller.adminRefund(new RefundRequestData(), request);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        assertEquals("401", res.getBody().getStatusCode());
        // step-up must not even be consulted for non-admin role
        verify(stepUpService, never()).verify(any(), any());
        verify(appService, never()).adminRefund(anyString(), any());
    }
}
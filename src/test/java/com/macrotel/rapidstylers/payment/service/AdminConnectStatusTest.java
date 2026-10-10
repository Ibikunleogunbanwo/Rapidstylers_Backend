package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.support.TestServices;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminConnectStatusTest {

    private TestServices services;
    private StylerRepo stylerRepo;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        stylerRepo = mock(StylerRepo.class);
        services.stylerRepo = stylerRepo;
    }

    @Test
    void listsAllStylersWithConnectFieldsOrderedProblemsFirst() {
        StylerEntity complete = styler("S1", "COMPLETE", "acct_1", "APPROVED");
        StylerEntity rejected = styler("S2", "REJECTED", "acct_2", "APPROVED");
        rejected.setConnectDisabledReason("rejected.other");
        StylerEntity notStarted = styler("S3", null, null, "PENDING");
        StylerEntity pending = styler("S4", "PENDING", "acct_4", "APPROVED");
        when(stylerRepo.findAll()).thenReturn(Arrays.asList(complete, rejected, notStarted, pending));

        BaseResponse response = services.connectAccountService().getAdminStylerConnectStatuses();

        assertEquals("200", response.getStatusCode());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getData();
        assertEquals(4, rows.size());
        assertEquals("S2", rows.get(0).get("stylerId")); // REJECTED surfaces first
        assertEquals("rejected.other", rows.get(0).get("disabledReason"));
        assertEquals("S4", rows.get(1).get("stylerId")); // PENDING
        assertEquals("S3", rows.get(2).get("stylerId")); // NOT_STARTED
        assertEquals("S1", rows.get(3).get("stylerId")); // COMPLETE
        assertEquals("acct_1", rows.get(3).get("connectAccountId"));
        assertEquals("APPROVED", rows.get(3).get("verificationStatus"));
        assertEquals("FirstS1 Last", rows.get(3).get("name"));
        assertEquals(true, rows.get(3).get("accountActive"));
    }

    @Test
    void notStartedStylerReportsNullAccountAndReason() {
        StylerEntity styler = styler("S3", null, null, "PENDING");
        when(stylerRepo.findAll()).thenReturn(Arrays.asList(styler));

        BaseResponse response = services.connectAccountService().getAdminStylerConnectStatuses();

        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getData();
        assertEquals("NOT_STARTED", rows.get(0).get("connectStatus"));
        assertEquals(null, rows.get(0).get("connectAccountId"));
        assertEquals(null, rows.get(0).get("disabledReason"));
    }

    private StylerEntity styler(String id, String connectStatus, String accountId, String verification) {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId(id);
        styler.setFirstname("First" + id);
        styler.setLastname("Last");
        styler.setEmailAddress(id + "@example.com");
        styler.setStatus("0");
        styler.setVerificationStatus(verification);
        styler.setConnectOnboardingStatus(connectStatus);
        styler.setStripeConnectAccountId(accountId);
        return styler;
    }
}

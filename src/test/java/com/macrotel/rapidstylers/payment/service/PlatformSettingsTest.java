package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.payment.entity.PlatformSettingEntity;
import com.macrotel.rapidstylers.payment.repo.PlatformSettingRepo;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformSettingsTest {

    // Commission settings logic lives in PaymentService; this unit test targets it directly.
    private PaymentService paymentOps;
    private PlatformSettingRepo platformSettingRepo;

    @BeforeEach
    void setUp() {
        paymentOps = new PaymentService();
        platformSettingRepo = mock(PlatformSettingRepo.class);
        paymentOps.platformSettingRepo = platformSettingRepo;
        // audit() is a no-op on a bare AuditService (null repo guard).
        paymentOps.auditService = new AuditService();
        // .env default (application.properties seed).
        ReflectionTestUtils.setField(paymentOps, "stripeCommissionPercent", 12.0);
        ReflectionTestUtils.setField(paymentOps, "cachedCommissionPercent", null);
    }

    @Test
    void fallsBackToEnvDefaultWhenNoSettingRowExists() {
        when(platformSettingRepo.findBySettingKey(anyString())).thenReturn(Optional.empty());
        BaseResponse response = paymentOps.getCommissionSetting("ADMIN1");
        assertEquals(12.0, ((Number) ((Map<?, ?>) response.getData()).get("commissionPercent")).doubleValue());
    }

    @Test
    void databaseSeedOverridesEnvDefaultAtStartup() {
        when(platformSettingRepo.findBySettingKey(anyString()))
                .thenReturn(Optional.of(new PlatformSettingEntity("commission_percent", "15.5")));
        paymentOps.loadCommissionSetting();
        BaseResponse response = paymentOps.getCommissionSetting("ADMIN1");
        assertEquals(15.5, ((Number) ((Map<?, ?>) response.getData()).get("commissionPercent")).doubleValue());
    }

    @Test
    void adminUpdatePersistsAndCachesNewValue() {
        when(platformSettingRepo.findBySettingKey(anyString())).thenReturn(Optional.empty());
        BaseResponse response = paymentOps.updateCommissionSetting("ADMIN1", 12.5);
        assertEquals("200", response.getStatusCode());

        ArgumentCaptor<PlatformSettingEntity> captor = ArgumentCaptor.forClass(PlatformSettingEntity.class);
        verify(platformSettingRepo).save(captor.capture());
        assertEquals("commission_percent", captor.getValue().getSettingKey());
        assertEquals("12.5", captor.getValue().getSettingValue());

        BaseResponse after = paymentOps.getCommissionSetting("ADMIN1");
        assertEquals(12.5, ((Number) ((Map<?, ?>) after.getData()).get("commissionPercent")).doubleValue());
    }

    @Test
    void rejectsOutOfRangeCommission() {
        BaseResponse response = paymentOps.updateCommissionSetting("ADMIN1", 150);
        assertEquals("400", response.getStatusCode());
    }

    @Test
    void commissionIsCappedAtFiftyPercent() {
        assertEquals("200", paymentOps.updateCommissionSetting("ADMIN1", 50).getStatusCode());
        assertEquals("200", paymentOps.updateCommissionSetting("ADMIN1", 0).getStatusCode());
        BaseResponse tooHigh = paymentOps.updateCommissionSetting("ADMIN1", 50.5);
        assertEquals("400", tooHigh.getStatusCode());
        assertEquals("Commission must be between 0 and 50", tooHigh.getMessage());
        assertEquals("400", paymentOps.updateCommissionSetting("ADMIN1", -1).getStatusCode());
        // What the controller passes for a missing or unreadable value.
        assertEquals("400", paymentOps.updateCommissionSetting("ADMIN1", Double.NaN).getStatusCode());
    }

    @Test
    void theAuditLogRecordsOldAndNewValue() {
        AuditService audit = mock(AuditService.class);
        paymentOps.auditService = audit;
        when(platformSettingRepo.findBySettingKey(anyString())).thenReturn(Optional.empty());

        paymentOps.updateCommissionSetting("admin@example.com", 15);

        verify(audit).audit("admin@example.com", "ADMIN", "UPDATE_COMMISSION", "SETTINGS", "commission_percent", "12.0 -> 15.0");
    }
}

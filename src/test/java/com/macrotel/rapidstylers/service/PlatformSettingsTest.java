package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.PlatformSettingEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.PlatformSettingRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformSettingsTest {

    // Commission settings logic now lives in PaymentOpsService (carved out of
    // AppService — see docs/simplification-plan.md); this unit test targets it directly.
    private PaymentOpsService paymentOps;
    private PlatformSettingRepo platformSettingRepo;

    @BeforeEach
    void setUp() {
        paymentOps = new PaymentOpsService();
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
}

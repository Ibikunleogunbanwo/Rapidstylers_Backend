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

    private AppService appService;
    private PlatformSettingRepo platformSettingRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        platformSettingRepo = mock(PlatformSettingRepo.class);
        appService.platformSettingRepo = platformSettingRepo;
        // .env default (application.properties seed).
        ReflectionTestUtils.setField(appService, "stripeCommissionPercent", 10.0);
        ReflectionTestUtils.setField(appService, "cachedCommissionPercent", null);
    }

    @Test
    void fallsBackToEnvDefaultWhenNoSettingRowExists() {
        when(platformSettingRepo.findBySettingKey(anyString())).thenReturn(Optional.empty());
        BaseResponse response = appService.getCommissionSetting("ADMIN1");
        assertEquals(10.0, ((Number) ((Map<?, ?>) response.getData()).get("commissionPercent")).doubleValue());
    }

    @Test
    void databaseSeedOverridesEnvDefaultAtStartup() {
        when(platformSettingRepo.findBySettingKey(anyString()))
                .thenReturn(Optional.of(new PlatformSettingEntity("commission_percent", "15.5")));
        appService.loadCommissionSetting();
        BaseResponse response = appService.getCommissionSetting("ADMIN1");
        assertEquals(15.5, ((Number) ((Map<?, ?>) response.getData()).get("commissionPercent")).doubleValue());
    }

    @Test
    void adminUpdatePersistsAndCachesNewValue() {
        when(platformSettingRepo.findBySettingKey(anyString())).thenReturn(Optional.empty());
        BaseResponse response = appService.updateCommissionSetting("ADMIN1", 12.5);
        assertEquals("200", response.getStatusCode());

        ArgumentCaptor<PlatformSettingEntity> captor = ArgumentCaptor.forClass(PlatformSettingEntity.class);
        verify(platformSettingRepo).save(captor.capture());
        assertEquals("commission_percent", captor.getValue().getSettingKey());
        assertEquals("12.5", captor.getValue().getSettingValue());

        BaseResponse after = appService.getCommissionSetting("ADMIN1");
        assertEquals(12.5, ((Number) ((Map<?, ?>) after.getData()).get("commissionPercent")).doubleValue());
    }

    @Test
    void rejectsOutOfRangeCommission() {
        BaseResponse response = appService.updateCommissionSetting("ADMIN1", 150);
        assertEquals("400", response.getStatusCode());
    }
}

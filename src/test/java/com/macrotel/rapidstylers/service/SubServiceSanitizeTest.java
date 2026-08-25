package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.dto.SubServiceDTO;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.ServiceUpdateData;
import com.macrotel.rapidstylers.pojo.SubServiceData;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubServiceSanitizeTest {

    private AppService appService;
    private StylerRepo stylerRepo;
    private SubServiceRepo subServiceRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        stylerRepo = mock(StylerRepo.class);
        subServiceRepo = mock(SubServiceRepo.class);
        appService.stylerRepo = stylerRepo;
        appService.subServiceRepo = subServiceRepo;
    }

    @Test
    void createSanitizesSubServiceName() {
        when(stylerRepo.findByStylerId("S1")).thenReturn(Optional.of(new StylerEntity()));

        SubServiceData data = new SubServiceData();
        data.setStylerId("S1");
        data.setName("Box Braids <script>alert(1)</script>");
        data.setPrice("150.00");
        data.setDurationMinutes(60);

        BaseResponse response = appService.createSubService(data);

        assertEquals("200", response.getStatusCode());
        ArgumentCaptor<SubServiceEntity> captor = ArgumentCaptor.forClass(SubServiceEntity.class);
        verify(subServiceRepo).save(captor.capture());
        assertEquals("Box Braids", captor.getValue().getName());
    }

    @Test
    void updateSanitizesSubServiceName() {
        SubServiceEntity existing = new SubServiceEntity();
        existing.setName("Old Name");
        existing.setPrice("100.00");
        when(subServiceRepo.isServiceExistById("S1", 5L)).thenReturn(Optional.of(existing));
        appService.dtoService = mock(DTOService.class);
        when(appService.dtoService.subServiceDTO(existing)).thenReturn(new SubServiceDTO());

        ServiceUpdateData data = new ServiceUpdateData();
        data.setId(5L);
        data.setName("New <b>Name</b> <img src=x onerror=alert(1)>");
        data.setPrice("120.00");
        data.setDurationMinutes(60);

        BaseResponse response = appService.updateSubService("S1", data);

        assertEquals("200", response.getStatusCode());
        assertEquals("New Name", existing.getName());
    }
}

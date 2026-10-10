package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.support.TestServices;
import com.macrotel.rapidstylers.styler.entity.AvailabilityEntity;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.pojo.AvailabilityData;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.styler.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvailabilityValidationTest {

    private TestServices services;
    private StylerRepo stylerRepo;
    private AvailabilityRepo availabilityRepo;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        stylerRepo = mock(StylerRepo.class);
        availabilityRepo = mock(AvailabilityRepo.class);
        services.stylerRepo = stylerRepo;
        services.availabilityRepo = availabilityRepo;
        when(stylerRepo.findByStylerId("S1")).thenReturn(Optional.of(new StylerEntity()));
    }

    private AvailabilityData slot(String day, String start, String end) {
        AvailabilityData slot = new AvailabilityData();
        slot.setDayOfWeek(day);
        slot.setStartTime(start);
        slot.setEndTime(end);
        return slot;
    }

    @Test
    void rejectsDayOfWeekOutOfRange() {
        BaseResponse response = services.availabilityService().updateStylerAvailability("S1", List.of(slot("7", "09:00", "17:00")));
        assertEquals("400", response.getStatusCode());
        verify(availabilityRepo, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void rejectsMalformedTimeFormat() {
        BaseResponse response = services.availabilityService().updateStylerAvailability("S1", List.of(slot("1", "9am", "17:00")));
        assertEquals("400", response.getStatusCode());
        verify(availabilityRepo, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void rejectsEndNotAfterStart() {
        BaseResponse response = services.availabilityService().updateStylerAvailability("S1", List.of(slot("1", "17:00", "09:00")));
        assertEquals("400", response.getStatusCode());
        verify(availabilityRepo, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void acceptsValidSlotsAndPersists() {
        BaseResponse response = services.availabilityService().updateStylerAvailability("S1",
                List.of(slot("1", "09:00", "17:00"), slot("2", "10:00", "14:00")));
        assertEquals("200", response.getStatusCode());

        ArgumentCaptor<List<AvailabilityEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(availabilityRepo).saveAll(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertEquals("1", captor.getValue().get(0).getDayOfWeek());
        assertEquals("09:00", captor.getValue().get(0).getStartTime());
    }
}

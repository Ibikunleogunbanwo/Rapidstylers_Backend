package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.repo.NotificationRepo;
import com.macrotel.rapidstylers.repo.SavedStylistRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stylist home-visit fee settings: free radius (includedTravelKm) + flat
 * baseTravelFee, validated and persisted through the stylist dashboard.
 */
class TravelSettingsTest {

    private AppService appService;
    private StylerRepo stylerRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        stylerRepo = mock(StylerRepo.class);
        appService.stylerRepo = stylerRepo;
        // Home-visit fee updates notify saved customers — wire minimal mocks.
        appService.notificationRepo = mock(NotificationRepo.class);
        SavedStylistRepo saved = mock(SavedStylistRepo.class);
        when(saved.findAll()).thenReturn(Collections.emptyList());
        appService.savedStylistRepo = saved;
    }

    @Test
    void returnsDefaultsWhenUnset() {
        StylerEntity styler = styler(); // included null, fee null
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));

        Map<?, ?> data = (Map<?, ?>) appService.stylerTravelSettings("STYLER1").getData();
        assertEquals(15.0, ((Number) data.get("includedTravelKm")).doubleValue());
        assertEquals("0.00", data.get("baseTravelFee"));
    }

    @Test
    void updatesFeeAndRadius() {
        StylerEntity styler = styler();
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));

        appService.updateStylerTravelSettings("STYLER1", 20.0, "25.00");

        assertEquals(20.0, styler.getIncludedTravelKm().doubleValue());
        assertEquals("25.00", styler.getBaseTravelFee());
        verify(stylerRepo).save(styler);
    }

    @Test
    void negativeFeeRejectedAndNotSaved() {
        StylerEntity styler = styler();
        styler.setBaseTravelFee("0.00");
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));

        String code = appService.updateStylerTravelSettings("STYLER1", 15.0, "-5").getStatusCode();

        assertNotEquals("200", code);
        assertEquals("0.00", styler.getBaseTravelFee());
    }

    @Test
    void invalidFeeTextRejected() {
        StylerEntity styler = styler();
        styler.setBaseTravelFee("0.00");
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));

        String code = appService.updateStylerTravelSettings("STYLER1", 15.0, "abc").getStatusCode();

        assertNotEquals("200", code);
        assertEquals("0.00", styler.getBaseTravelFee());
    }

    @Test
    void missingStylerRejected() {
        when(stylerRepo.findByStylerId("NOPE")).thenReturn(Optional.empty());
        assertEquals("400", appService.updateStylerTravelSettings("NOPE", 15.0, "10.00").getStatusCode());
    }

    private StylerEntity styler() {
        StylerEntity s = new StylerEntity();
        s.setStylerId("STYLER1");
        return s;
    }
}
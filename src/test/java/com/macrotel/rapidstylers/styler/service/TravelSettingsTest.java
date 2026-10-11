package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.customer.repo.SavedStylistRepo;
import com.macrotel.rapidstylers.engagement.repo.NotificationRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.support.TestServices;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private TestServices services;
    private StylerRepo stylerRepo;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        stylerRepo = mock(StylerRepo.class);
        services.stylerRepo = stylerRepo;
        // Home-visit fee updates notify saved customers — wire minimal mocks.
        services.notificationRepo = mock(NotificationRepo.class);
        SavedStylistRepo saved = mock(SavedStylistRepo.class);
        when(saved.findAll()).thenReturn(Collections.emptyList());
        services.savedStylistRepo = saved;
    }

    @Test
    void returnsDefaultsWhenUnset() {
        StylerEntity styler = styler(); // included null, fee null
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));

        Map<?, ?> data = (Map<?, ?>) services.availabilityService().stylerTravelSettings("STYLER1").getData();
        assertEquals(15.0, ((Number) data.get("includedTravelKm")).doubleValue());
        assertEquals("0.00", data.get("baseTravelFee"));
    }

    @Test
    void updatesFeeAndRadius() {
        StylerEntity styler = styler();
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));

        services.availabilityService().updateStylerTravelSettings("STYLER1", 20.0, "25.00");

        assertEquals(20.0, styler.getIncludedTravelKm().doubleValue());
        assertEquals("25.00", styler.getBaseTravelFee());
        verify(stylerRepo).save(styler);
    }

    @Test
    void negativeFeeRejectedAndNotSaved() {
        StylerEntity styler = styler();
        styler.setBaseTravelFee("0.00");
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));

        String code = services.availabilityService().updateStylerTravelSettings("STYLER1", 15.0, "-5").getStatusCode();

        assertNotEquals("200", code);
        assertEquals("0.00", styler.getBaseTravelFee());
    }

    @Test
    void invalidFeeTextRejected() {
        StylerEntity styler = styler();
        styler.setBaseTravelFee("0.00");
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));

        String code = services.availabilityService().updateStylerTravelSettings("STYLER1", 15.0, "abc").getStatusCode();

        assertNotEquals("200", code);
        assertEquals("0.00", styler.getBaseTravelFee());
    }

    @Test
    void missingStylerRejected() {
        when(stylerRepo.findByStylerId("NOPE")).thenReturn(Optional.empty());
        assertEquals("400", services.availabilityService().updateStylerTravelSettings("NOPE", 15.0, "10.00").getStatusCode());
    }

    @Test
    void aStylistChoosesWhichAppointmentLocationsTheyOffer() {
        StylerEntity styler = styler();
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        Map<?, ?> before = (Map<?, ?>) services.availabilityService().stylerTravelSettings("STYLER1").getData();
        assertEquals(true, before.get("offersSalonVisits"), "everyone offers both until they choose");
        assertEquals(true, before.get("offersHomeVisits"));

        AvailabilityService.LocationOptions homeOnly = new AvailabilityService.LocationOptions();
        homeOnly.offersSalonVisits = false;
        homeOnly.maxTravelKm = 30.0;
        Map<?, ?> after = (Map<?, ?>) services.availabilityService()
                .updateStylerTravelSettings("STYLER1", 15.0, "10.00", homeOnly).getData();
        assertFalse(styler.isOffersSalonVisits());
        assertTrue(styler.isOffersHomeVisits(), "a field left out keeps its saved value");
        assertEquals(30.0, styler.getMaxTravelKm());
        assertEquals(false, after.get("offersSalonVisits"));

        AvailabilityService.LocationOptions neither = new AvailabilityService.LocationOptions();
        neither.offersHomeVisits = false;
        assertEquals("Offer at least one appointment location", services.availabilityService()
                .updateStylerTravelSettings("STYLER1", 15.0, "10.00", neither).getMessage());
        assertTrue(styler.isOffersHomeVisits());

        AvailabilityService.LocationOptions tooFar = new AvailabilityService.LocationOptions();
        tooFar.maxTravelKm = 900.0;
        assertNotEquals("200", services.availabilityService()
                .updateStylerTravelSettings("STYLER1", 15.0, "10.00", tooFar).getStatusCode());

        AvailabilityService.LocationOptions noLimit = new AvailabilityService.LocationOptions();
        noLimit.clearMaxTravelKm = true;
        services.availabilityService().updateStylerTravelSettings("STYLER1", 15.0, "10.00", noLimit);
        assertNull(styler.getMaxTravelKm());
    }

    private StylerEntity styler() {
        StylerEntity s = new StylerEntity();
        s.setStylerId("STYLER1");
        return s;
    }
}
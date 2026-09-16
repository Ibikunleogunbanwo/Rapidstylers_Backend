package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.service.GoogleTimezoneService;
import com.macrotel.rapidstylers.service.VendorZoneResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The startup backfill that gives every vendor with a geocoded address a
 * Google-exact zone. The behaviour that matters: it resolves the rows that only
 * have a province guess, it never spends a lookup on a row already resolved from
 * its address, and it never overwrites a stored zone with nothing when Google
 * cannot answer.
 */
class VendorZoneBackfillTest {

    private static final double CALGARY_LAT = 51.0447;
    private static final double CALGARY_LNG = -114.0719;

    private StylerRepo stylerRepo;
    private GoogleTimezoneService google;
    private VendorZoneBackfill backfill;

    @BeforeEach
    void setUp() {
        stylerRepo = mock(StylerRepo.class);
        google = mock(GoogleTimezoneService.class);
        when(stylerRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        backfill = new VendorZoneBackfill(stylerRepo, google);
    }

    private StylerEntity styler(String id, String province, Double lat, Double lng, String zone, String source) {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId(id);
        styler.setProvince(province);
        styler.setLatitude(lat);
        styler.setLongitude(lng);
        styler.setTimeZone(zone);
        styler.setTimeZoneSource(source);
        return styler;
    }

    @Test
    void aProvinceFallbackIsReplacedByTheResolvedZoneAndMarkedExact() {
        StylerEntity vancouver = styler("DS1001", "British Columbia", 49.2827, -123.1207,
                "America/Vancouver", null);
        when(stylerRepo.findAll()).thenReturn(List.of(vancouver));
        when(google.timeZoneId(eq(49.2827), eq(-123.1207))).thenReturn("America/Vancouver");

        backfill.run();

        assertEquals(VendorZoneResolver.SOURCE_GOOGLE, vancouver.getTimeZoneSource());
        assertEquals("America/Vancouver", vancouver.getTimeZone());
        verify(stylerRepo, times(1)).save(vancouver);
    }

    @Test
    void aWrongStoredZoneIsCorrectedToTheAddressAnswer() {
        // A row whose province label and coordinates disagree: the coordinates win.
        StylerEntity moved = styler("DS1002", "Alberta", 45.4215, -75.6972,
                "America/Edmonton", VendorZoneResolver.SOURCE_PROVINCE);
        when(stylerRepo.findAll()).thenReturn(List.of(moved));
        when(google.timeZoneId(eq(45.4215), eq(-75.6972))).thenReturn("America/Toronto");

        backfill.run();

        assertEquals("America/Toronto", moved.getTimeZone());
        assertEquals(VendorZoneResolver.SOURCE_GOOGLE, moved.getTimeZoneSource());
    }

    @Test
    void aNullZoneWithCoordinatesIsFilled() {
        StylerEntity unset = styler("DS1003", "Ontario", 43.6532, -79.3832, null, null);
        when(stylerRepo.findAll()).thenReturn(List.of(unset));
        when(google.timeZoneId(anyDouble(), anyDouble())).thenReturn("America/Toronto");

        backfill.run();

        assertEquals("America/Toronto", unset.getTimeZone());
        assertEquals(VendorZoneResolver.SOURCE_GOOGLE, unset.getTimeZoneSource());
    }

    @Test
    void theSameZoneAsTheProvinceAnswerIsStillMarkedExactAndNeverQueriedAgain() {
        // The Alberta trap: province map and Google agree, so the value cannot
        // show whether a real lookup happened. The marker is the only evidence —
        // and it is what stops a restart from paying for another lookup.
        StylerEntity calgary = styler("DEMO3058", "Alberta", CALGARY_LAT, CALGARY_LNG,
                "America/Edmonton", null);
        when(stylerRepo.findAll()).thenReturn(List.of(calgary));
        when(google.timeZoneId(anyDouble(), anyDouble())).thenReturn("America/Edmonton");

        backfill.run();

        assertEquals(VendorZoneResolver.SOURCE_GOOGLE, calgary.getTimeZoneSource());
        assertEquals("America/Edmonton", calgary.getTimeZone());

        // Second boot: already resolved, so no further Google calls at all.
        backfill.run();
        verify(google, times(1)).timeZoneId(anyDouble(), anyDouble());
        verify(stylerRepo, times(1)).save(any());
    }

    @Test
    void rowsAlreadyResolvedFromTheirAddressAreNotLookedUpAtAll() {
        StylerEntity exact = styler("DS1004", "Ontario", 43.6532, -79.3832,
                "America/Toronto", VendorZoneResolver.SOURCE_GOOGLE);
        when(stylerRepo.findAll()).thenReturn(List.of(exact));

        backfill.run();

        verify(google, never()).timeZoneId(anyDouble(), anyDouble());
        verify(stylerRepo, never()).save(any());
    }

    @Test
    void rowsWithoutCoordinatesAreLeftUntouched() {
        StylerEntity noCoords = styler("DS1005", "Alberta", null, null, "America/Edmonton", null);
        StylerEntity halfCoords = styler("DS1006", "Alberta", CALGARY_LAT, null, "America/Edmonton", null);
        when(stylerRepo.findAll()).thenReturn(List.of(noCoords, halfCoords));

        backfill.run();

        verify(google, never()).timeZoneId(anyDouble(), anyDouble());
        verify(stylerRepo, never()).save(any());
        assertNull(noCoords.getTimeZoneSource());
    }

    @Test
    void anUnresolvableRowKeepsItsStoredZoneAndIsRetriedOnTheNextBoot() {
        StylerEntity stranded = styler("DS1007", "Saskatchewan", 52.1332, -106.6700,
                "America/Regina", VendorZoneResolver.SOURCE_PROVINCE);
        when(stylerRepo.findAll()).thenReturn(List.of(stranded));
        when(google.timeZoneId(anyDouble(), anyDouble())).thenReturn(null); // no key / quota / error

        backfill.run();

        assertEquals("America/Regina", stranded.getTimeZone(), "a fallback beats no zone at all");
        assertEquals(VendorZoneResolver.SOURCE_PROVINCE, stranded.getTimeZoneSource(),
                "the marker must stay so the next boot tries again");
        verify(stylerRepo, never()).save(any());
    }

    @Test
    void oneUnresolvableRowDoesNotSpareTheOthers() {
        StylerEntity stranded = styler("DS1008", "Alberta", 52.2681, -113.8112, "America/Edmonton", null);
        StylerEntity fine = styler("DS1009", "Ontario", 45.4215, -75.6972, "America/Edmonton", null);
        when(stylerRepo.findAll()).thenReturn(List.of(stranded, fine));
        when(google.timeZoneId(eq(52.2681), eq(-113.8112))).thenReturn(null);
        when(google.timeZoneId(eq(45.4215), eq(-75.6972))).thenReturn("America/Toronto");

        backfill.run();

        assertEquals("America/Toronto", fine.getTimeZone());
        assertEquals("America/Edmonton", stranded.getTimeZone());
        ArgumentCaptor<StylerEntity> saved = ArgumentCaptor.forClass(StylerEntity.class);
        verify(stylerRepo, times(1)).save(saved.capture());
        assertEquals("DS1009", saved.getValue().getStylerId());
    }

    @Test
    void aRepositoryFailureNeverBlocksBoot() {
        when(stylerRepo.findAll()).thenThrow(new RuntimeException("db down"));

        backfill.run(); // must not throw

        verify(stylerRepo, never()).save(any());
    }
}

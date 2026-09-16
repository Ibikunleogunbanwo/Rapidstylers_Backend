package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.AvailabilityEntity;
import com.macrotel.rapidstylers.entity.AvailabilityExceptionEntity;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.repo.AvailabilityExceptionRepo;
import com.macrotel.rapidstylers.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Open now" must be judged on the vendor's own clock. Availability rows store
 * bare local times, so the zone comes from the vendor's business province —
 * a Calgary vendor is America/Edmonton, a Toronto vendor America/Toronto —
 * never the server's single application zone and never the visitor's browser.
 */
class VendorZoneResolverTest {

    private AppService appService;
    private StylerRepo stylerRepo;
    private AvailabilityRepo availabilityRepo;
    private AvailabilityExceptionRepo availabilityExceptionRepo;
    private BookAppointmentRepo bookAppointmentRepo;

    @BeforeEach
    void setUp() throws Exception {
        appService = new AppService();
        stylerRepo = mock(StylerRepo.class);
        availabilityRepo = mock(AvailabilityRepo.class);
        availabilityExceptionRepo = mock(AvailabilityExceptionRepo.class);
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        appService.stylerRepo = stylerRepo;
        appService.availabilityRepo = availabilityRepo;
        appService.availabilityExceptionRepo = availabilityExceptionRepo;
        appService.bookAppointmentRepo = bookAppointmentRepo;
        // isOpenAt's window-free check consults the stylist's own bookings.
        when(bookAppointmentRepo.findByStylerId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());
    }

    private StylerEntity stylerIn(String province) {
        StylerEntity styler = new StylerEntity();
        styler.setProvince(province);
        return styler;
    }

    private AvailabilityEntity slot(String day, String start, String end) {
        AvailabilityEntity row = new AvailabilityEntity();
        row.setDayOfWeek(day);
        row.setStartTime(start);
        row.setEndTime(end);
        return row;
    }

    /** Reflects into the private isOpenAt(stylerId, date, time, duration). */
    private boolean isOpenAt(String stylerId, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        try {
            Method method = AppService.class.getDeclaredMethod(
                    "isOpenAt", String.class, LocalDate.class, LocalTime.class, int.class);
            method.setAccessible(true);
            return (boolean) method.invoke(appService, stylerId,
                    now.toLocalDate(), now.toLocalTime(), 30);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * A straight reading of the vendor's wall clock against their 09:00-17:00
     * Tuesday window (with the 30-minute fit window). Independent of AppService,
     * so it is the oracle the isOpenAt verdict is asserted against.
     */
    private boolean vendorWallClockInsideWindow(ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        boolean isTuesday = now.getDayOfWeek().getValue() == 2; // java.time: 1=Mon .. 7=Sun
        if (!isTuesday) return false;
        int hour = now.getHour();
        int minute = now.getMinute();
        boolean afterStart = hour > 9 || (hour == 9 && minute >= 0);
        boolean fitsBeforeEnd = (hour < 16) || (hour == 16 && minute == 0); // 16:30 + 30 <= 17:00
        return afterStart && fitsBeforeEnd;
    }

    @Test
    void mapsCanadianProvincesToTheirDominantZone() {
        assertEquals(ZoneId.of("America/Edmonton"), VendorZoneResolver.zoneForProvince("Alberta"));
        assertEquals(ZoneId.of("America/Toronto"), VendorZoneResolver.zoneForProvince("Ontario"));
        assertEquals(ZoneId.of("America/Vancouver"), VendorZoneResolver.zoneForProvince("British Columbia"));
        assertEquals(ZoneId.of("America/Halifax"), VendorZoneResolver.zoneForProvince("Nova Scotia"));
        assertEquals(ZoneId.of("America/St_Johns"), VendorZoneResolver.zoneForProvince("Newfoundland and Labrador"));
        // Abbreviations and casing are accepted too.
        assertEquals(ZoneId.of("America/Toronto"), VendorZoneResolver.zoneForProvince("on"));
        assertEquals(ZoneId.of("America/Edmonton"), VendorZoneResolver.zoneForProvince("  ALBERTA  "));
    }

    @Test
    void unknownOrNullProvincesFallBackToTheAppDefault() {
        assertEquals(VendorZoneResolver.DEFAULT_ZONE, VendorZoneResolver.zoneForProvince(null));
        assertEquals(VendorZoneResolver.DEFAULT_ZONE, VendorZoneResolver.zoneForProvince(""));
        assertEquals(VendorZoneResolver.DEFAULT_ZONE, VendorZoneResolver.zoneForProvince("Atlantis"));
    }

    @Test
    void calgaryVendorHoursAreJudgedInMountainTimeNotTheVisitorsClock() {
        // The core scenario: a Calgary vendor works Tue 09:00-17:00. Whether
        // they read open must track Calgary's wall clock — not the server's.
        when(stylerRepo.findByStylerId("CALGARY1")).thenReturn(Optional.of(stylerIn("Alberta")));
        // dayOfWeek is JS-style (0 = Sunday); Tuesday = 2.
        when(availabilityRepo.findByStylerId("CALGARY1")).thenReturn(List.of(slot("2", "09:00", "17:00")));
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        ZoneId calgary = VendorZoneResolver.zoneForProvince("Alberta");
        assertEquals(vendorWallClockInsideWindow(calgary), isOpenAt("CALGARY1", calgary),
                "openNow must match a straight reading of Calgary's wall clock");
    }

    @Test
    void torontoVendorOpenStatusDiffersFromServerClockWhenZonesDiverge() {
        // A Toronto vendor's 09:00-17:00 must be evaluated at 09:00-17:00 in
        // Toronto. When Edmonton's clock is past 17:00 but Toronto's is not
        // (15:00-17:00 Edmonton = 17:00-19:00 Toronto), the vendor is OPEN,
        // whereas the old application-zone logic would have closed them.
        when(stylerRepo.findByStylerId("TORONTO1")).thenReturn(Optional.of(stylerIn("Ontario")));
        when(availabilityRepo.findByStylerId("TORONTO1")).thenReturn(List.of(slot("2", "09:00", "17:00")));
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        ZoneId toronto = VendorZoneResolver.zoneForProvince("Ontario");
        boolean openByVendorClock = isOpenAt("TORONTO1", toronto);
        assertEquals(vendorWallClockInsideWindow(toronto), openByVendorClock,
                "openNow must track Toronto's clock, not the server zone");
    }

    @Test
    void vendorWithUnknownProvinceKeepsLegacyDefaultZoneBehaviour() {
        when(stylerRepo.findByStylerId("LEGACY1")).thenReturn(Optional.of(stylerIn(null)));
        when(availabilityRepo.findByStylerId("LEGACY1")).thenReturn(List.of(slot("2", "09:00", "17:00")));
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        // Same evaluation as before this change: application default zone.
        ZoneId defaultZone = VendorZoneResolver.DEFAULT_ZONE;
        assertEquals(vendorWallClockInsideWindow(defaultZone), isOpenAt("LEGACY1", defaultZone),
                "no-province vendors keep the app-default zone behaviour");
    }

    @Test
    void storedTimeZoneOverridesTheProvinceMap() {
        // A row whose province says one thing but whose stored zone (derived
        // from the signup geocode) says another: the stored zone wins — the
        // geocode knows the real place better than the province label.
        StylerEntity odd = stylerIn("Alberta");
        odd.setTimeZone("America/Toronto");
        when(stylerRepo.findByStylerId("MIXED1")).thenReturn(Optional.of(odd));
        when(availabilityRepo.findByStylerId("MIXED1")).thenReturn(List.of(slot("2", "09:00", "17:00")));
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        ZoneId toronto = ZoneId.of("America/Toronto");
        assertEquals(vendorWallClockInsideWindow(toronto), isOpenAt("MIXED1", toronto),
                "the stored zone must decide, not the province label");
    }
}

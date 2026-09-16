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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Open now" must be judged on the vendor's own clock. Availability rows store
 * bare local times, so the zone comes from the vendor's stored zone (derived
 * from their geocoded address), falling back to the province map — never the
 * server's single application zone and never the visitor's browser.
 *
 * <p>Every case here evaluates a <em>fixed instant</em> rather than the wall
 * clock. The previous version called ZonedDateTime.now() twice (once for the
 * expectation, once for the code under test) and therefore only failed during a
 * narrow window each night: it caught the "vendor closed at 17:00 still reads
 * open after 23:30" bug in CI and nowhere else. Fixed instants make the
 * behaviour verifiable at any hour.
 */
class VendorZoneResolverTest {

    /** 2026-09-15 is a Tuesday. 13:30Z = 09:30 Toronto, 07:30 Edmonton. */
    private static final ZonedDateTime TUESDAY_MIDWEEK = ZonedDateTime.of(2026, 9, 15, 13, 30, 0, 0, ZoneOffset.UTC);

    /** 2026-09-16T03:50Z = Tuesday 23:50 in Toronto, Tuesday 21:50 in Edmonton. */
    private static final ZonedDateTime TUESDAY_LATE_NIGHT = ZonedDateTime.of(2026, 9, 16, 3, 50, 0, 0, ZoneOffset.UTC);

    /** 2026-09-16T01:50Z = Tuesday 21:50 in Toronto, Tuesday 19:50 in Edmonton. */
    private static final ZonedDateTime TUESDAY_EVENING = ZonedDateTime.of(2026, 9, 16, 1, 50, 0, 0, ZoneOffset.UTC);

    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final ZoneId EDMONTON = ZoneId.of("America/Edmonton");

    private AppService appService;
    private StylerRepo stylerRepo;
    private AvailabilityRepo availabilityRepo;
    private AvailabilityExceptionRepo availabilityExceptionRepo;
    private BookAppointmentRepo bookAppointmentRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        stylerRepo = mock(StylerRepo.class);
        availabilityRepo = mock(AvailabilityRepo.class);
        availabilityExceptionRepo = mock(AvailabilityExceptionRepo.class);
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        appService.stylerRepo = stylerRepo;
        appService.availabilityRepo = availabilityRepo;
        appService.availabilityExceptionRepo = availabilityExceptionRepo;
        appService.bookAppointmentRepo = bookAppointmentRepo;
        // isOpenAt's conflict check reads the stylist's own bookings for the day.
        when(bookAppointmentRepo.findByStylerIdAndAppointmentDateValue(anyString(), org.mockito.ArgumentMatchers.any(LocalDate.class)))
                .thenReturn(List.of());
        when(bookAppointmentRepo.findByStylerIdAndAppointmentDate(anyString(), anyString()))
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

    /** Looks up the styler and evaluates "open now" at the given instant. */
    private boolean isOpenNowAt(String stylerId, ZonedDateTime instant) {
        Optional<StylerEntity> styler = stylerRepo.findByStylerId(stylerId);
        ZoneId zone = styler.map(s -> s.getTimeZone() == null || s.getTimeZone().isBlank()
                        ? VendorZoneResolver.zoneForProvince(s.getProvince())
                        : ZoneId.of(s.getTimeZone()))
                .orElse(VendorZoneResolver.DEFAULT_ZONE);
        ZonedDateTime vendorNow = instant.withZoneSameInstant(zone);
        return isOpenAtInstant(stylerId, vendorNow);
    }

    /** Reflects into the private isOpenAt(stylerId, date, time, duration). */
    private boolean isOpenAtInstant(String stylerId, ZonedDateTime vendorNow) {
        try {
            Method method = AppService.class.getDeclaredMethod(
                    "isOpenAt", String.class, LocalDate.class, LocalTime.class, int.class);
            method.setAccessible(true);
            return (boolean) method.invoke(appService, stylerId,
                    vendorNow.toLocalDate(), vendorNow.toLocalTime(), 30);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void stubHours(String stylerId, String province, AvailabilityEntity... rows) {
        when(stylerRepo.findByStylerId(stylerId)).thenReturn(Optional.of(stylerIn(province)));
        when(availabilityRepo.findByStylerId(stylerId)).thenReturn(List.of(rows));
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    void mapsCanadianProvincesToTheirDominantZone() {
        assertEquals(EDMONTON, VendorZoneResolver.zoneForProvince("Alberta"));
        assertEquals(TORONTO, VendorZoneResolver.zoneForProvince("Ontario"));
        assertEquals(ZoneId.of("America/Vancouver"), VendorZoneResolver.zoneForProvince("British Columbia"));
        assertEquals(ZoneId.of("America/Halifax"), VendorZoneResolver.zoneForProvince("Nova Scotia"));
        assertEquals(ZoneId.of("America/St_Johns"), VendorZoneResolver.zoneForProvince("Newfoundland and Labrador"));
        // Abbreviations and casing are accepted too.
        assertEquals(TORONTO, VendorZoneResolver.zoneForProvince("on"));
        assertEquals(EDMONTON, VendorZoneResolver.zoneForProvince("  ALBERTA  "));
    }

    @Test
    void unknownOrNullProvincesFallBackToTheAppDefault() {
        assertEquals(VendorZoneResolver.DEFAULT_ZONE, VendorZoneResolver.zoneForProvince(null));
        assertEquals(VendorZoneResolver.DEFAULT_ZONE, VendorZoneResolver.zoneForProvince(""));
        assertEquals(VendorZoneResolver.DEFAULT_ZONE, VendorZoneResolver.zoneForProvince("Atlantis"));
    }

    @Test
    void sameInstantReadsOpenForTorontoAndClosedForAlberta() {
        // The same moment, two vendors with identical 09:00-17:00 Tuesday hours:
        // in Toronto it is 09:30 (open), in Alberta 07:30 (not yet open). A
        // single application zone cannot get both right.
        stubHours("TORONTO1", "Ontario", slot("2", "09:00", "17:00"));
        stubHours("CALGARY1", "Alberta", slot("2", "09:00", "17:00"));

        assertTrue(isOpenNowAt("TORONTO1", TUESDAY_MIDWEEK),
                "a Toronto vendor's 09:00-17:00 window is open at 09:30 their time");
        assertFalse(isOpenNowAt("CALGARY1", TUESDAY_MIDWEEK),
                "an Alberta vendor is still closed at 07:30 their time");
    }

    @Test
    void calgaryVendorReadsItsOwnClockAcrossTheDay() {
        stubHours("CALGARY1", "Alberta", slot("2", "09:00", "17:00"));

        assertFalse(isOpenNowAt("CALGARY1", ZonedDateTime.of(2026, 9, 15, 14, 0, 0, 0, ZoneOffset.UTC)),
                "08:00 in Calgary is before opening");
        assertTrue(isOpenNowAt("CALGARY1", ZonedDateTime.of(2026, 9, 15, 18, 0, 0, 0, ZoneOffset.UTC)),
                "12:00 in Calgary is inside the window");
        assertTrue(isOpenNowAt("CALGARY1", ZonedDateTime.of(2026, 9, 15, 22, 0, 0, 0, ZoneOffset.UTC)),
                "16:00 in Calgary still leaves room for a 30-minute service");
        assertFalse(isOpenNowAt("CALGARY1", ZonedDateTime.of(2026, 9, 15, 22, 45, 0, 0, ZoneOffset.UTC)),
                "16:45 in Calgary cannot fit a 30-minute service before 17:00");
        assertFalse(isOpenNowAt("CALGARY1", ZonedDateTime.of(2026, 9, 16, 2, 0, 0, 0, ZoneOffset.UTC)),
                "20:00 in Calgary is closed");
    }

    @Test
    void vendorClosedForTheEveningNeverWrapsPastMidnight() {
        // Regression: LocalTime.plusMinutes wraps at midnight, so 23:50 + 30
        // "ended" at 00:20 — earlier in the day than a 17:00 close — and the
        // gate reported a vendor who shut at 17:00 as open every night after
        // 23:30. Minute-of-day arithmetic has no wrap to fall into.
        stubHours("TORONTO1", "Ontario", slot("2", "09:00", "17:00"));
        stubHours("LATEWINDOW1", "Ontario", slot("2", "18:00", "23:00"));

        assertFalse(isOpenNowAt("TORONTO1", TUESDAY_LATE_NIGHT),
                "23:50 is well past a 17:00 close");
        // The fix must not close genuinely late hours: 21:50 + 30 fits in 18:00-23:00.
        assertTrue(isOpenNowAt("LATEWINDOW1", TUESDAY_EVENING),
                "a 18:00-23:00 window is still open at 21:50");
        assertFalse(isOpenNowAt("LATEWINDOW1", TUESDAY_LATE_NIGHT),
                "23:50 cannot fit a 30-minute service before a 23:00 close");
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
        when(availabilityExceptionRepo.findByStylerIdAndBlockedDate(anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertTrue(isOpenNowAt("MIXED1", TUESDAY_MIDWEEK),
                "the stored zone must decide, not the province label");
    }

    @Test
    void vendorWithUnknownProvinceKeepsLegacyDefaultZoneBehaviour() {
        stubHours("LEGACY1", null, slot("2", "09:00", "17:00"));

        // No usable province: the application default zone (Edmonton) decides.
        assertFalse(isOpenNowAt("LEGACY1", TUESDAY_MIDWEEK),
                "the app default zone is 07:30 at this instant, so the vendor is closed");
        assertTrue(isOpenAtInstant("LEGACY1", TUESDAY_MIDWEEK.withZoneSameInstant(EDMONTON).withHour(12)),
                "12:00 in the app default zone is inside 09:00-17:00");
    }

    @Test
    void malformedOrAbsentHoursNeverReadAsOpen() {
        stubHours("BADWINDOW1", "Ontario", slot("2", "17:00", "09:00"));

        assertFalse(isOpenAtInstant("BADWINDOW1", TUESDAY_MIDWEEK.withZoneSameInstant(TORONTO)),
                "an end before its start is malformed, not an overnight shift");

        when(stylerRepo.findByStylerId("NOHOURS1")).thenReturn(Optional.of(stylerIn("Ontario")));
        when(availabilityRepo.findByStylerId("NOHOURS1")).thenReturn(List.of());
        assertFalse(isOpenAtInstant("NOHOURS1", TUESDAY_MIDWEEK.withZoneSameInstant(TORONTO)),
                "a vendor with no weekly hours set is never 'open now'");
    }
}

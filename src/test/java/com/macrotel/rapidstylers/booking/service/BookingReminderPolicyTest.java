package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.booking.service.BookingReminderPolicy.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Who gets a reminder and when. Uses America/Toronto (whose rules are the
 * same in every tz release the build might run on) and fixed offsets, so the
 * table holds on any JVM; the Alberta permanent-daylight case is checked
 * against the vendored 2026e rules in BookingReminderTzdbTest.
 */
class BookingReminderPolicyTest {

    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
    private static final LocalTime TEN = LocalTime.of(10, 0);
    /** 2026-11-20 10:00 EST. */
    private static final Instant START = Instant.parse("2026-11-20T15:00:00Z");

    private static Outcome at(String status, Duration beforeStart) {
        return BookingReminderPolicy.decide(status, null, null, null, DAY, TEN, TORONTO,
                START.minus(beforeStart), 24, 2);
    }

    @ParameterizedTest(name = "status {0} -> {1}")
    @CsvSource({
            "3, SEND",           // accepted by the stylist
            "1, NOT_ACCEPTED",   // still pending: the stylist hasn't confirmed
            "2, NOT_ACCEPTED",   // declined
            "0, NOT_ACCEPTED",   // completed
            "4, NOT_ACCEPTED",   // cancelled
            "'', NOT_ACCEPTED",
    })
    void onlyAcceptedBookingsAreReminded(String status, Outcome expected) {
        assertEquals(expected, at(status, Duration.ofHours(23)));
    }

    @Test
    void nullStatusIsNotReminded() {
        assertEquals(Outcome.NOT_ACCEPTED, at(null, Duration.ofHours(23)));
    }

    @Test
    void disputedBookingsAreNotReminded() {
        Instant now = START.minus(Duration.ofHours(23));
        assertEquals(Outcome.DISPUTED, BookingReminderPolicy.decide("3", "OPEN", null, null, DAY, TEN, TORONTO, now, 24, 2));
        assertEquals(Outcome.DISPUTED, BookingReminderPolicy.decide("3", null, "DISPUTED", null, DAY, TEN, TORONTO, now, 24, 2));
        assertEquals(Outcome.DISPUTED, BookingReminderPolicy.decide("3", null, "DISPUTE_LOST", null, DAY, TEN, TORONTO, now, 24, 2));
        assertEquals(Outcome.SEND, BookingReminderPolicy.decide("3", null, "AUTHORIZED", null, DAY, TEN, TORONTO, now, 24, 2));
    }

    @Test
    void alreadyRemindedIsNotRemindedAgain() {
        assertEquals(Outcome.ALREADY_SENT, BookingReminderPolicy.decide("3", null, null,
                LocalDateTime.of(2026, 11, 19, 15, 0), DAY, TEN, TORONTO, START.minus(Duration.ofHours(20)), 24, 2));
    }

    @Test
    void bookingWithoutATimeIsSkipped() {
        assertEquals(Outcome.NO_TIME, BookingReminderPolicy.decide("3", null, null, null, DAY, null, TORONTO,
                START.minus(Duration.ofHours(20)), 24, 2));
        assertEquals(Outcome.NO_TIME, BookingReminderPolicy.decide("3", null, null, null, null, TEN, TORONTO,
                START.minus(Duration.ofHours(20)), 24, 2));
    }

    @ParameterizedTest(name = "{0}s before start -> {1}")
    @CsvSource({
            "604800, TOO_EARLY",  // a week ahead
            "86401, TOO_EARLY",   // one second before the window opens
            "86400, SEND",        // exactly the lead time
            "82800, SEND",        // 23h
            "18000, SEND",        // short notice: accepted 5h before, still 2h+ away
            "7200, SEND",         // exactly the minimum notice
            "7199, TOO_LATE",     // one second inside the minimum notice
            "3600, TOO_LATE",     // an hour before
            "1, TOO_LATE",
            "0, STARTED",         // starting now
            "-60, STARTED",       // started a minute ago
            "-172800, STARTED",   // two days ago
    })
    void leadTimeShortNoticeAndPastEdges(long secondsBeforeStart, Outcome expected) {
        assertEquals(expected, at("3", Duration.ofSeconds(secondsBeforeStart)));
    }

    @Test
    void leadTimeIsConfigurable() {
        Instant now = START.minus(Duration.ofHours(40));
        assertEquals(Outcome.TOO_EARLY, BookingReminderPolicy.decide("3", null, null, null, DAY, TEN, TORONTO, now, 24, 2));
        assertEquals(Outcome.SEND, BookingReminderPolicy.decide("3", null, null, null, DAY, TEN, TORONTO, now, 48, 2));
    }

    @Test
    void startIsTheStylistsWallClockInTheStylistsZone() {
        assertEquals(START, BookingReminderPolicy.startInstant(DAY, TEN, TORONTO));
        assertEquals(Instant.parse("2026-11-20T16:00:00Z"),
                BookingReminderPolicy.startInstant(DAY, TEN, ZoneOffset.ofHours(-6)));
        assertEquals(Instant.parse("2026-11-20T17:00:00Z"),
                BookingReminderPolicy.startInstant(DAY, TEN, ZoneOffset.ofHours(-7)));
    }

    @Test
    void sameWallClockInDifferentZonesIsRemindedAtDifferentMoments() {
        Instant now = Instant.parse("2026-11-19T15:30:00Z");
        // Toronto 10:00 = 15:00Z: 23.5h away, due. Vancouver 10:00 = 18:00Z: 26.5h away, not yet.
        assertEquals(Outcome.SEND, BookingReminderPolicy.decide("3", null, null, null, DAY, TEN, TORONTO, now, 24, 2));
        assertEquals(Outcome.TOO_EARLY, BookingReminderPolicy.decide("3", null, null, null, DAY, TEN,
                ZoneId.of("America/Vancouver"), now, 24, 2));
    }

    @Test
    void twentyFourHoursIsRealTimeAcrossTheFallBackChange() {
        // Toronto leaves daylight time on 2026-11-01 at 02:00. A 10:00 EST start
        // is 15:00Z; 24 real hours earlier is 10-31 15:00Z, which is 11:00 EDT
        // on the wall, not 10:00.
        LocalDate sunday = LocalDate.of(2026, 11, 1);
        assertEquals(Outcome.TOO_EARLY, BookingReminderPolicy.decide("3", null, null, null, sunday, TEN, TORONTO,
                Instant.parse("2026-10-31T14:30:00Z"), 24, 2)); // 10:30 EDT: 24.5h away
        assertEquals(Outcome.SEND, BookingReminderPolicy.decide("3", null, null, null, sunday, TEN, TORONTO,
                Instant.parse("2026-10-31T15:00:00Z"), 24, 2));
    }
}

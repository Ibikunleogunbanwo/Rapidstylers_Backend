package com.macrotel.rapidstylers.booking.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Whether a booking's day-before reminder should go out now. No Spring, no
 * database: the reminder job asks this for every candidate, and the tests ask
 * it for every status and time edge.
 *
 * <p>An appointment's date and start are bare wall-clock values on the
 * stylist's clock, so they only become a moment in time once the stylist's
 * zone is applied ({@link #startInstant}); "24 hours before" is then measured
 * on that real timeline, across any daylight-time change.
 *
 * <p>The send window is {@code [start - leadHours, start - minNoticeHours]}.
 * A booking made weeks ahead is reminded as the window opens. A booking made
 * (or accepted) inside the lead time is reminded on the next run if its start
 * is still at least {@code minNoticeHours} away, and never otherwise. Nothing
 * is ever sent once the start has passed.
 */
public final class BookingReminderPolicy {

    /** Status code of a booking the stylist accepted (see AppointmentWorkflowService). */
    public static final String ACCEPTED = "3";

    public enum Outcome {
        /** Due: send it now. */
        SEND,
        /** Pending, declined, completed or cancelled: never reminded. */
        NOT_ACCEPTED,
        /** A service or payment dispute is open. */
        DISPUTED,
        /** Already reminded. */
        ALREADY_SENT,
        /** The booking has no usable date or start time. */
        NO_TIME,
        /** More than the lead time away: a later run will send it. */
        TOO_EARLY,
        /** Starts in less than the minimum notice: too late to be useful. */
        TOO_LATE,
        /** Already started or over. */
        STARTED
    }

    private BookingReminderPolicy() {
    }

    /** The real moment an appointment starts: its wall-clock date and time on the stylist's clock. */
    public static Instant startInstant(LocalDate date, LocalTime start, ZoneId zone) {
        return LocalDateTime.of(date, start).atZone(zone).toInstant();
    }

    public static Outcome decide(String status, String serviceDisputeStatus, String paymentStatus,
                                 LocalDateTime reminderSentAt, LocalDate date, LocalTime start, ZoneId zone,
                                 Instant now, long leadHours, long minNoticeHours) {
        if (!ACCEPTED.equals(status)) return Outcome.NOT_ACCEPTED;
        if ("OPEN".equals(serviceDisputeStatus) || (paymentStatus != null && paymentStatus.startsWith("DISPUTE"))) {
            return Outcome.DISPUTED;
        }
        if (reminderSentAt != null) return Outcome.ALREADY_SENT;
        if (date == null || start == null || zone == null) return Outcome.NO_TIME;
        Instant begins = startInstant(date, start, zone);
        if (!now.isBefore(begins)) return Outcome.STARTED;
        Duration untilStart = Duration.between(now, begins);
        if (untilStart.compareTo(Duration.ofHours(leadHours)) > 0) return Outcome.TOO_EARLY;
        if (untilStart.compareTo(Duration.ofHours(minNoticeHours)) < 0) return Outcome.TOO_LATE;
        return Outcome.SEND;
    }
}

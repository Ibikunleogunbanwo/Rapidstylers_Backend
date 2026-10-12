package com.macrotel.rapidstylers.engagement.service;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** createdAtUtc is createdAt read in the zone it was written in, as an instant. */
class NotificationTimesTest {

    @Test
    void aUtcServerTimeIsUnchanged() {
        assertEquals("2026-10-12T15:04:05Z", NotificationTimes.utc("2026-10-12 15:04:05", ZoneId.of("UTC")));
    }

    @Test
    void aZonedServerTimeIsShiftedToUtc() {
        // Edmonton is UTC-6 in October 2026.
        assertEquals("2026-10-12T21:04:05Z", NotificationTimes.utc("2026-10-12 15:04:05", ZoneId.of("America/Edmonton")));
    }

    @Test
    void theDefaultIsTheJvmZoneTheEntityWritesIn() {
        String written = java.time.LocalDateTime.of(2026, 1, 2, 3, 4, 5)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        assertEquals(java.time.LocalDateTime.of(2026, 1, 2, 3, 4, 5).atZone(ZoneId.systemDefault()).toInstant().toString(),
                NotificationTimes.utc(written));
    }

    @Test
    void missingOrUnreadableIsNull() {
        assertNull(NotificationTimes.utc(null));
        assertNull(NotificationTimes.utc(" "));
        assertNull(NotificationTimes.utc("yesterday"));
    }
}

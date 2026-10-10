package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.styler.entity.AvailabilityEntity;
import com.macrotel.rapidstylers.styler.entity.AvailabilityExceptionEntity;
import com.macrotel.rapidstylers.styler.repo.AvailabilityExceptionRepo;
import com.macrotel.rapidstylers.styler.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.support.TestServices;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The badge reads the stylist's set hours on their own clock, not whether they are signed in. */
class StylerPresenceTest {

    private AvailabilityRepo availabilityRepo;
    private AvailabilityExceptionRepo exceptionRepo;
    private StylerPresence presence;

    @BeforeEach
    void setUp() {
        TestServices services = new TestServices();
        availabilityRepo = mock(AvailabilityRepo.class);
        exceptionRepo = mock(AvailabilityExceptionRepo.class);
        services.availabilityRepo = availabilityRepo;
        services.availabilityExceptionRepo = exceptionRepo;
        presence = new StylerPresence();
        presence.availabilityService = services.availabilityService();

        AvailabilityEntity monday = new AvailabilityEntity();
        monday.setStylerId("S1");
        monday.setDayOfWeek("1");
        monday.setStartTime("09:00");
        monday.setEndTime("17:00");
        when(availabilityRepo.findByStylerId("S1")).thenReturn(List.of(monday));
    }

    private void at(String instant) {
        presence.clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    void onlineInsideSetHours() {
        at("2026-10-12T17:30:00Z"); // Monday 11:30 in Calgary
        assertEquals(StylerPresence.ONLINE, presence.status("S1", "America/Edmonton", "Alberta"));
    }

    @Test
    void offlineOutsideSetHours() {
        at("2026-10-13T00:30:00Z"); // Monday 18:30 in Calgary
        assertEquals(StylerPresence.OFFLINE, presence.status("S1", "America/Edmonton", "Alberta"));
    }

    @Test
    void readsTheStylistsOwnClock() {
        at("2026-10-12T14:30:00Z"); // 08:30 in Calgary, 10:30 in Toronto
        assertEquals(StylerPresence.OFFLINE, presence.status("S1", "America/Edmonton", "Alberta"));
        assertEquals(StylerPresence.ONLINE, presence.status("S1", "America/Toronto", "Ontario"));
    }

    @Test
    void offlineOnADayOff() {
        at("2026-10-12T17:30:00Z");
        when(exceptionRepo.findByStylerIdAndBlockedDate("S1", "2026-10-12"))
                .thenReturn(Optional.of(new AvailabilityExceptionEntity()));
        assertEquals(StylerPresence.OFFLINE, presence.status("S1", "America/Edmonton", "Alberta"));
    }

    @Test
    void offlineWithNoHoursSet() {
        at("2026-10-12T17:30:00Z");
        assertEquals(StylerPresence.OFFLINE, presence.status("S2", "America/Edmonton", "Alberta"));
    }
}

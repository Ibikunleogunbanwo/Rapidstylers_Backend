package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.entity.AvailabilityEntity;
import com.macrotel.rapidstylers.entity.AvailabilityExceptionEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.AvailabilityExceptionRepo;
import com.macrotel.rapidstylers.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A search result or a featured card should be able to say "open now, closes
 * 5:00 PM" on the professional's own clock instead of only reporting that they
 * are signed in. That means the hours have to arrive with the row.
 *
 * The two things worth pinning are the ones that break quietly: the row must not
 * be the cached DTO itself (that instance is shared by every surface, so writing
 * hours onto it would hand one professional's schedule to another caller), and
 * only the blocked dates still ahead should be shipped, because a professional's
 * exception history grows for years while only the days in front of them matter.
 */
class ListRowHoursTest {

    private AppService appService;
    private StylerRepo stylerRepo;
    private AvailabilityRepo availabilityRepo;
    private AvailabilityExceptionRepo availabilityExceptionRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        stylerRepo = mock(StylerRepo.class);
        availabilityRepo = mock(AvailabilityRepo.class);
        availabilityExceptionRepo = mock(AvailabilityExceptionRepo.class);
        appService.stylerRepo = stylerRepo;
        appService.dtoService = mock(DTOService.class);
        appService.objectMapper = new ObjectMapper();
        appService.availabilityRepo = availabilityRepo;
        appService.availabilityExceptionRepo = availabilityExceptionRepo;
        // Passthrough: serve what the loader would build, so the hours under test
        // come from the repos rather than a canned cache entry.
        ReadCacheService readCacheService = mock(ReadCacheService.class);
        appService.readCacheService = readCacheService;
        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        // Payments not configured: approval and a published address are then the
        // whole bookable gate, so the fixture states one variable per test.
        StripeService stripeService = mock(StripeService.class);
        when(stripeService.isConfigured()).thenReturn(false);
        appService.stripeService = stripeService;
        // The canned DTO stands in for the read cache: one instance, served to
        // every caller, which is exactly the object the row must not modify.
        StylerAccountDTO cached = new StylerAccountDTO();
        cached.setStylerId("S1");
        when(appService.dtoService.stylerAccountDTO(any(StylerEntity.class))).thenReturn(cached);
        when(stylerRepo.findAll()).thenReturn(Collections.singletonList(approvedStyler("S1")));
    }

    @Test
    void listRowsCarryTheWeeklyHoursAndTheComingBlockedDates() {
        when(availabilityRepo.findByStylerId("S1")).thenReturn(Collections.singletonList(
                window("S1", "2", "10:00", "17:00")));
        when(availabilityExceptionRepo.findByStylerId("S1")).thenReturn(Arrays.asList(
                blocked("S1", LocalDate.now().minusDays(30).toString(), "Vacation"),
                blocked("S1", LocalDate.now().plusDays(5).toString(), "Sick day")));

        List<?> rows = (List<?>) appService.listAllStylers().getData();
        StylerAccountDTO row = (StylerAccountDTO) rows.get(0);

        assertEquals(1, row.getAvailability().size());
        assertEquals("17:00", ((java.util.Map<?, ?>) row.getAvailability().get(0)).get("endTime"));
        // The past blocked date is gone; the one still coming is kept.
        assertEquals(1, row.getExceptions().size());
        assertEquals(LocalDate.now().plusDays(5).toString(),
                ((java.util.Map<?, ?>) row.getExceptions().get(0)).get("blockedDate"));
    }

    @Test
    void theSharedCachedDtoKeepsItsOwnState() {
        when(availabilityRepo.findByStylerId("S1")).thenReturn(Collections.singletonList(
                window("S1", "2", "10:00", "17:00")));

        List<?> rows = (List<?>) appService.listAllStylers().getData();
        StylerAccountDTO row = (StylerAccountDTO) rows.get(0);
        StylerAccountDTO shared = appService.dtoService.stylerAccountDTO(approvedStyler("S1"));

        assertNotSame(shared, row);
        assertNull(shared.getAvailability(), "the cached DTO must not be decorated in place");
        assertNull(shared.getExceptions(), "the cached DTO must not be decorated in place");
    }

    @Test
    void aProfessionalWithNoHoursSetSaysSoRatherThanNothing() {
        // No stubbing of either repo: the loader returns empty, and an empty list
        // is what lets a card say "no weekly hours set" instead of falling back to
        // a presence badge that means something else entirely.
        List<?> rows = (List<?>) appService.listAllStylers().getData();
        StylerAccountDTO row = (StylerAccountDTO) rows.get(0);

        assertTrue(row.getAvailability().isEmpty());
        assertTrue(row.getExceptions().isEmpty());
    }

    private StylerEntity approvedStyler(String id) {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId(id);
        styler.setVerificationStatus("APPROVED");
        // A published address is part of being listed.
        styler.setBusinessAddress("700 2 St SW, Calgary, Alberta");
        return styler;
    }

    private AvailabilityEntity window(String stylerId, String dayOfWeek, String start, String end) {
        AvailabilityEntity row = new AvailabilityEntity();
        row.setStylerId(stylerId);
        row.setDayOfWeek(dayOfWeek);
        row.setStartTime(start);
        row.setEndTime(end);
        return row;
    }

    private AvailabilityExceptionEntity blocked(String stylerId, String blockedDate, String reason) {
        AvailabilityExceptionEntity row = new AvailabilityExceptionEntity();
        row.setStylerId(stylerId);
        row.setBlockedDate(blockedDate);
        row.setReason(reason);
        return row;
    }
}

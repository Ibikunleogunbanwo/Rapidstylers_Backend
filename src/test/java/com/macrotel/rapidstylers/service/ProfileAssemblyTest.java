package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.dto.StylerPortfolioDTO;
import com.macrotel.rapidstylers.dto.StylerReviewDTO;
import com.macrotel.rapidstylers.dto.SubServiceDTO;
import com.macrotel.rapidstylers.entity.AvailabilityEntity;
import com.macrotel.rapidstylers.entity.AvailabilityExceptionEntity;
import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.ReviewEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.StylerPortfolioEntity;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.AvailabilityExceptionRepo;
import com.macrotel.rapidstylers.repo.AvailabilityRepo;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.ReviewRepo;
import com.macrotel.rapidstylers.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the Category B profile assembly: getStylerDetails reads every part
 * through its cache, so a warm profile costs exactly one DB query (the styler
 * row) and the response shape stays byte-identical to the pre-cache version.
 */
class ProfileAssemblyTest {

    private AppService appService;
    private StylerRepo stylerRepo;
    private SubServiceRepo subServiceRepo;
    private StylerPortfolioRepo stylerPortfolioRepo;
    private ReviewRepo reviewRepo;
    private BookAppointmentRepo bookAppointmentRepo;
    private AvailabilityRepo availabilityRepo;
    private AvailabilityExceptionRepo availabilityExceptionRepo;
    private DTOService dtoService;
    private ReadCacheService readCacheService;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        stylerRepo = mock(StylerRepo.class);
        subServiceRepo = mock(SubServiceRepo.class);
        stylerPortfolioRepo = mock(StylerPortfolioRepo.class);
        reviewRepo = mock(ReviewRepo.class);
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        availabilityRepo = mock(AvailabilityRepo.class);
        availabilityExceptionRepo = mock(AvailabilityExceptionRepo.class);
        dtoService = mock(DTOService.class);
        readCacheService = mock(ReadCacheService.class);
        appService.stylerRepo = stylerRepo;
        appService.subServiceRepo = subServiceRepo;
        appService.stylerPortfolioRepo = stylerPortfolioRepo;
        appService.reviewRepo = reviewRepo;
        appService.bookAppointmentRepo = bookAppointmentRepo;
        appService.availabilityRepo = availabilityRepo;
        appService.availabilityExceptionRepo = availabilityExceptionRepo;
        appService.dtoService = dtoService;
        appService.readCacheService = readCacheService;
    }

    @Test
    void warmCacheServesFullProfileWithoutTouchingChildRepos() {
        StylerEntity styler = approvedStyler("S1");
        when(stylerRepo.findByStylerId("S1")).thenReturn(Optional.of(styler));

        List<Object> subs = Collections.singletonList(Collections.singletonMap("name", "Cut"));
        List<Object> portfolio = Collections.singletonList(Collections.singletonMap("imageUrl", "u"));
        List<Object> reviews = new ArrayList<>();
        StylerReviewDTO r1 = new StylerReviewDTO();
        r1.setRatingScore("5");
        StylerReviewDTO r2 = new StylerReviewDTO();
        r2.setRatingScore("5");
        reviews.add(r1);
        reviews.add(r2);
        Map<String, Object> appointments = new HashMap<>();
        appointments.put("appointmentCount", "3");
        appointments.put("bookedSlots", Collections.singletonList(Collections.singletonMap("appointmentDate", "2030-01-01")));
        List<Object> availability = Collections.singletonList(Collections.singletonMap("dayOfWeek", "1"));
        List<Object> exceptions = Collections.singletonList(Collections.singletonMap("blockedDate", "2030-02-01"));
        StylerAccountDTO dto = new StylerAccountDTO();
        dto.setStylerId("S1");

        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.startsWith(ReadCacheService.KEY_STYLER_DTO)) return dto;
            if (key.startsWith(ReadCacheService.KEY_STYLER_SUBSERVICES)) return subs;
            if (key.startsWith(ReadCacheService.KEY_STYLER_PORTFOLIO)) return portfolio;
            if (key.startsWith(ReadCacheService.KEY_STYLER_REVIEWS)) return reviews;
            if (key.startsWith(ReadCacheService.KEY_STYLER_APPOINTMENTS)) return appointments;
            if (key.endsWith(":exceptions")) return exceptions;
            if (key.startsWith(ReadCacheService.KEY_STYLER_AVAILABILITY)) return availability;
            return ((Supplier<?>) inv.getArgument(3)).get();
        });

        BaseResponse response = appService.getStylerDetails("S1");

        assertEquals("200", response.getStatusCode());
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertEquals("S1", ((StylerAccountDTO) data.get("stylerInformation")).getStylerId());
        assertEquals(subs, data.get("stylerSubService"));
        assertEquals(portfolio, data.get("stylerPortfolio"));
        assertEquals(reviews, data.get("stylerReviews"));
        assertEquals("100", data.get("ratingPercentage"));
        assertEquals("3", data.get("appointmentCount"));
        assertEquals(availability, data.get("availability"));
        assertEquals(exceptions, data.get("exceptions"));
        assertNotNull(data.get("bookedSlots"));

        // Only the styler row is queried — every part comes from the cache.
        verify(stylerRepo).findByStylerId("S1");
        verify(subServiceRepo, never()).findByStylerId(anyString());
        verify(stylerPortfolioRepo, never()).findByStylerId(anyString());
        verify(reviewRepo, never()).findByStylerIdAndModerationStatus(anyString(), anyString());
        verify(bookAppointmentRepo, never()).findByStylerId(anyString());
        verify(availabilityRepo, never()).findByStylerId(anyString());
        verify(availabilityExceptionRepo, never()).findByStylerId(anyString());
    }

    @Test
    void coldCacheBuildsProfileFromReposWithIdenticalShape() {
        StylerEntity styler = approvedStyler("S1");
        when(stylerRepo.findByStylerId("S1")).thenReturn(Optional.of(styler));

        SubServiceEntity sub = new SubServiceEntity();
        when(subServiceRepo.findByStylerId("S1")).thenReturn(Collections.singletonList(sub));
        StylerPortfolioEntity portfolio = new StylerPortfolioEntity();
        when(stylerPortfolioRepo.findByStylerId("S1")).thenReturn(Collections.singletonList(portfolio));
        ReviewEntity review = new ReviewEntity();
        review.setStylerId("S1");
        review.setRatingScore(5);
        when(reviewRepo.findByStylerIdAndModerationStatus("S1", "APPROVED")).thenReturn(Collections.singletonList(review));
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setStatus("1");
        when(bookAppointmentRepo.findByStylerId("S1")).thenReturn(Collections.singletonList(appointment));
        AvailabilityEntity availability = new AvailabilityEntity();
        availability.setDayOfWeek("1");
        when(availabilityRepo.findByStylerId("S1")).thenReturn(Collections.singletonList(availability));
        AvailabilityExceptionEntity exception = new AvailabilityExceptionEntity();
        exception.setBlockedDate("2030-02-01");
        when(availabilityExceptionRepo.findByStylerId("S1")).thenReturn(Collections.singletonList(exception));

        StylerAccountDTO stylerDto = new StylerAccountDTO();
        stylerDto.setStylerId("S1");
        when(dtoService.stylerAccountDTO(styler)).thenReturn(stylerDto);
        when(dtoService.subServiceDTO(any())).thenReturn(new SubServiceDTO());
        when(dtoService.stylerPortfolioDTO(any())).thenReturn(new StylerPortfolioDTO());
        when(dtoService.stylerReviewDTO(any())).thenAnswer(inv -> {
            StylerReviewDTO r = new StylerReviewDTO();
            r.setRatingScore(String.valueOf(((ReviewEntity) inv.getArgument(0)).getRatingScore()));
            return r;
        });
        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());

        BaseResponse response = appService.getStylerDetails("S1");

        assertEquals("200", response.getStatusCode());
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertEquals("S1", ((StylerAccountDTO) data.get("stylerInformation")).getStylerId());
        assertEquals(1, ((List<?>) data.get("stylerSubService")).size());
        assertEquals(1, ((List<?>) data.get("stylerPortfolio")).size());
        assertEquals(1, ((List<?>) data.get("stylerReviews")).size());
        assertEquals("100", data.get("ratingPercentage"));
        assertEquals("1", data.get("appointmentCount"));
        assertEquals(1, ((List<?>) data.get("availability")).size());
        assertEquals(1, ((List<?>) data.get("exceptions")).size());
        assertEquals(1, ((List<?>) data.get("bookedSlots")).size());
    }

    private StylerEntity approvedStyler(String id) {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId(id);
        styler.setVerificationStatus("APPROVED");
        return styler;
    }
}

package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.entity.ReviewEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.repo.ReviewRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The public profile's header line has to tell two different professionals
 * apart: one who genuinely just joined, and one who has been on the platform a
 * while but has never been reviewed. The second case is the common one, and
 * claiming "New on RapidStylers" for it is simply untrue.
 *
 * <p>That rule lives on the frontend, so what these tests pin is the data it
 * needs from this service: the review aggregates it already had, plus the
 * registration date it did not. Both write paths store an ISO {@code yyyy-MM-dd}
 * — the signup constructor from {@code LocalDate.now()} and the demo seeder
 * explicitly — so the value arrives in one shape. A row with no date must
 * surface as null rather than a substitute, because a date nobody stored is a
 * claim nobody can back.</p>
 */
class StylerProfileStandingTest {

    private DTOService dtoService;
    private ReviewRepo reviewRepo;
    private StripeService stripeService;

    @BeforeEach
    void setUp() {
        reviewRepo = mock(ReviewRepo.class);
        stripeService = mock(StripeService.class);
        when(stripeService.isConfigured()).thenReturn(false);

        dtoService = new DTOService();
        dtoService.stripeService = stripeService;
        dtoService.reviewRepo = reviewRepo;
    }

    private StylerEntity styler(String id, String insertedDt) {
        StylerEntity styler = new StylerEntity();
        styler.setStylerId(id);
        styler.setBusinessName("Pro " + id);
        styler.setStatus("0");
        styler.setIsOnline("0");
        styler.setVerificationStatus("APPROVED");
        styler.setInsertedDt(insertedDt);
        return styler;
    }

    private void approvedReviews(List<ReviewEntity> reviews) {
        when(reviewRepo.findByStylerIdAndModerationStatus(anyString(), anyString())).thenReturn(reviews);
    }

    private ReviewEntity review(int score) {
        ReviewEntity review = new ReviewEntity();
        review.setRatingScore(score);
        return review;
    }

    @Test
    void carriesTheStoredJoinDate() {
        approvedReviews(Collections.emptyList());

        StylerAccountDTO dto = dtoService.stylerAccountDTO(styler("S1", "2026-01-05"));

        assertEquals("2026-01-05", dto.getDateRegistered());
    }

    @Test
    void anAbsentJoinDateIsNeverInvented() {
        approvedReviews(Collections.emptyList());

        // No date on file means no claim about how new the professional is.
        assertNull(dtoService.stylerAccountDTO(styler("S3", null)).getDateRegistered());

        // A blank value stays blank rather than being quietly replaced with
        // today, which would make every unreadable row look brand new.
        String blank = dtoService.stylerAccountDTO(styler("S4", "  ")).getDateRegistered();
        assertTrue(blank == null || blank.trim().isEmpty(),
                "a blank stored date must not come back as a usable one, got: " + blank);
    }

    @Test
    void aReviewlessStylerReportsTheEmptyAggregateTheHeaderReads() {
        approvedReviews(Collections.emptyList());

        StylerAccountDTO dto = dtoService.stylerAccountDTO(styler("S5", "2026-08-01"));

        assertEquals(Long.valueOf(0L), dto.getReviewCount());
        assertEquals(Double.valueOf(0.0), dto.getAverageRating());
    }

    @Test
    void anEstablishedStylerReportsItsRealAggregateAlongsideTheJoinDate() {
        approvedReviews(List.of(review(5), review(4), review(5)));

        StylerAccountDTO dto = dtoService.stylerAccountDTO(styler("S6", "2025-03-11"));

        assertEquals(Long.valueOf(3L), dto.getReviewCount());
        assertEquals(Double.valueOf(4.7), dto.getAverageRating());
        // The date rides along either way; the review count is what decides
        // whether anything ever consults it.
        assertEquals("2025-03-11", dto.getDateRegistered());
    }
}

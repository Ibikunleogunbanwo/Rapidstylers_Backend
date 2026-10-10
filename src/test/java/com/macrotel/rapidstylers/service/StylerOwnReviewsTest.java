package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.dto.StylerReviewDTO;
import com.macrotel.rapidstylers.entity.ReviewEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.ReviewRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The professional's own reviews view. It must show what clients see (approved
 * rows, averaged the same way the public profile averages them) while still
 * accounting for reviews that are sitting in moderation, so a stylist never
 * concludes a review was lost.
 */
class StylerOwnReviewsTest {

    private AppService appService;
    private StylerRepo stylerRepo;
    private ReviewRepo reviewRepo;
    private ReadCacheService readCacheService;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        stylerRepo = mock(StylerRepo.class);
        reviewRepo = mock(ReviewRepo.class);
        readCacheService = mock(ReadCacheService.class);
        appService.stylerRepo = stylerRepo;
        appService.reviewRepo = reviewRepo;
        appService.readCacheService = readCacheService;
        appService.dtoService = new DTOService();
        // Serve what the loader would build, so Redis never enters the picture.
        when(readCacheService.getOrLoad(anyString(), any(Duration.class), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
    }

    private static StylerEntity styler(String id) {
        StylerEntity entity = new StylerEntity();
        entity.setStylerId(id);
        return entity;
    }

    private static ReviewEntity review(String stylerId, String userName, int score, String status) {
        ReviewEntity entity = new ReviewEntity();
        entity.setStylerId(stylerId);
        entity.setUserName(userName);
        entity.setRatingScore(score);
        entity.setMessage(userName + " says hello");
        entity.setModerationStatus(status);
        entity.setCreatedAt("2026-09-01 10:00:00");
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(BaseResponse response) {
        return (Map<String, Object>) response.getData();
    }

    @Test
    void summarisesApprovedReviewsAndCountsWhatIsInModeration() {
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler("STYLER1")));
        when(reviewRepo.findByStylerIdAndModerationStatus("STYLER1", "APPROVED"))
                .thenReturn(Arrays.asList(
                        review("STYLER1", "Ada", 5, "APPROVED"),
                        review("STYLER1", "Bo", 3, "APPROVED")));
        when(reviewRepo.findByStylerId("STYLER1"))
                .thenReturn(Arrays.asList(
                        review("STYLER1", "Ada", 5, "APPROVED"),
                        review("STYLER1", "Bo", 3, "APPROVED"),
                        review("STYLER1", "Cy", 4, "PENDING")));

        BaseResponse response = appService.getOwnStylerReviews("STYLER1");

        assertEquals("200", response.getStatusCode());
        Map<String, Object> data = dataOf(response);
        assertEquals(2, data.get("reviewCount"));
        assertEquals(4.0, data.get("averageRating"));
        assertEquals(1, data.get("pendingCount"));
        assertEquals(2, ((List<?>) data.get("reviews")).size());
    }

    @Test
    void aPendingReviewNeverLeaksIntoTheListClientsSee() {
        when(stylerRepo.findByStylerId("STYLER2")).thenReturn(Optional.of(styler("STYLER2")));
        when(reviewRepo.findByStylerIdAndModerationStatus("STYLER2", "APPROVED"))
                .thenReturn(Collections.emptyList());
        when(reviewRepo.findByStylerId("STYLER2"))
                .thenReturn(Collections.singletonList(review("STYLER2", "Cy", 4, "PENDING")));

        Map<String, Object> data = dataOf(appService.getOwnStylerReviews("STYLER2"));

        assertEquals(0, data.get("reviewCount"));
        assertNull(data.get("averageRating"), "no approved ratings means no average to report");
        assertEquals(1, data.get("pendingCount"));
        assertTrue(((List<?>) data.get("reviews")).isEmpty());
    }

    @Test
    void averagesToTheSamePrecisionThePublicProfileShows() {
        when(stylerRepo.findByStylerId("STYLER3")).thenReturn(Optional.of(styler("STYLER3")));
        when(reviewRepo.findByStylerIdAndModerationStatus("STYLER3", "APPROVED"))
                .thenReturn(Arrays.asList(
                        review("STYLER3", "Ada", 5, "APPROVED"),
                        review("STYLER3", "Bo", 4, "APPROVED"),
                        review("STYLER3", "Cy", 4, "APPROVED")));
        when(reviewRepo.findByStylerId("STYLER3"))
                .thenReturn(Arrays.asList(
                        review("STYLER3", "Ada", 5, "APPROVED"),
                        review("STYLER3", "Bo", 4, "APPROVED"),
                        review("STYLER3", "Cy", 4, "APPROVED")));

        Map<String, Object> data = dataOf(appService.getOwnStylerReviews("STYLER3"));

        // 13 / 3 = 4.333... -> 4.3, one decimal, no trailing zeros to confuse the UI.
        assertEquals(4.3, data.get("averageRating"));
    }

    @Test
    void anUnknownStylerIsRejectedRatherThanShowingAnEmptyList() {
        when(stylerRepo.findByStylerId("NOPE")).thenReturn(Optional.empty());

        BaseResponse response = appService.getOwnStylerReviews("NOPE");

        assertNotEquals("200", response.getStatusCode());
        assertEquals("Invalid Styler Id", response.getMessage());
    }

    @Test
    void aMalformedScoreIsLeftOutOfTheAverageInsteadOfBreakingIt() {
        DTOService dtoService = new DTOService();
        StylerReviewDTO scored = dtoService.stylerReviewDTO(review("STYLER4", "Ada", 5, "APPROVED"));
        StylerReviewDTO unparsable = dtoService.stylerReviewDTO(review("STYLER4", "Bo", 4, "APPROVED"));
        unparsable.setRatingScore("n/a");
        StylerReviewDTO unscored = dtoService.stylerReviewDTO(review("STYLER4", "Cy", 2, "APPROVED"));
        unscored.setRatingScore(null);

        assertEquals(5.0, AppService.averageReviewRating(Arrays.asList(scored, unparsable, unscored)));
        assertNull(AppService.averageReviewRating(Collections.emptyList()));
    }
}

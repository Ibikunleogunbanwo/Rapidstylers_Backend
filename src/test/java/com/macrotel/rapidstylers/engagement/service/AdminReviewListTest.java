package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.engagement.entity.ReviewEntity;
import com.macrotel.rapidstylers.engagement.repo.ReviewRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.support.TestServices;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The admin can page and search published, hidden and pending reviews. */
class AdminReviewListTest {

    private ReviewService reviewService;
    private ReviewRepo reviewRepo;

    @BeforeEach
    void setUp() {
        TestServices services = new TestServices();
        reviewRepo = mock(ReviewRepo.class);
        StylerRepo stylerRepo = mock(StylerRepo.class);
        services.reviewRepo = reviewRepo;
        services.stylerRepo = stylerRepo;
        reviewService = services.reviewService();
        StylerEntity studio = new StylerEntity();
        studio.setStylerId("CM1");
        studio.setBusinessName("Crown Studio");
        when(stylerRepo.findByStylerId("CM1")).thenReturn(Optional.of(studio));
        when(reviewRepo.adminSearch(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void listsPublishedReviewsByDefaultWithTheStudioName() {
        ReviewEntity review = new ReviewEntity();
        review.setId(9L);
        review.setStylerId("CM1");
        review.setUserName("Ada Obi");
        review.setRatingScore(5);
        review.setMessage("Great braids");
        review.setModerationStatus("APPROVED");
        when(reviewRepo.adminSearch(eq("APPROVED"), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(review), PageRequest.of(0, 20), 41));

        BaseResponse response = reviewService.listReviewsForAdmin(null, "  ", null, null);

        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertEquals(41L, data.get("total"));
        assertEquals(0, data.get("page"));
        assertEquals(20, data.get("size"));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) data.get("items")).get(0);
        assertEquals(9L, item.get("reviewId"));
        assertEquals("Crown Studio", item.get("businessName"));
        assertEquals("Ada Obi", item.get("userName"));
        assertEquals(5, item.get("ratingScore"));
        assertEquals("Great braids", item.get("message"));
        assertEquals("APPROVED", item.get("moderationStatus"));
        assertEquals(review.getCreatedAt(), item.get("createdAt"));
    }

    @Test
    void searchesCaseInsensitivelyAndPagesHiddenReviews() {
        reviewService.listReviewsForAdmin("rejected", " Crown%_ ", 2, 500);

        verify(reviewRepo).adminSearch("REJECTED", "%crown%", PageRequest.of(2, 100));
    }

    @Test
    void refusesAnUnknownStatus() {
        assertEquals("400", reviewService.listReviewsForAdmin("DELETED", null, 0, 20).getStatusCode());
    }
}

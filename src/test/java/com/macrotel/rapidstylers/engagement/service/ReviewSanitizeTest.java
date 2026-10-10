package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.support.TestServices;
import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.engagement.entity.ReviewEntity;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.engagement.pojo.ReviewData;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.engagement.repo.ReviewRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewSanitizeTest {

    private TestServices services;
    private UserRepo userRepo;
    private StylerRepo stylerRepo;
    private BookAppointmentRepo bookAppointmentRepo;
    private ReviewRepo reviewRepo;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        userRepo = mock(UserRepo.class);
        stylerRepo = mock(StylerRepo.class);
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        reviewRepo = mock(ReviewRepo.class);
        services.userRepo = userRepo;
        services.stylerRepo = stylerRepo;
        services.bookAppointmentRepo = bookAppointmentRepo;
        services.reviewRepo = reviewRepo;
    }

    @Test
    void reviewMessageIsSanitizedBeforeSave() {
        UserEntity user = new UserEntity();
        user.setFirstname("Ada");
        user.setLastname("Lovelace");
        when(userRepo.findByUserId("U1")).thenReturn(Optional.of(user));

        when(stylerRepo.findByStylerId("S1")).thenReturn(Optional.of(new StylerEntity()));

        BookAppointmentEntity booking = new BookAppointmentEntity();
        booking.setUserId("U1");
        booking.setStylerId("S1");
        booking.setStatus("0"); // completed
        when(bookAppointmentRepo.findByAppointmentId("100")).thenReturn(Optional.of(booking));

        when(reviewRepo.findByBookingId("100")).thenReturn(Optional.empty());

        ReviewData data = new ReviewData();
        data.setUserId("U1");
        data.setStylerId("S1");
        data.setBookingId("100");
        data.setRatingScore("5");
        data.setReviewMessage("Amazing <script>alert('x')</script> work, <img src=x onerror=alert(1)> thank you!");

        BaseResponse response = services.reviewService().createStylerReview(data);

        assertEquals("200", response.getStatusCode());
        ArgumentCaptor<ReviewEntity> captor = ArgumentCaptor.forClass(ReviewEntity.class);
        verify(reviewRepo).save(captor.capture());
        assertEquals("Amazing work, thank you!", captor.getValue().getMessage());
        assertEquals("Ada Lovelace", captor.getValue().getUserName());
    }
}

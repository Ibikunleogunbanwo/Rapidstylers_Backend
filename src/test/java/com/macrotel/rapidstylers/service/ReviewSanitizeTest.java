package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.entity.ReviewEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.ReviewData;
import com.macrotel.rapidstylers.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.repo.ReviewRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
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

    private AppService appService;
    private UserRepo userRepo;
    private StylerRepo stylerRepo;
    private BookAppointmentRepo bookAppointmentRepo;
    private ReviewRepo reviewRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        userRepo = mock(UserRepo.class);
        stylerRepo = mock(StylerRepo.class);
        bookAppointmentRepo = mock(BookAppointmentRepo.class);
        reviewRepo = mock(ReviewRepo.class);
        appService.userRepo = userRepo;
        appService.stylerRepo = stylerRepo;
        appService.bookAppointmentRepo = bookAppointmentRepo;
        appService.reviewRepo = reviewRepo;
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

        BaseResponse response = appService.createStylerReview(data);

        assertEquals("200", response.getStatusCode());
        ArgumentCaptor<ReviewEntity> captor = ArgumentCaptor.forClass(ReviewEntity.class);
        verify(reviewRepo).save(captor.capture());
        assertEquals("Amazing work, thank you!", captor.getValue().getMessage());
        assertEquals("Ada Lovelace", captor.getValue().getUserName());
    }
}

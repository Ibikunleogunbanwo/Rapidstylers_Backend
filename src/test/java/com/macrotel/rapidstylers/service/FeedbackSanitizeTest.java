package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.FeedbackEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.UserFeedbackData;
import com.macrotel.rapidstylers.repo.FeedBackRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackSanitizeTest {

    private AppService appService;
    private UserRepo userRepo;
    private FeedBackRepo feedBackRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        userRepo = mock(UserRepo.class);
        feedBackRepo = mock(FeedBackRepo.class);
        appService.userRepo = userRepo;
        appService.feedBackRepo = feedBackRepo;
    }

    @Test
    void feedbackMessageIsSanitizedBeforeSave() {
        UserEntity user = new UserEntity();
        when(userRepo.findByUserId("U1")).thenReturn(Optional.of(user));

        UserFeedbackData data = new UserFeedbackData();
        data.setUserId("U1");
        data.setEmailAddress("customer@example.com");
        data.setFeedbackType("BUG");
        data.setMessage("<script>alert(1)</script><img src=x onerror=alert(2)> the app broke");

        BaseResponse response = appService.addUserFeedBack(data);

        assertEquals("200", response.getStatusCode());
        ArgumentCaptor<FeedbackEntity> captor = ArgumentCaptor.forClass(FeedbackEntity.class);
        verify(feedBackRepo).save(captor.capture());
        assertEquals("the app broke", captor.getValue().getMessage());
    }
}

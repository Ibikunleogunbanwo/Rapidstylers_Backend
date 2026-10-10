package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.support.TestServices;
import com.macrotel.rapidstylers.engagement.entity.FeedbackEntity;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.engagement.pojo.UserFeedbackData;
import com.macrotel.rapidstylers.engagement.repo.FeedBackRepo;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackSanitizeTest {

    private TestServices services;
    private UserRepo userRepo;
    private FeedBackRepo feedBackRepo;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        userRepo = mock(UserRepo.class);
        feedBackRepo = mock(FeedBackRepo.class);
        services.userRepo = userRepo;
        services.feedBackRepo = feedBackRepo;
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

        BaseResponse response = services.feedbackService().addUserFeedBack(data);

        assertEquals("200", response.getStatusCode());
        ArgumentCaptor<FeedbackEntity> captor = ArgumentCaptor.forClass(FeedbackEntity.class);
        verify(feedBackRepo).save(captor.capture());
        assertEquals("the app broke", captor.getValue().getMessage());
    }
}

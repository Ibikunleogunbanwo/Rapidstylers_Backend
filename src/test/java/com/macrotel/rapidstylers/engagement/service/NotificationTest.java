package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.support.TestServices;
import com.macrotel.rapidstylers.engagement.entity.NotificationEntity;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.engagement.pojo.NotificationPreferencesData;
import com.macrotel.rapidstylers.engagement.repo.NotificationRepo;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationTest {
    private TestServices services;
    private NotificationRepo notificationRepo;
    private UserRepo userRepo;

    @BeforeEach
    void setUp() {
        services = new TestServices();
        notificationRepo = mock(NotificationRepo.class);
        userRepo = mock(UserRepo.class);
        services.notificationRepo = notificationRepo;
        services.userRepo = userRepo;
    }

    @Test
    void listReturnsOnlyTheAuthenticatedCustomersNotificationsAndUnreadCount() {
        UserEntity user = new UserEntity();
        NotificationEntity notification = new NotificationEntity("USER1", "STYLER1", "PRICE", "Price changed", "The price changed.");
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(user));
        when(notificationRepo.findByUserIdOrderByCreatedAtDesc("USER1"))
                .thenReturn(Collections.singletonList(notification));
        when(notificationRepo.countByUserIdAndReadFalse("USER1")).thenReturn(1L);

        BaseResponse response = services.notificationService().listNotifications("USER1");

        assertEquals("200", response.getStatusCode());
        assertEquals(1L, ((java.util.Map<?, ?>) response.getData()).get("unreadCount"));
    }

    @Test
    void readAndPreferenceUpdatesAreScopedToTheAuthenticatedCustomer() {
        UserEntity user = new UserEntity();
        NotificationEntity notification = new NotificationEntity("USER1", "STYLER1", "AVAILABILITY", "Hours changed", "Hours changed.");
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(user));
        when(notificationRepo.findByIdAndUserId(4L, "USER1")).thenReturn(Optional.of(notification));

        BaseResponse readResponse = services.notificationService().markNotificationRead("USER1", 4L);
        NotificationPreferencesData preferences = new NotificationPreferencesData();
        preferences.setAvailability(false);
        preferences.setPrice(true);
        preferences.setVerification(false);
        BaseResponse preferenceResponse = services.notificationService().updateNotificationPreferences("USER1", preferences);

        assertEquals("200", readResponse.getStatusCode());
        assertEquals("200", preferenceResponse.getStatusCode());
        assertEquals(false, user.getNotifySavedAvailability());
        assertEquals(false, user.getNotifySavedVerification());
        verify(notificationRepo).save(notification);
        verify(userRepo).save(user);
    }
}

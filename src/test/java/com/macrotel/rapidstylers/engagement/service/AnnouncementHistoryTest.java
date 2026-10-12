package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.engagement.entity.AnnouncementEntity;
import com.macrotel.rapidstylers.engagement.repo.AnnouncementRepo;
import com.macrotel.rapidstylers.engagement.repo.NotificationRepo;
import com.macrotel.rapidstylers.support.TestServices;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Sent announcements are kept for the admin's history, and the audience can be counted first. */
class AnnouncementHistoryTest {

    private NotificationService notificationService;
    private UserRepo userRepo;
    private AnnouncementRepo announcementRepo;

    @BeforeEach
    void setUp() {
        TestServices services = new TestServices();
        userRepo = mock(UserRepo.class);
        services.userRepo = userRepo;
        services.notificationRepo = mock(NotificationRepo.class);
        notificationService = services.notificationService();
        announcementRepo = mock(AnnouncementRepo.class);
        notificationService.announcementRepo = announcementRepo;
    }

    @Test
    void anAnnouncementToEveryoneIsRecorded() {
        when(userRepo.findActiveUserIds()).thenReturn(List.of("U1", "U2", "U3"));

        BaseResponse response = notificationService.sendAnnouncement("admin@example.com", " Holiday hours ", "We close early.", null);

        assertEquals("200", response.getStatusCode());
        ArgumentCaptor<AnnouncementEntity> saved = ArgumentCaptor.forClass(AnnouncementEntity.class);
        verify(announcementRepo).save(saved.capture());
        assertEquals("Holiday hours", saved.getValue().getTitle());
        assertEquals("We close early.", saved.getValue().getMessage());
        assertEquals(3, saved.getValue().getRecipientCount());
        assertNull(saved.getValue().getRecipientEmail());
        assertEquals("admin@example.com", saved.getValue().getSentBy());
    }

    @Test
    void anAnnouncementToOneCustomerRecordsTheirEmail() {
        UserEntity ada = new UserEntity();
        ada.setUserId("U1");
        ada.setEmailAddress("ada@example.com");
        when(userRepo.findByEmailAddress("ada@example.com")).thenReturn(Optional.of(ada));

        notificationService.sendAnnouncement("admin@example.com", "Hi", "Message", "ada@example.com");

        ArgumentCaptor<AnnouncementEntity> saved = ArgumentCaptor.forClass(AnnouncementEntity.class);
        verify(announcementRepo).save(saved.capture());
        assertEquals(1, saved.getValue().getRecipientCount());
        assertEquals("ada@example.com", saved.getValue().getRecipientEmail());
    }

    @Test
    void aRefusedAnnouncementIsNotRecorded() {
        assertEquals("400", notificationService.sendAnnouncement("a", " ", "Message", null).getStatusCode());
        verify(announcementRepo, never()).save(any());
    }

    @Test
    void historyListsWhatWasSent() {
        AnnouncementEntity sent = new AnnouncementEntity();
        sent.setId(4L);
        sent.setTitle("Holiday hours");
        sent.setMessage("We close early.");
        sent.setRecipientCount(3);
        sent.setSentAt(LocalDateTime.of(2026, 10, 12, 9, 30));
        sent.setSentBy("admin@example.com");
        when(announcementRepo.findTop200ByOrderBySentAtDescIdDesc()).thenReturn(List.of(sent));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) notificationService.listAnnouncements().getData();

        assertEquals(1, rows.size());
        assertEquals("Holiday hours", rows.get(0).get("title"));
        assertEquals(3, rows.get(0).get("recipientCount"));
        assertNull(rows.get(0).get("recipientEmail"));
        assertEquals("admin@example.com", rows.get(0).get("sentBy"));
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 30), OffsetDateTime.parse((String) rows.get(0).get("sentAt")).toLocalDateTime());
    }

    @Test
    void audienceCountIsTheActiveCustomers() {
        when(userRepo.findActiveUserIds()).thenReturn(List.of("U1", "U2"));

        assertEquals(Map.of("count", 2), notificationService.announcementAudienceCount().getData());
    }
}

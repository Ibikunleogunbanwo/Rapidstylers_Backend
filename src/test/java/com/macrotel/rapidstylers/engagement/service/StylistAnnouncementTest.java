package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.engagement.entity.AnnouncementEntity;
import com.macrotel.rapidstylers.engagement.entity.NotificationEntity;
import com.macrotel.rapidstylers.engagement.repo.AnnouncementRepo;
import com.macrotel.rapidstylers.engagement.repo.NotificationRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import com.macrotel.rapidstylers.support.TestServices;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Announcements reach approved stylists (all, or one by email), who read them in their own inbox. */
class StylistAnnouncementTest {

    private NotificationService notificationService;
    private NotificationRepo notificationRepo;
    private StylerRepo stylerRepo;
    private UserRepo userRepo;
    private AnnouncementRepo announcementRepo;

    @BeforeEach
    void setUp() {
        TestServices services = new TestServices();
        userRepo = mock(UserRepo.class);
        stylerRepo = mock(StylerRepo.class);
        notificationRepo = mock(NotificationRepo.class);
        services.userRepo = userRepo;
        services.stylerRepo = stylerRepo;
        services.notificationRepo = notificationRepo;
        notificationService = services.notificationService();
        announcementRepo = mock(AnnouncementRepo.class);
        notificationService.announcementRepo = announcementRepo;
    }

    @SuppressWarnings("unchecked")
    private List<NotificationEntity> savedRows() {
        ArgumentCaptor<List<NotificationEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(notificationRepo).saveAll(rows.capture());
        return rows.getValue();
    }

    @Test
    void anAnnouncementToAllStylistsGoesToEachApprovedStylistsInbox() {
        when(stylerRepo.findActiveApprovedStylerIds()).thenReturn(List.of("S1", "S2"));

        BaseResponse response = notificationService.sendAnnouncement("admin@example.com", "Payout dates", "Payouts move to Friday.", null, "stylists");

        assertEquals("200", response.getStatusCode());
        assertEquals(Map.of("sent", 2), response.getData());
        List<NotificationEntity> rows = savedRows();
        assertEquals("S1", rows.get(0).getStylerId());
        assertEquals("", rows.get(0).getUserId());
        assertEquals(NotificationEntity.RECIPIENT_STYLIST, rows.get(0).getRecipientRole());
        assertEquals("ANNOUNCEMENT", rows.get(0).getType());
        verify(userRepo, never()).findActiveUserIds();
        ArgumentCaptor<AnnouncementEntity> sent = ArgumentCaptor.forClass(AnnouncementEntity.class);
        verify(announcementRepo).save(sent.capture());
        assertEquals("STYLISTS", sent.getValue().getAudience());
        assertEquals(2, sent.getValue().getRecipientCount());
    }

    @Test
    void anAnnouncementToOneStylistIsFoundByEmail() {
        StylerEntity chloe = new StylerEntity();
        chloe.setStylerId("S9");
        chloe.setEmailAddress("chloe@example.com");
        when(stylerRepo.findByEmailAddress("chloe@example.com")).thenReturn(Optional.of(chloe));

        BaseResponse response = notificationService.sendAnnouncement("admin@example.com", "Hi", "Message", "chloe@example.com", "STYLISTS");

        assertEquals("200", response.getStatusCode());
        List<NotificationEntity> rows = savedRows();
        assertEquals(1, rows.size());
        assertEquals("S9", rows.get(0).getStylerId());
        ArgumentCaptor<AnnouncementEntity> sent = ArgumentCaptor.forClass(AnnouncementEntity.class);
        verify(announcementRepo).save(sent.capture());
        assertEquals("chloe@example.com", sent.getValue().getRecipientEmail());
    }

    @Test
    void anUnknownStylistEmailIsRefused() {
        when(stylerRepo.findByEmailAddress(any())).thenReturn(Optional.empty());

        BaseResponse response = notificationService.sendAnnouncement("a", "Hi", "Message", "x@example.com", "STYLISTS");

        assertEquals("400", response.getStatusCode());
        assertEquals("No stylist with that email", response.getMessage());
        verify(notificationRepo, never()).saveAll(anyList());
    }

    @Test
    void customersStayTheDefaultAudience() {
        when(userRepo.findActiveUserIds()).thenReturn(List.of("U1"));

        notificationService.sendAnnouncement("a", "Hi", "Message", null);

        List<NotificationEntity> rows = savedRows();
        assertEquals("U1", rows.get(0).getUserId());
        assertEquals(NotificationEntity.RECIPIENT_CUSTOMER, rows.get(0).getRecipientRole());
        verify(stylerRepo, never()).findActiveApprovedStylerIds();
    }

    @Test
    void anUnknownAudienceIsRefused() {
        assertEquals("Audience must be CUSTOMERS or STYLISTS",
                notificationService.sendAnnouncement("a", "Hi", "Message", null, "EVERYONE").getMessage());
        assertEquals("400", notificationService.announcementAudienceCount("EVERYONE").getStatusCode());
    }

    @Test
    void theAudienceCountFollowsTheAudience() {
        when(stylerRepo.findActiveApprovedStylerIds()).thenReturn(List.of("S1", "S2", "S3"));
        when(userRepo.findActiveUserIds()).thenReturn(List.of("U1"));

        assertEquals(Map.of("count", 3), notificationService.announcementAudienceCount("STYLISTS").getData());
        assertEquals(Map.of("count", 1), notificationService.announcementAudienceCount(null).getData());
        assertEquals(Map.of("count", 1), notificationService.announcementAudienceCount("customers").getData());
    }

    @Test
    void historyRowsSayWhichAudience() {
        AnnouncementEntity sent = new AnnouncementEntity();
        sent.setTitle("t");
        sent.setMessage("m");
        sent.setAudience("STYLISTS");
        when(announcementRepo.findTop200ByOrderBySentAtDescIdDesc()).thenReturn(List.of(sent));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) notificationService.listAnnouncements().getData();
        assertEquals("STYLISTS", rows.get(0).get("audience"));
    }

    // ── The stylist's inbox ───────────────────────────────────────────

    private StylerInboxService inbox() {
        StylerInboxService inbox = new StylerInboxService();
        inbox.notificationRepo = notificationRepo;
        inbox.stylerRecords = new StylerRecords(stylerRepo, null);
        StylerEntity chloe = new StylerEntity();
        chloe.setStylerId("S1");
        when(stylerRepo.findByStylerId("S1")).thenReturn(Optional.of(chloe));
        return inbox;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStylistReadsOnlyTheirOwnInbox() {
        NotificationEntity note = NotificationEntity.forStylist("S1", "ANNOUNCEMENT", "Payout dates", "Friday.");
        note.setId(5L);
        note.setCreatedAt("2026-10-12 15:04:05");
        when(notificationRepo.findByStylerIdAndRecipientRoleOrderByCreatedAtDesc("S1", "STYLIST")).thenReturn(List.of(note));
        when(notificationRepo.countByStylerIdAndRecipientRoleAndReadFalse("S1", "STYLIST")).thenReturn(1L);

        Map<String, Object> data = (Map<String, Object>) inbox().listNotifications("S1").getData();

        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertEquals(1, items.size());
        assertEquals("Payout dates", items.get(0).get("title"));
        assertEquals("2026-10-12 15:04:05", items.get(0).get("createdAt"));
        assertTrue(((String) items.get(0).get("createdAtUtc")).endsWith("Z"));
        assertEquals(1L, data.get("unreadCount"));
        verify(notificationRepo, never()).findByUserIdOrderByCreatedAtDesc(any());
    }

    @Test
    void aStylistCannotTouchSomeoneElsesNotification() {
        when(notificationRepo.findByIdAndStylerIdAndRecipientRole(5L, "S1", "STYLIST")).thenReturn(Optional.empty());

        StylerInboxService inbox = inbox();
        assertEquals("400", inbox.markRead("S1", 5L).getStatusCode());
        assertEquals("400", inbox.delete("S1", 5L).getStatusCode());
        verify(notificationRepo, never()).delete(any());
    }

    @Test
    void markingReadSavesTheStylistsOwnRow() {
        NotificationEntity note = NotificationEntity.forStylist("S1", "ANNOUNCEMENT", "t", "m");
        when(notificationRepo.findByIdAndStylerIdAndRecipientRole(5L, "S1", "STYLIST")).thenReturn(Optional.of(note));

        assertEquals("200", inbox().markRead("S1", 5L).getStatusCode());
        assertTrue(note.isRead());
        verify(notificationRepo).save(note);
    }
}

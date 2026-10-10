package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.engagement.entity.NotificationEntity;
import com.macrotel.rapidstylers.engagement.repo.NotificationRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.support.TestServices;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Booking events land in the customer's in-app inbox, and customers can delete their own entries. */
class NotificationInboxTest {

    private NotificationRepo notificationRepo;
    private OutboxEventService outbox;
    private NotificationService service;
    private UserRepo userRepo;

    @BeforeEach
    void setUp() {
        TestServices services = new TestServices();
        notificationRepo = mock(NotificationRepo.class);
        outbox = mock(OutboxEventService.class);
        services.notificationRepo = notificationRepo;
        services.outboxEventService = outbox;
        userRepo = mock(UserRepo.class);
        services.userRepo = userRepo;
        service = services.notificationService();
    }

    @Test
    void bookingEventGoesToTheCustomersInboxAndTheEmailStillGoesOut() {
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setUserId("U1");
        appointment.setStylerId("S1");
        appointment.setAppointmentDate("2026-10-12");
        appointment.setArrivalTime("10:00");

        service.sendAppointmentNotification(appointment, "Confirmed", "Your appointment has been confirmed",
                "The professional has accepted your booking request.", "Appointment confirmed", "You confirmed it.");

        ArgumentCaptor<NotificationEntity> saved = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepo).save(saved.capture());
        assertEquals("U1", saved.getValue().getUserId());
        assertEquals("BOOKING", saved.getValue().getType());
        assertEquals("Your appointment has been confirmed", saved.getValue().getTitle());
        assertTrue(saved.getValue().getMessage().endsWith("Appointment: 2026-10-12 at 10:00."));
        verify(outbox).appointmentNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void inboxFailureNeverStopsTheEmail() {
        when(notificationRepo.save(any())).thenThrow(new RuntimeException("db down"));
        BookAppointmentEntity appointment = new BookAppointmentEntity();
        appointment.setUserId("U1");
        service.sendAppointmentNotification(appointment, "Request", "h", "d", "sh", "sd");
        verify(outbox).appointmentNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deletesOnlyYourOwnNotification() {
        NotificationEntity mine = new NotificationEntity("U1", "S1", "BOOKING", "t", "m");
        when(notificationRepo.findByIdAndUserId(7L, "U1")).thenReturn(Optional.of(mine));

        assertEquals("200", service.deleteNotification("U1", 7L).getStatusCode());
        verify(notificationRepo).delete(mine);

        BaseResponse other = service.deleteNotification("U2", 7L);
        assertEquals("400", other.getStatusCode());
        verify(notificationRepo, times(1)).delete(any(NotificationEntity.class));
    }

    @Test
    void deleteAllClearsOnlyYourInbox() {
        List<NotificationEntity> mine = List.of(new NotificationEntity("U1", "S1", "BOOKING", "t", "m"));
        when(notificationRepo.findByUserIdOrderByCreatedAtDesc("U1")).thenReturn(mine);

        assertEquals("200", service.deleteAllNotifications("U1").getStatusCode());
        verify(notificationRepo).deleteAll(mine);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminAnnouncementReachesEveryActiveCustomer() {
        when(userRepo.findActiveUserIds()).thenReturn(List.of("U1", "U2", "U3"));

        BaseResponse response = service.sendAnnouncement("A1", " Holiday hours ", "We are closed Monday.", null);

        assertEquals("200", response.getStatusCode());
        assertEquals(3, ((java.util.Map<String, Object>) response.getData()).get("sent"));
        ArgumentCaptor<List<NotificationEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(notificationRepo).saveAll(saved.capture());
        assertEquals(3, saved.getValue().size());
        assertEquals("ANNOUNCEMENT", saved.getValue().get(0).getType());
        assertEquals("Holiday hours", saved.getValue().get(0).getTitle());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminAnnouncementCanTargetOneCustomerByEmail() {
        UserEntity user = new UserEntity();
        user.setUserId("U9");
        when(userRepo.findByEmailAddress("ade@example.com")).thenReturn(Optional.of(user));

        assertEquals("200", service.sendAnnouncement("A1", "Hi", "Your refund is on its way.", "ade@example.com").getStatusCode());
        ArgumentCaptor<List<NotificationEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(notificationRepo).saveAll(saved.capture());
        assertEquals("U9", saved.getValue().get(0).getUserId());
        verify(userRepo, never()).findActiveUserIds();
    }

    @Test
    void adminAnnouncementNeedsATitleAMessageAndAKnownEmail() {
        assertEquals("400", service.sendAnnouncement("A1", "", "x", null).getStatusCode());
        assertEquals("400", service.sendAnnouncement("A1", "t", " ", null).getStatusCode());
        assertEquals("400", service.sendAnnouncement("A1", "t", "m", "nobody@example.com").getStatusCode());
        verify(notificationRepo, never()).saveAll(any());
    }
}

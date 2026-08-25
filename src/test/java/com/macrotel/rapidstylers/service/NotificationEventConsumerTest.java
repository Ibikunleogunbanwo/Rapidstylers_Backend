package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.outbox.NotificationEventConsumer;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEventConsumerTest {

    private EmailConfig emailConfig;
    private UserRepo userRepo;
    private StylerRepo stylerRepo;
    private SubServiceRepo subServiceRepo;
    private NotificationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        emailConfig = mock(EmailConfig.class);
        userRepo = mock(UserRepo.class);
        stylerRepo = mock(StylerRepo.class);
        subServiceRepo = mock(SubServiceRepo.class);
        consumer = new NotificationEventConsumer(emailConfig, userRepo, stylerRepo, subServiceRepo);
    }

    @Test
    void bookingNotificationSendsRoleSpecificEmailsAndAcknowledges() {
        UserEntity user = new UserEntity();
        user.setUserId("USER1");
        user.setFirstname("Ada");
        user.setLastname("Client");
        user.setEmailAddress("customer@example.com");
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        styler.setFirstname("Bea");
        styler.setLastname("Stylist");
        styler.setBusinessName("Bea Beauty");
        styler.setEmailAddress("styler@example.com");
        SubServiceEntity service = new SubServiceEntity();
        service.setName("Braids");
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(user));
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleBookingNotification(payload(), "1", "event-1", "BOOKING_REQUESTED", ack);

        verify(emailConfig).sendSimpleMail(eq("customer@example.com"), contains("Appointment Request"), contains("Booking request received"));
        // The canonical 24-hour wire value (09:30) renders as a friendly 12-hour clock time.
        verify(emailConfig).sendSimpleMail(eq("customer@example.com"), contains("Appointment Request"), contains("Arrival time:</strong> 9:30 AM"));
        verify(emailConfig).sendSimpleMail(eq("styler@example.com"), contains("Appointment Request"), contains("New booking request"));
        verify(ack).acknowledge();
    }

    @Test
    void paymentEventSendsReceiptEmailsToBothPartiesAndAcknowledges() {
        UserEntity user = new UserEntity();
        user.setUserId("USER1");
        user.setEmailAddress("customer@example.com");
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        styler.setEmailAddress("styler@example.com");
        SubServiceEntity service = new SubServiceEntity();
        service.setName("Braids");
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(user));
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleBookingNotification(paymentPayload(), "1", "event-2", "PAYMENT_SUCCEEDED", ack);

        verify(emailConfig).sendSimpleMail(eq("customer@example.com"), contains("Payment receipt"), contains("Total paid: $125.00"));
        verify(emailConfig).sendSimpleMail(eq("styler@example.com"), contains("Payment receipt"), contains("Total paid: $125.00"));
        verify(ack).acknowledge();
    }

    private String payload() {
        return "{"
                + "\"appointmentId\":\"APPT1\","
                + "\"eventLabel\":\"Request\","
                + "\"customerId\":\"USER1\","
                + "\"stylerId\":\"STYLER1\","
                + "\"subServiceId\":\"1\","
                + "\"appointmentDate\":\"2030-08-24\","
                + "\"arrivalTime\":\"09:30\","
                + "\"servicePrice\":\"100.00\","
                + "\"travelFee\":\"25.00\","
                + "\"totalPrice\":\"125.00\","
                + "\"customerHeadline\":\"Booking request received\","
                + "\"customerDetail\":\"Your request is waiting for confirmation.\","
                + "\"stylerHeadline\":\"New booking request\","
                + "\"stylerDetail\":\"A client requested an appointment.\""
                + "}";
    }

    private String paymentPayload() {
        return "{"
                + "\"appointmentId\":\"APPT2\","
                + "\"eventLabel\":\"Paid\","
                + "\"customerId\":\"USER1\","
                + "\"stylerId\":\"STYLER1\","
                + "\"subServiceId\":\"1\","
                + "\"appointmentDate\":\"2030-08-24\","
                + "\"arrivalTime\":\"09:30\","
                + "\"servicePrice\":\"100.00\","
                + "\"travelFee\":\"25.00\","
                + "\"totalPrice\":\"125.00\","
                + "\"paymentAmount\":\"125.00\","
                + "\"paymentStatus\":\"CAPTURED\","
                + "\"customerHeadline\":\"Payment received\","
                + "\"customerDetail\":\"Your payment has been received. Thank you!\","
                + "\"stylerHeadline\":\"Payment received\","
                + "\"stylerDetail\":\"The client's payment has been received.\""
                + "}";
    }
}

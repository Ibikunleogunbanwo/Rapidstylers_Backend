package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.service.NotificationDedupService;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.SubServiceEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.outbox.NotificationEventConsumer;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Optional;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationEventConsumerTest {

    private EmailConfig emailConfig;
    private UserRepo userRepo;
    private StylerRepo stylerRepo;
    private SubServiceRepo subServiceRepo;
    private NotificationDedupService dedupService;
    private KafkaTemplate<String, String> kafkaTemplate;
    private NotificationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        emailConfig = mock(EmailConfig.class);
        userRepo = mock(UserRepo.class);
        stylerRepo = mock(StylerRepo.class);
        subServiceRepo = mock(SubServiceRepo.class);
        dedupService = mock(NotificationDedupService.class);
        when(dedupService.tryClaim(any())).thenReturn(true);
        kafkaTemplate = mock(KafkaTemplate.class);
        consumer = new NotificationEventConsumer(emailConfig, userRepo, stylerRepo, subServiceRepo, kafkaTemplate, dedupService);
        org.springframework.test.util.ReflectionTestUtils.setField(consumer, "retryTopic", "rapidstylers.domain-events.retry");
        org.springframework.test.util.ReflectionTestUtils.setField(consumer, "dlqTopic", "rapidstylers.domain-events.dlq");
        org.springframework.test.util.ReflectionTestUtils.setField(consumer, "maxRetries", 3);
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

        consumer.handleBookingNotification(payload(), "1", "event-1", "BOOKING_REQUESTED", null, ack);

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

        consumer.handleBookingNotification(paymentPayload(), "1", "event-2", "PAYMENT_SUCCEEDED", null, ack);

        verify(emailConfig).sendSimpleMail(eq("customer@example.com"), contains("Payment receipt"), contains("Total paid: $125.00"));
        verify(emailConfig).sendSimpleMail(eq("styler@example.com"), contains("Payment receipt"), contains("Total paid: $125.00"));
        verify(ack).acknowledge();
    }

    @Test
    void redeliveredEventIsSkippedWithoutSendingEmails() {
        UserEntity user = new UserEntity();
        user.setUserId("USER1");
        user.setEmailAddress("customer@example.com");
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        styler.setEmailAddress("styler@example.com");
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(user));
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        when(dedupService.tryClaim("event-1")).thenReturn(false); // already delivered
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleBookingNotification(payload(), "1", "event-1", "BOOKING_REQUESTED", null, ack);

        verify(emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
        verify(ack).acknowledge();
    }

    @Test
    void failedDeliveryReleasesClaimAndRoutesToRetryTopic() {
        UserEntity user = new UserEntity();
        user.setUserId("USER1");
        user.setEmailAddress("customer@example.com");
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        styler.setEmailAddress("styler@example.com");
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(user));
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        doThrow(new RuntimeException("email provider down")).when(emailConfig).sendSimpleMail(anyString(), anyString(), anyString());
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleBookingNotification(payload(), "1", "event-1", "BOOKING_REQUESTED", "0", ack);

        verify(dedupService).release("event-1");
        verify(kafkaTemplate).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
        verify(ack).acknowledge();
    }

    @Test
    void exhaustedRetriesRouteToDlqWithErrorMessageHeader() {
        UserEntity user = new UserEntity();
        user.setUserId("USER1");
        user.setEmailAddress("customer@example.com");
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        styler.setEmailAddress("styler@example.com");
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(user));
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        // Email provider keeps failing: exhausted retries (retry-count == maxRetries)
        // must go to the DLQ, not loop the retry topic forever.
        doThrow(new RuntimeException("email provider down")).when(emailConfig).sendSimpleMail(anyString(), anyString(), anyString());
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleBookingNotification(payload(), "1", "event-1", "BOOKING_REQUESTED", "3", ack);

        verify(dedupService).release("event-1");
        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());
        ProducerRecord<String, String> sent = recordCaptor.getValue();
        assertEquals("rapidstylers.domain-events.dlq", sent.topic(),
                "after max retries the event must land on the DLQ, not the retry topic");
        assertTrue(sent.headers().lastHeader("error-message") != null,
                "DLQ record must carry the error-message header for ops triage");
        verify(ack).acknowledge();
    }

    @Test
    void refundEventSendsRefundNoticeEmailsToBothPartiesAndAcknowledges() {
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

        consumer.handleBookingNotification(refundPayload(), "1", "event-3", "REFUND_COMPLETED", null, ack);

        verify(emailConfig).sendSimpleMail(eq("customer@example.com"), contains("Refund notice"), contains("Refund amount:</strong> $125.00"));
        verify(emailConfig).sendSimpleMail(eq("styler@example.com"), contains("Refund notice"), contains("Refund amount:</strong> $125.00"));
        verify(ack).acknowledge();
    }

    private String refundPayload() {
        return "{"
                + "\"appointmentId\":\"APPT3\","
                + "\"eventLabel\":\"Refunded\","
                + "\"customerId\":\"USER1\","
                + "\"stylerId\":\"STYLER1\","
                + "\"subServiceId\":\"1\","
                + "\"appointmentDate\":\"2030-08-24\","
                + "\"arrivalTime\":\"09:30\","
                + "\"servicePrice\":\"100.00\","
                + "\"travelFee\":\"25.00\","
                + "\"totalPrice\":\"125.00\","
                + "\"refundAmount\":\"125.00\","
                + "\"refundReason\":\"Client request\","
                + "\"customerHeadline\":\"Refund issued\","
                + "\"customerDetail\":\"A refund has been issued to your payment method.\","
                + "\"stylerHeadline\":\"Refund issued\","
                + "\"stylerDetail\":\"A refund has been issued for this appointment.\""
                + "}";
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

package com.macrotel.rapidstylers.outbox;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.util.Optional;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        consumer = new NotificationEventConsumer(emailConfig, new CustomerRecords(userRepo),
                new StylerRecords(stylerRepo, subServiceRepo), kafkaTemplate, dedupService);
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
    void bookingEmailsNameTheStylistsTimeZone() {
        // An appointment's times are written on the stylist's clock. A customer
        // in another province reading a bare "9:30 AM" cannot tell whose 9:30
        // that is, so both parties are told which clock it is.
        stylerWith(null, "America/Edmonton");
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleBookingNotification(payload(), "1", "event-zone", "BOOKING_REQUESTED", null, ack);

        verify(emailConfig).sendSimpleMail(eq("customer@example.com"), contains("Appointment Request"),
                contains("Arrival time:</strong> 9:30 AM (Mountain Time)"));
        verify(emailConfig).sendSimpleMail(eq("styler@example.com"), contains("Appointment Request"),
                contains("Arrival time:</strong> 9:30 AM (Mountain Time)"));
        verify(ack).acknowledge();
    }

    @Test
    void aStylistWithoutAStoredZoneIsLabelledFromTheirProvince() {
        stylerWith("Ontario", null);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleBookingNotification(payload(), "1", "event-province", "BOOKING_REQUESTED", null, ack);

        verify(emailConfig).sendSimpleMail(eq("customer@example.com"), contains("Appointment Request"),
                contains("Arrival time:</strong> 9:30 AM (Eastern Time)"));
        verify(ack).acknowledge();
    }

    @Test
    void aStylistWithNoClockDataGetsABareTimeRatherThanAGuess() {
        // No stored zone and no province: the clock is unknown. Printing
        // "Mountain Time" here would state something the row never declared.
        stylerWith(null, null);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleBookingNotification(payload(), "1", "event-noclock", "BOOKING_REQUESTED", null, ack);

        verify(emailConfig).sendSimpleMail(eq("customer@example.com"), contains("Appointment Request"),
                contains("Arrival time:</strong> 9:30 AM</p>"));
        verify(ack).acknowledge();
    }

    @Test
    void paymentReceiptsNameTheZoneToo() {
        stylerWith("America/Edmonton", null);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.handleBookingNotification(paymentPayload(), "1", "event-paid-zone", "PAYMENT_SUCCEEDED", null, ack);

        verify(emailConfig).sendSimpleMail(eq("customer@example.com"), contains("Payment receipt"),
                contains("Arrival time:</strong> 9:30 AM (Mountain Time)"));
        verify(ack).acknowledge();
    }

    /** The shared parties for a notification event, with the clock the stylist declares. */
    private void stylerWith(String province, String timeZone) {
        UserEntity user = new UserEntity();
        user.setUserId("USER1");
        user.setEmailAddress("customer@example.com");
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        styler.setEmailAddress("styler@example.com");
        styler.setProvince(province);
        styler.setTimeZone(timeZone);
        SubServiceEntity service = new SubServiceEntity();
        service.setName("Braids");
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(user));
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
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

    @Test
    void publishedBookingTemplateUsesRealRecipientAndEscapedAppointmentValues() {
        stylerWith("Ontario", null);
        var templates = new com.macrotel.rapidstylers.catalog.service.SiteContentService();
        var repo = mock(com.macrotel.rapidstylers.catalog.repo.SiteContentRepo.class);
        org.springframework.test.util.ReflectionTestUtils.setField(templates, "contentRepo", repo);
        var content = new com.macrotel.rapidstylers.catalog.entity.SiteContentEntity();
        content.setPublishedBody("{\"subject\":\"Received {{serviceName}}\",\"body\":\"Hello {{firstName}}: {{serviceName}} at {{appointmentTime}}\"}");
        when(repo.findByContentKey("email:booking_received")).thenReturn(Optional.of(content));
        org.springframework.test.util.ReflectionTestUtils.setField(consumer, "siteContentService", templates);
        SubServiceEntity service = new SubServiceEntity();
        service.setName("<script>alert(1)</script>");
        when(subServiceRepo.isServiceExistById("STYLER1", 1L)).thenReturn(Optional.of(service));
        consumer.handleBookingNotification(payload(), "1", "template-booking", "BOOKING_REQUESTED", null, mock(Acknowledgment.class));
        verify(emailConfig).sendSimpleMail(eq("customer@example.com"), eq("Received <script>alert(1)</script>"), contains("&lt;script&gt;alert(1)&lt;/script&gt; at 9:30 AM (Eastern Time)"));
        verify(emailConfig).sendSimpleMail(eq("styler@example.com"), contains("Appointment Request"), contains("New booking request"));
    }

    @Test
    void templateLookupFailureKeepsBuiltInBookingAndKafkaAcknowledgement() {
        stylerWith(null, null);
        var templates = mock(com.macrotel.rapidstylers.catalog.service.SiteContentService.class);
        when(templates.renderEmail(anyString(), any())).thenThrow(new IllegalStateException("corrupt content"));
        org.springframework.test.util.ReflectionTestUtils.setField(consumer, "siteContentService", templates);
        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.handleBookingNotification(payload(), "1", "bad-template", "BOOKING_REQUESTED", null, ack);
        verify(emailConfig).sendSimpleMail(eq("customer@example.com"), contains("Appointment Request"), contains("Booking request received"));
        verify(ack).acknowledge();
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void stylistWelcomeEventUsesPublishedTemplateAndAcknowledges() {
        var templates = mock(com.macrotel.rapidstylers.catalog.service.SiteContentService.class);
        when(templates.renderEmail(eq("welcome_stylist"), eq(java.util.Map.of("firstName", "Bea"))))
                .thenReturn(Optional.of(new com.macrotel.rapidstylers.catalog.service.SiteContentRules.RenderedEmail("Hello Bea", "<p>Welcome stylist</p>")));
        org.springframework.test.util.ReflectionTestUtils.setField(consumer, "siteContentService", templates);
        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.handleBookingNotification("{\"emailAddress\":\"stylist@example.test\",\"firstname\":\"Bea\"}", "1", "stylist-welcome", "STYLER_WELCOME", null, ack);
        verify(emailConfig).sendSimpleMail("stylist@example.test", "Hello Bea", "<p>Welcome stylist</p>");
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

    @org.junit.jupiter.api.Test
    void aQuoteEmailEscapesThePeoplesOwnWordsAndPointsToTheRightPage() {
        String body = NotificationEventConsumer.quoteBody(java.util.Map.of(
                "headline", "New quote request",
                "detail", "<script>alert(1)</script> braids",
                "audience", "STYLIST"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("&lt;script&gt;"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("under Quotes"));
        org.junit.jupiter.api.Assertions.assertEquals("RapidStylers - Quote update", NotificationEventConsumer.quoteSubject(" "));
    }
}

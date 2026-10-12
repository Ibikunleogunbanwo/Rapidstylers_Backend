package com.macrotel.rapidstylers.outbox;

import com.macrotel.rapidstylers.catalog.service.SiteContentRules;
import com.macrotel.rapidstylers.catalog.service.SiteContentService;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The BOOKING_REMINDER event becomes one email to the customer, never to the stylist. */
@SuppressWarnings("unchecked")
class BookingReminderMailTest {

    private EmailConfig emailConfig;
    private UserRepo userRepo;
    private NotificationEventConsumer consumer;
    private UserEntity customer;

    @BeforeEach
    void setUp() {
        emailConfig = mock(EmailConfig.class);
        userRepo = mock(UserRepo.class);
        StylerRepo stylerRepo = mock(StylerRepo.class);
        SubServiceRepo subServiceRepo = mock(SubServiceRepo.class);
        NotificationDedupService dedup = mock(NotificationDedupService.class);
        when(dedup.tryClaim(any())).thenReturn(true);
        consumer = new NotificationEventConsumer(emailConfig, new CustomerRecords(userRepo),
                new StylerRecords(stylerRepo, subServiceRepo), mock(KafkaTemplate.class), dedup);
        ReflectionTestUtils.setField(consumer, "maxRetries", 3);
        customer = new UserEntity();
        customer.setUserId("USER1");
        customer.setFirstname("Ada");
        customer.setEmailAddress("ada@example.test");
        customer.setStatus("0");
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.of(customer));
        StylerEntity styler = new StylerEntity();
        styler.setStylerId("STYLER1");
        styler.setFirstname("Bea");
        styler.setBusinessName("Bea's <Braids>");
        styler.setEmailAddress("bea@example.test");
        styler.setTimeZone("America/Edmonton");
        styler.setProvince("Alberta");
        when(stylerRepo.findByStylerId("STYLER1")).thenReturn(Optional.of(styler));
        SubServiceEntity service = new SubServiceEntity();
        service.setName("Knotless braids");
        when(subServiceRepo.isServiceExistById("STYLER1", 7L)).thenReturn(Optional.of(service));
    }

    @Test
    void builtInReminderGoesToTheCustomerOnlyWithEverythingTheyNeed() {
        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.handleBookingNotification(payload("SALON", "200 Queen St, Calgary, AB"), "1", "rem-1",
                "BOOKING_REMINDER", null, ack);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailConfig).sendSimpleMail(eq("ada@example.test"), subject.capture(), body.capture());
        verify(emailConfig, never()).sendSimpleMail(eq("bea@example.test"), anyString(), anyString());
        verify(ack).acknowledge();
        assertEquals("Reminder: Knotless braids with Bea's <Braids> on Monday, November 2, 2026", subject.getValue());
        String html = body.getValue();
        assertTrue(html.contains("Hi Ada"), html);
        assertTrue(html.contains("Knotless braids"), html);
        assertTrue(html.contains("Bea&#39;s &lt;Braids&gt;"), html);
        assertTrue(html.contains("Monday, November 2, 2026"), html);
        assertTrue(html.contains("10:00 AM (Mountain Time)"), html);
        assertTrue(html.contains("Where:</strong> 200 Queen St, Calgary, AB"), html);
        assertTrue(html.contains("href=\"https://rapidstylers.ca/dashboard\""), html);
    }

    @Test
    void homeVisitNamesTheCustomersAddress() {
        consumer.handleBookingNotification(payload("HOME", "12 Elm St, Calgary"), "1", "rem-2",
                "BOOKING_REMINDER", null, mock(Acknowledgment.class));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailConfig).sendSimpleMail(eq("ada@example.test"), anyString(), body.capture());
        assertTrue(body.getValue().contains("Your stylist will come to:</strong> 12 Elm St, Calgary"), body.getValue());
    }

    @Test
    void publishedTemplateIsUsedAndTheMissingPlaceAndLinkAreAdded() {
        SiteContentService templates = mock(SiteContentService.class);
        Map<String, Object> published = Map.of("subject", "See you soon, {{firstName}}",
                "body", "{{serviceName}} with {{stylistName}}\n\n{{appointmentDate}} at {{appointmentTime}}");
        when(templates.renderEmail(eq("booking_reminder"), anyMap()))
                .thenAnswer(inv -> Optional.of(SiteContentRules.render(published, inv.getArgument(1))));
        ReflectionTestUtils.setField(consumer, "siteContentService", templates);

        consumer.handleBookingNotification(payload("SALON", "200 Queen St, Calgary, AB"), "1", "rem-3",
                "BOOKING_REMINDER", null, mock(Acknowledgment.class));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailConfig).sendSimpleMail(eq("ada@example.test"), eq("See you soon, Ada"), body.capture());
        String html = body.getValue();
        assertTrue(html.startsWith("<p>Knotless braids with Bea&#39;s &lt;Braids&gt;</p>"), html);
        assertTrue(html.contains("Monday, November 2, 2026 at 10:00 AM (Mountain Time)"), html);
        assertTrue(html.contains("Where:</strong> 200 Queen St, Calgary, AB"), html);
        assertTrue(html.contains("View your bookings"), html);
    }

    @Test
    void templateThatAlreadyNamesThePlaceAndLinkIsNotRepeated() {
        SiteContentService templates = mock(SiteContentService.class);
        Map<String, Object> published = Map.of("subject", "Reminder",
                "body", "Where: {{location}}\n\nManage it: {{bookingsLink}}");
        when(templates.renderEmail(eq("booking_reminder"), anyMap()))
                .thenAnswer(inv -> Optional.of(SiteContentRules.render(published, inv.getArgument(1))));
        ReflectionTestUtils.setField(consumer, "siteContentService", templates);

        consumer.handleBookingNotification(payload("SALON", "200 Queen St, Calgary, AB"), "1", "rem-4",
                "BOOKING_REMINDER", null, mock(Acknowledgment.class));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailConfig).sendSimpleMail(eq("ada@example.test"), eq("Reminder"), body.capture());
        assertEquals("<p>Where: 200 Queen St, Calgary, AB</p><p>Manage it: https://rapidstylers.ca/dashboard</p>",
                body.getValue());
    }

    @Test
    void suspendedOrMissingCustomerGetsNothingButTheEventIsAcknowledged() {
        customer.setStatus("SUSPENDED");
        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.handleBookingNotification(payload("SALON", "x"), "1", "rem-5", "BOOKING_REMINDER", null, ack);
        when(userRepo.findByUserId("USER1")).thenReturn(Optional.empty());
        consumer.handleBookingNotification(payload("SALON", "x"), "1", "rem-6", "BOOKING_REMINDER", null, ack);
        verify(emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void reminderTemplateOffersThePlaceAndLinkPlaceholders() {
        assertTrue(SiteContentRules.EMAIL_TEMPLATES.get("booking_reminder").containsAll(
                java.util.List.of("firstName", "serviceName", "stylistName", "appointmentDate", "appointmentTime",
                        "location", "bookingsLink")));
        assertFalse(SiteContentRules.EMAIL_TEMPLATES.get("booking_confirmed").contains("location"));
    }

    private static String payload(String visitType, String location) {
        return "{\"appointmentId\":\"APPT9\",\"eventLabel\":\"Reminder\",\"customerId\":\"USER1\","
                + "\"stylerId\":\"STYLER1\",\"subServiceId\":\"7\",\"appointmentDate\":\"2026-11-02\","
                + "\"arrivalTime\":\"10:00\",\"visitType\":\"" + visitType + "\",\"location\":\"" + location + "\","
                + "\"bookingsUrl\":\"https://rapidstylers.ca/dashboard\"}";
    }
}

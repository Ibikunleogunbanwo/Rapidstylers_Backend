package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.engagement.entity.NewsletterCampaignEntity;
import com.macrotel.rapidstylers.engagement.entity.NewsletterDeliveryEntity;
import com.macrotel.rapidstylers.engagement.entity.NewsletterSubscriberEntity;
import com.macrotel.rapidstylers.engagement.repo.EmailSuppressionRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterCampaignRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterDeliveryRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterSubscriberRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Every newsletter-related send checks the suppression list right before the provider is called. */
class NewsletterSuppressionTest {

    private static final String TOKEN = "b".repeat(64);
    private NewsletterMailer mailer;

    @BeforeEach
    void setUp() {
        mailer = new NewsletterMailer();
        mailer.deliveryRepo = mock(NewsletterDeliveryRepo.class);
        mailer.campaignRepo = mock(NewsletterCampaignRepo.class);
        mailer.subscriberRepo = mock(NewsletterSubscriberRepo.class);
        mailer.suppressionRepo = mock(EmailSuppressionRepo.class);
        mailer.emailConfig = mock(EmailConfig.class);
        NewsletterCampaignEntity campaign = new NewsletterCampaignEntity();
        campaign.setSubject("News");
        campaign.setBody("Hello");
        campaign.setStatus("SENDING");
        when(mailer.campaignRepo.findById(2L)).thenReturn(Optional.of(campaign));
        when(mailer.suppressionRepo.existsByEmail("blocked@example.test")).thenReturn(true);
    }

    @Test
    void aConfirmationQueuedBeforeTheAddressWasBlockedIsNotSent() {
        assertDoesNotThrow(() -> mailer.deliver("NEWSLETTER_CONFIRM",
                Map.of("emailAddress", "Blocked@Example.test ", "token", TOKEN)));
        verify(mailer.emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void aTestEmailToABlockedAddressIsNotSent() {
        assertDoesNotThrow(() -> mailer.deliver("NEWSLETTER_TEST",
                Map.of("emailAddress", "blocked@example.test", "campaignId", "2")));
        verify(mailer.emailConfig, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void unblockedConfirmationsAndTestsStillGoOut() {
        mailer.deliver("NEWSLETTER_CONFIRM", Map.of("emailAddress", "ok@example.test", "token", TOKEN));
        mailer.deliver("NEWSLETTER_TEST", Map.of("emailAddress", "ok@example.test", "campaignId", "2"));
        verify(mailer.emailConfig).sendSimpleMail(eq("ok@example.test"), eq("Confirm your RapidStylers subscription"), anyString());
        verify(mailer.emailConfig).sendSimpleMail(eq("ok@example.test"), eq("[Test] News"), anyString());
    }

    @Test
    void aQueuedCampaignEmailToABlockedAddressIsSkippedAndMarked() {
        NewsletterDeliveryEntity delivery = new NewsletterDeliveryEntity();
        delivery.setId(1L);
        delivery.setCampaignId(2L);
        delivery.setSubscriberId(3L);
        delivery.setEmail("blocked@example.test");
        delivery.setStatus("QUEUED");
        NewsletterSubscriberEntity subscriber = new NewsletterSubscriberEntity();
        subscriber.setStatus("SUBSCRIBED");
        subscriber.setUnsubscribeToken(TOKEN);
        when(mailer.deliveryRepo.findById(1L)).thenReturn(Optional.of(delivery));
        when(mailer.subscriberRepo.findById(3L)).thenReturn(Optional.of(subscriber));

        mailer.deliver("NEWSLETTER_EMAIL", Map.of("deliveryId", "1"));

        assertEquals("SKIPPED", delivery.getStatus());
        assertEquals("Address blocked", delivery.getError());
        verify(mailer.emailConfig, never()).sendNewsletterMail(anyString(), anyString(), anyString(), anyString());
        verify(mailer.deliveryRepo).save(delivery);
    }

    @Test
    void sendTestToABlockedAddressAnswersConflictAndQueuesNothing() {
        NewsletterCampaignService campaigns = new NewsletterCampaignService();
        campaigns.campaignRepo = mailer.campaignRepo;
        campaigns.suppressionRepo = mailer.suppressionRepo;
        campaigns.outboxEventService = mock(OutboxEventService.class);
        campaigns.auditService = mock(AuditService.class);

        BaseResponse response = campaigns.sendTest("admin-1", 2L, " BLOCKED@example.test");

        assertEquals(HttpStatus.CONFLICT, ApiResponses.httpStatus(response));
        assertTrue(response.getMessage().contains("blocked list"), response.getMessage());
        verifyNoInteractions(campaigns.outboxEventService, campaigns.auditService);

        BaseResponse allowed = campaigns.sendTest("admin-1", 2L, "ok@example.test");
        assertEquals(HttpStatus.OK, ApiResponses.httpStatus(allowed));
        verify(campaigns.outboxEventService).newsletterEmail(any(), eq("2"), anyMap());
    }
}

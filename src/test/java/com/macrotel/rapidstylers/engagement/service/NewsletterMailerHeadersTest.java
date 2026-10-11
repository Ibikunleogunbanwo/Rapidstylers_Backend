package com.macrotel.rapidstylers.engagement.service;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.engagement.entity.*;
import com.macrotel.rapidstylers.engagement.repo.*;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class NewsletterMailerHeadersTest {
    @Test void campaignSendsOneClickHeaderUrlAndKeepsHumanPreferenceLink() {
        NewsletterMailer mailer=new NewsletterMailer();
        mailer.deliveryRepo=mock(NewsletterDeliveryRepo.class);mailer.campaignRepo=mock(NewsletterCampaignRepo.class);mailer.subscriberRepo=mock(NewsletterSubscriberRepo.class);mailer.suppressionRepo=mock(EmailSuppressionRepo.class);mailer.emailConfig=mock(EmailConfig.class);
        NewsletterDeliveryEntity delivery=new NewsletterDeliveryEntity();delivery.setId(1L);delivery.setCampaignId(2L);delivery.setSubscriberId(3L);delivery.setEmail("recipient@example.test");delivery.setStatus("QUEUED");
        NewsletterCampaignEntity campaign=new NewsletterCampaignEntity();campaign.setSubject("News");campaign.setBody("Hello");campaign.setStatus("SENDING");
        NewsletterSubscriberEntity subscriber=new NewsletterSubscriberEntity();subscriber.setStatus("SUBSCRIBED");subscriber.setUnsubscribeToken("a".repeat(64));
        when(mailer.deliveryRepo.findById(1L)).thenReturn(Optional.of(delivery));when(mailer.campaignRepo.findById(2L)).thenReturn(Optional.of(campaign));when(mailer.subscriberRepo.findById(3L)).thenReturn(Optional.of(subscriber));when(mailer.emailConfig.sendNewsletterMail(anyString(),anyString(),anyString(),anyString())).thenReturn("Mail Sent Successfully...");
        mailer.deliver("NEWSLETTER_EMAIL",Map.of("deliveryId","1"));
        ArgumentCaptor<String> html=ArgumentCaptor.forClass(String.class);verify(mailer.emailConfig).sendNewsletterMail(eq("recipient@example.test"),eq("News"),html.capture(),eq("https://api.rapidstylers.ca/rapid_stylers/newsletter_one_click_unsubscribe?token="+"a".repeat(64)));
        assertTrue(html.getValue().contains("https://rapidstylers.ca/newsletter/unsubscribe?token="+"a".repeat(64)));assertEquals("SENT",delivery.getStatus());
        verify(mailer.emailConfig,never()).sendSimpleMail(anyString(),anyString(),anyString());
    }
}

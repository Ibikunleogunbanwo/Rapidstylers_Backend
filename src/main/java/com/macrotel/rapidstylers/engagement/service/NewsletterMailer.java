package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.catalog.service.SiteContentRules;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.engagement.entity.NewsletterCampaignEntity;
import com.macrotel.rapidstylers.engagement.entity.NewsletterDeliveryEntity;
import com.macrotel.rapidstylers.engagement.entity.NewsletterSubscriberEntity;
import com.macrotel.rapidstylers.engagement.repo.EmailSuppressionRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterCampaignRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterDeliveryRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterSubscriberRepo;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.engagement.service.NewsletterRules.*;

/**
 * The last step of every newsletter email, run by the mail-queue consumer.
 * It builds the email at the moment it goes out and checks once more that the
 * person still wants it, so an unsubscribe a second earlier still counts.
 */
@Service
public class NewsletterMailer {

    @Autowired NewsletterDeliveryRepo deliveryRepo;
    @Autowired NewsletterCampaignRepo campaignRepo;
    @Autowired NewsletterSubscriberRepo subscriberRepo;
    @Autowired EmailSuppressionRepo suppressionRepo;
    @Autowired EmailConfig emailConfig;

    /** Where links in the email point: the website's confirm and unsubscribe pages. */
    @Value("${app.newsletter.site-url:https://rapidstylers.ca}")
    String siteUrl = "https://rapidstylers.ca";

    @Transactional
    public void deliver(String eventType, Map<String, String> event) {
        if ("NEWSLETTER_CONFIRM".equals(eventType)) {
            confirmEmail(event.getOrDefault("emailAddress", ""), event.getOrDefault("token", ""));
        } else if ("NEWSLETTER_TEST".equals(eventType)) {
            testEmail(event.getOrDefault("emailAddress", ""), id(event.get("campaignId")));
        } else if ("NEWSLETTER_EMAIL".equals(eventType)) {
            Long id = id(event.get("deliveryId"));
            if (id != null) deliveryRepo.findById(id).ifPresent(this::campaignEmail);
        }
    }

    private void confirmEmail(String email, String token) {
        if (email.isBlank() || !token.matches("[0-9a-f]{64}")) return;
        String link = siteUrl + "/newsletter/confirm?token=" + token;
        emailConfig.sendSimpleMail(email, "Confirm your RapidStylers subscription",
                "<p>Please confirm you'd like RapidStylers news, offers and tips by email.</p>"
                + "<p><a href=\"" + link + "\">Yes, subscribe me</a></p>"
                + "<p>If you didn't ask for this, ignore this email and you won't hear from us.</p>"
                + "<p>– The RapidStylers Team</p>");
    }

    private void testEmail(String email, Long campaignId) {
        Optional<NewsletterCampaignEntity> c = campaignId == null ? Optional.empty() : campaignRepo.findById(campaignId);
        if (email.isBlank() || c.isEmpty()) return;
        SiteContentRules.RenderedEmail mail = render(c.get().getSubject(), c.get().getBody(), siteUrl + "/newsletter/unsubscribe");
        emailConfig.sendSimpleMail(email, "[Test] " + mail.subject, mail.html);
    }

    private void campaignEmail(NewsletterDeliveryEntity d) {
        if (!"QUEUED".equals(d.getStatus())) return; // already handled
        Optional<NewsletterCampaignEntity> c = campaignRepo.findById(d.getCampaignId());
        Optional<NewsletterSubscriberEntity> s = subscriberRepo.findById(d.getSubscriberId());
        String skip = null;
        if (c.isEmpty() || "CANCELLED".equals(c.get().getStatus())) skip = "Campaign cancelled";
        else if (s.isEmpty() || !SUBSCRIBED.equals(s.get().getStatus())) skip = "No longer subscribed";
        else if (suppressionRepo.existsByEmail(d.getEmail())) skip = "Address blocked";
        if (skip != null) {
            d.setStatus("SKIPPED");
            d.setError(skip);
        } else {
            SiteContentRules.RenderedEmail mail = render(c.get().getSubject(), c.get().getBody(),
                    siteUrl + "/newsletter/unsubscribe?token=" + s.get().getUnsubscribeToken());
            String result = emailConfig.sendSimpleMail(d.getEmail(), mail.subject, mail.html);
            boolean sent = result != null && result.startsWith("Mail Sent");
            d.setStatus(sent ? "SENT" : "FAILED");
            d.setError(sent ? null : String.valueOf(result).substring(0, Math.min(String.valueOf(result).length(), 500)));
            if (sent) d.setSentAt(now());
        }
        deliveryRepo.save(d);
    }

    private static Long id(String raw) {
        try {
            return raw == null ? null : Long.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

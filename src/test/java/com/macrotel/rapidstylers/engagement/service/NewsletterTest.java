package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.engagement.entity.NewsletterCampaignEntity;
import com.macrotel.rapidstylers.engagement.entity.NewsletterDeliveryEntity;
import com.macrotel.rapidstylers.engagement.entity.NewsletterSubscriberEntity;
import com.macrotel.rapidstylers.engagement.repo.EmailSuppressionRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterCampaignRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterDeliveryRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterSubscriberRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventEntity;
import com.macrotel.rapidstylers.outbox.OutboxEventRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventType;
import com.macrotel.rapidstylers.security.jwt.JwtUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The newsletter against the real database: double opt-in, unsubscribe links,
 * blocked addresses, audience targeting, paced sending and admin-only access.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NewsletterTest {

    @Autowired NewsletterSubscriptionService subscriptions;
    @Autowired NewsletterCampaignService campaigns;
    @Autowired NewsletterMailer mailer;
    @Autowired NewsletterSubscriberRepo subscriberRepo;
    @Autowired EmailSuppressionRepo suppressionRepo;
    @Autowired NewsletterCampaignRepo campaignRepo;
    @Autowired NewsletterDeliveryRepo deliveryRepo;
    @Autowired OutboxEventRepo outboxRepo;
    @Autowired RateLimiterService rateLimiter;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean EmailConfig emailConfig;
    @Value("${app.api.key}") String apiKey;

    private final String tag = Long.toString(System.nanoTime(), 36).toLowerCase();
    /** A made-up province so other tests' subscribers never fall inside these audiences. */
    private final String province = "Prov-" + tag;
    private final String admin = "ADM" + tag;
    private final List<String> emails = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(subscriptions, "rateLimiter", null);
        when(emailConfig.sendSimpleMail(anyString(), anyString(), anyString())).thenReturn("Mail Sent Successfully...");
        when(emailConfig.sendNewsletterMail(anyString(), anyString(), anyString(), anyString())).thenReturn("Mail Sent Successfully...");
    }

    @AfterEach
    void cleanUp() {
        ReflectionTestUtils.setField(subscriptions, "rateLimiter", rateLimiter);
        for (NewsletterCampaignEntity c : campaignRepo.findAll()) {
            if (admin.equals(c.getCreatedBy())) {
                deliveryRepo.deleteAll(deliveryRepo.findAll().stream().filter(d -> d.getCampaignId().equals(c.getId())).toList());
                campaignRepo.delete(c);
            }
        }
        for (String e : emails) {
            subscriberRepo.findByEmail(e).ifPresent(subscriberRepo::delete);
            suppressionRepo.findByEmail(e).ifPresent(suppressionRepo::delete);
        }
    }

    private String email(String name) {
        String e = name + "-" + tag + "@example.com";
        emails.add(e);
        return e;
    }

    @Test
    void aVisitorIsOnlySubscribedAfterClickingTheEmailedLink() {
        String e = email("guest");
        assertEquals("Please tick the box to agree to receive emails", subscriptions.subscribe(e, province, false).getMessage());
        assertEquals("Enter a valid email address", subscriptions.subscribe("not-an-email", null, true).getMessage());

        assertEquals("200", subscriptions.subscribe(e.toUpperCase(), province, true).getStatusCode());
        NewsletterSubscriberEntity s = subscriberRepo.findByEmail(e).orElseThrow();
        assertEquals("PENDING", s.getStatus(), "not on the list until confirmed");
        assertEquals(NewsletterRules.CONSENT_TEXT, s.getConsentText());
        OutboxEventEntity confirm = outbox(OutboxEventType.NEWSLETTER_CONFIRM, String.valueOf(s.getId()));
        assertTrue(confirm.getPayload().contains(s.getConfirmToken()));
        assertTrue(campaigns.recipients("ALL", province).isEmpty());

        mailer.deliver("NEWSLETTER_CONFIRM", Map.of("emailAddress", e, "token", s.getConfirmToken()));
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailConfig).sendSimpleMail(eq(e), anyString(), html.capture());
        assertTrue(html.getValue().contains("/newsletter/confirm?token=" + s.getConfirmToken()));

        assertEquals("This link is invalid or was already used", subscriptions.confirm("nope").getMessage());
        assertEquals("200", subscriptions.confirm(s.getConfirmToken()).getStatusCode());
        assertEquals("This link is invalid or was already used", subscriptions.confirm(s.getConfirmToken()).getMessage());
        assertEquals(1, campaigns.recipients("ALL", province).size());
        assertEquals(1, campaigns.recipients(NewsletterRules.GUEST, province.toUpperCase()).size(), "province match ignores case");
        assertTrue(campaigns.recipients("CUSTOMER", province).isEmpty());

        // Asking again changes nothing and says the same thing.
        long before = outboxRepo.count();
        assertEquals("200", subscriptions.subscribe(e, province, true).getStatusCode());
        assertEquals(before, outboxRepo.count(), "an existing subscriber gets no new email");

        String unsub = subscriberRepo.findByEmail(e).orElseThrow().getUnsubscribeToken();
        @SuppressWarnings("unchecked")
        Map<String, Object> prefs = (Map<String, Object>) subscriptions.preferences(unsub).getData();
        assertEquals("gu***@example.com", prefs.get("email"));
        assertEquals("200", subscriptions.unsubscribe(unsub).getStatusCode());
        assertEquals("200", subscriptions.unsubscribe(unsub).getStatusCode(), "a second click still works");
        assertTrue(campaigns.recipients("ALL", province).isEmpty());
    }

    @Test
    void anOldConfirmLinkExpires() {
        String e = email("late");
        subscriptions.subscribe(e, province, true);
        NewsletterSubscriberEntity s = subscriberRepo.findByEmail(e).orElseThrow();
        s.setConfirmSentAt(NewsletterRules.now().minusDays(8));
        subscriberRepo.save(s);
        assertEquals("This link has expired. Please subscribe again.", subscriptions.confirm(s.getConfirmToken()).getMessage());
    }

    @Test
    void theFormIsRateLimitedPerAddress() {
        ReflectionTestUtils.setField(subscriptions, "rateLimiter", rateLimiter);
        String e = email("busy");
        rateLimiter.clear("newsletter:ip:" + rateLimiter.clientIp());
        for (int i = 0; i < 3; i++) assertEquals("200", subscriptions.subscribe(e, null, true).getStatusCode());
        assertEquals("429", subscriptions.subscribe(e, null, true).getStatusCode());
        rateLimiter.clear("newsletter:email:" + e);
        rateLimiter.clear("newsletter:ip:" + rateLimiter.clientIp());
    }

    @Test
    void campaignsArePacedSkipTheBlockedAndFinish() {
        List<String> people = List.of(email("a"), email("b"), email("c"), email("d"));
        for (String p : people) subscribed(p);
        String blocked = email("blocked");
        subscribed(blocked);
        assertEquals("200", subscriptions.addSuppression(admin, blocked, "bounced").getStatusCode());
        assertEquals("200", subscriptions.subscribe(blocked, province, true).getStatusCode(), "same answer for blocked");

        assertEquals("Placeholders like {{name}} aren't supported in newsletters",
                campaigns.save(admin, null, "Fall", "Hello", "Hi {{firstName}}", "ALL", List.of(province)).getMessage());
        assertEquals("400", campaigns.save(admin, null, "Fall", "Hello", "Body", "EVERYONE", List.of()).getStatusCode());
        @SuppressWarnings("unchecked")
        Long id = ((Number) ((Map<String, Object>) campaigns.save(admin, null, "Fall <b>news</b>", "Fall\r\nnews",
                "Hello <script>x</script> & welcome.\n\nSee you soon", "GUEST", List.of(province)).getData()).get("id")).longValue();
        NewsletterCampaignEntity saved = campaignRepo.findById(id).orElseThrow();
        assertEquals("Fall news", saved.getSubject(), "no line breaks in a subject");
        @SuppressWarnings("unchecked")
        Map<String, Object> count = (Map<String, Object>) campaigns.audience("GUEST", List.of(province)).getData();
        assertEquals(4, count.get("count"), "the blocked address is not counted");

        assertEquals("200", campaigns.schedule(admin, id, "2020-01-01T00:00:00Z").getStatusCode(), "a past time means now");
        assertEquals("Only drafts can be edited",
                campaigns.save(admin, id, "x", "x", "x", "ALL", List.of()).getMessage());

        ReflectionTestUtils.setField(campaigns, "perMinute", 3);
        try {
            campaigns.tick();
        } finally {
            ReflectionTestUtils.setField(campaigns, "perMinute", 50);
        }
        assertEquals("SENDING", campaignRepo.findById(id).orElseThrow().getStatus());
        assertEquals(4, deliveryRepo.countByCampaignId(id));
        List<NewsletterDeliveryEntity> queued = deliveryRepo.findByCampaignIdAndStatus(id, "QUEUED");
        assertTrue(queued.size() <= 3, "never more than the per-minute limit");

        // Someone unsubscribes after their email was queued: it is skipped.
        NewsletterDeliveryEntity first = queued.get(0);
        subscriptions.unsubscribe(subscriberRepo.findByEmail(first.getEmail()).orElseThrow().getUnsubscribeToken());
        for (int round = 0; round < 5 && !"SENT".equals(campaignRepo.findById(id).orElseThrow().getStatus()); round++) {
            for (NewsletterDeliveryEntity d : deliveryRepo.findByCampaignIdAndStatus(id, "QUEUED")) {
                mailer.deliver("NEWSLETTER_EMAIL", Map.of("deliveryId", String.valueOf(d.getId())));
            }
            campaigns.tick();
        }
        assertEquals("SENT", campaignRepo.findById(id).orElseThrow().getStatus());
        assertEquals(3, deliveryRepo.countByCampaignIdAndStatus(id, "SENT"));
        assertEquals(1, deliveryRepo.countByCampaignIdAndStatus(id, "SKIPPED"));
        verify(emailConfig, never()).sendNewsletterMail(eq(blocked), anyString(), anyString(), anyString());
        verify(emailConfig, never()).sendNewsletterMail(eq(first.getEmail()), anyString(), anyString(), anyString());

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        String someone = people.stream().filter(p -> !p.equals(first.getEmail())).findFirst().orElseThrow();
        verify(emailConfig).sendNewsletterMail(eq(someone), eq("Fall news"), html.capture(), anyString());
        assertTrue(html.getValue().startsWith("<p>Hello &lt;script&gt;x&lt;/script&gt; &amp; welcome.</p><p>See you soon</p>"), html.getValue());
        assertTrue(html.getValue().contains("/newsletter/unsubscribe?token="
                + subscriberRepo.findByEmail(someone).orElseThrow().getUnsubscribeToken()));

        // A delivery handled twice sends once.
        NewsletterDeliveryEntity sent = deliveryRepo.findByCampaignIdAndStatus(id, "SENT").get(0);
        mailer.deliver("NEWSLETTER_EMAIL", Map.of("deliveryId", String.valueOf(sent.getId())));
        verify(emailConfig, times(1)).sendNewsletterMail(eq(sent.getEmail()), anyString(), anyString(), anyString());
    }

    @Test
    void cancellingAScheduledCampaignMakesItADraftAgain() {
        subscribed(email("x"));
        @SuppressWarnings("unchecked")
        Long id = ((Number) ((Map<String, Object>) campaigns.save(admin, null, "N", "S", "B", "ALL", List.of(province))
                .getData()).get("id")).longValue();
        assertEquals("Schedule within the next 90 days", campaigns.schedule(admin, id, "2099-01-01T00:00:00Z").getMessage());
        assertEquals("200", campaigns.schedule(admin, id, NewsletterRules.now().plusDays(2) + "Z").getStatusCode());
        assertEquals("SCHEDULED", campaignRepo.findById(id).orElseThrow().getStatus());
        assertEquals("200", campaigns.cancel(admin, id).getStatusCode());
        assertEquals("DRAFT", campaignRepo.findById(id).orElseThrow().getStatus());
    }

    @Test
    void onlyAdminsManageCampaignsAndAnyoneCanUseTheForm() throws Exception {
        String body = "{\"name\":\"N\",\"subject\":\"S\",\"body\":\"B\",\"audienceRole\":\"ALL\"}";
        mockMvc.perform(post("/rapid_stylers/admin/save_newsletter_campaign").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken("U" + tag, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/rapid_stylers/admin/save_newsletter_campaign").header("x-api-key", apiKey)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        String e = email("form");
        mockMvc.perform(post("/rapid_stylers/newsletter_subscribe").header("x-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + e + "\",\"consent\":true}"))
                .andExpect(status().isOk());
        assertEquals("PENDING", subscriberRepo.findByEmail(e).orElseThrow().getStatus());
    }

    private void subscribed(String e) {
        subscriptions.subscribe(e, province, true);
        subscriptions.confirm(subscriberRepo.findByEmail(e).orElseThrow().getConfirmToken());
    }

    private OutboxEventEntity outbox(OutboxEventType type, String aggregateId) {
        return outboxRepo.findAll().stream()
                .filter(o -> o.getEventType() == type && aggregateId.equals(o.getAggregateId()))
                .findFirst().orElseThrow();
    }
}

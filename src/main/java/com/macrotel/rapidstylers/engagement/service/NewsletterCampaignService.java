package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.engagement.entity.NewsletterCampaignEntity;
import com.macrotel.rapidstylers.engagement.entity.NewsletterDeliveryEntity;
import com.macrotel.rapidstylers.engagement.entity.NewsletterSubscriberEntity;
import com.macrotel.rapidstylers.engagement.repo.EmailSuppressionRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterCampaignRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterDeliveryRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterSubscriberRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.outbox.OutboxEventType;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.engagement.service.NewsletterRules.*;

/**
 * Newsletter campaigns: write, aim, schedule, send.
 *
 * <p>Picture a mail room. When a campaign's time comes, a clerk writes one
 * envelope per subscriber who matches the audience (a delivery row, PENDING).
 * Every minute the clerk hands at most {@code per-minute} envelopes to the
 * post (the mail queue), and never more than {@code per-day} in any 24 hours,
 * so the email provider never sees a sudden flood. Each envelope is checked
 * again just before it goes: someone who unsubscribed or was blocked in the
 * meantime is skipped. When no envelope is left, the campaign is SENT.
 */
@Service
public class NewsletterCampaignService {

    private static final Logger LOG = Logger.getLogger(NewsletterCampaignService.class.getName());

    static final String DRAFT = "DRAFT";
    static final String SCHEDULED = "SCHEDULED";
    static final String SENDING = "SENDING";
    static final String SENT = "SENT";
    static final String CANCELLED = "CANCELLED";

    private static final List<String> AUDIENCES = List.of("ALL", "CUSTOMER", "STYLER", GUEST);

    @Autowired NewsletterCampaignRepo campaignRepo;
    @Autowired NewsletterDeliveryRepo deliveryRepo;
    @Autowired NewsletterSubscriberRepo subscriberRepo;
    @Autowired EmailSuppressionRepo suppressionRepo;
    @Autowired OutboxEventService outboxEventService;
    @Autowired AuditService auditService;

    @Value("${app.newsletter.per-minute:50}")
    int perMinute = 50;

    @Value("${app.newsletter.per-day:2000}")
    int perDay = 2000;

    @Value("${app.demo.read-only:false}")
    boolean readOnly;

    // ── admin ───────────────────────────────────────────────────────────────

    public BaseResponse list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NewsletterCampaignEntity c : campaignRepo.findTop500ByOrderByCreatedAtDesc()) rows.add(view(c, false));
        return ok(null, rows);
    }

    public BaseResponse get(Long id) {
        return find(id).map(c -> ok(null, view(c, true))).orElseGet(() -> status("404", "Campaign not found"));
    }

    /** Creates a draft (no id) or edits one. Only drafts can be edited. */
    @Transactional
    public BaseResponse save(String adminId, Long id, String name, String subject, String body,
                             String audienceRole, List<String> provinces) {
        NewsletterCampaignEntity c;
        if (id == null) {
            c = new NewsletterCampaignEntity();
            c.setStatus(DRAFT);
            c.setCreatedBy(adminId);
            c.setCreatedAt(now());
        } else {
            Optional<NewsletterCampaignEntity> found = find(id);
            if (found.isEmpty()) return status("404", "Campaign not found");
            c = found.get();
            if (!DRAFT.equals(c.getStatus())) return error("Only drafts can be edited");
        }
        try {
            c.setName(text(name, "name", 120));
            c.setSubject(text(subject, "subject", 150).replaceAll("[\\r\\n]+", " "));
            c.setBody(body(body));
            c.setAudienceRole(audience(audienceRole));
            c.setProvinces(provinces(provinces));
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
        c.setUpdatedAt(now());
        campaignRepo.save(c);
        auditService.audit(adminId, "ADMIN", "ADMIN_NEWSLETTER_SAVED", "NEWSLETTER", String.valueOf(c.getId()), c.getName());
        return ok("Draft saved", view(c, true));
    }

    /** How many people an audience reaches right now. */
    public BaseResponse audience(String audienceRole, List<String> provinces) {
        try {
            int count = recipients(audience(audienceRole), provinces(provinces)).size();
            return ok(null, Map.of("count", count));
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    /** Schedules a draft. sendAt is an ISO time (UTC when it has no offset); empty or past means now. */
    @Transactional
    public BaseResponse schedule(String adminId, Long id, String sendAt) {
        Optional<NewsletterCampaignEntity> found = find(id);
        if (found.isEmpty()) return status("404", "Campaign not found");
        NewsletterCampaignEntity c = found.get();
        if (!DRAFT.equals(c.getStatus())) return error("Only drafts can be scheduled");
        LocalDateTime when;
        try {
            when = sendAt == null || sendAt.isBlank() ? now() : utc(sendAt.trim());
        } catch (Exception ex) {
            return error("sendAt must be a date and time like 2026-11-01T15:00:00Z");
        }
        if (when.isBefore(now())) when = now();
        when = when.truncatedTo(ChronoUnit.SECONDS); // the column keeps whole seconds and would round up
        if (when.isAfter(now().plusDays(90))) return error("Schedule within the next 90 days");
        if (recipients(c.getAudienceRole(), c.getProvinces()).isEmpty()) return error("No one matches this audience yet");
        c.setStatus(SCHEDULED);
        c.setScheduledAt(when);
        c.setUpdatedAt(now());
        campaignRepo.save(c);
        auditService.audit(adminId, "ADMIN", "ADMIN_NEWSLETTER_SCHEDULED", "NEWSLETTER", String.valueOf(id),
                "sendAt=" + when + "Z");
        return ok("Scheduled", view(c, true));
    }

    /** A scheduled campaign goes back to draft; one being sent stops and the rest are skipped. */
    @Transactional
    public BaseResponse cancel(String adminId, Long id) {
        Optional<NewsletterCampaignEntity> found = find(id);
        if (found.isEmpty()) return status("404", "Campaign not found");
        NewsletterCampaignEntity c = found.get();
        String message;
        if (SCHEDULED.equals(c.getStatus())) {
            c.setStatus(DRAFT);
            c.setScheduledAt(null);
            message = "Unscheduled. It's a draft again.";
        } else if (SENDING.equals(c.getStatus())) {
            c.setStatus(CANCELLED);
            c.setFinishedAt(now());
            for (NewsletterDeliveryEntity d : deliveryRepo.findByCampaignIdAndStatus(id, PENDING)) {
                d.setStatus("SKIPPED");
                d.setError("Campaign cancelled");
                deliveryRepo.save(d);
            }
            message = "Stopped. Emails not yet sent were skipped.";
        } else {
            return error("Only scheduled or sending campaigns can be cancelled");
        }
        c.setUpdatedAt(now());
        campaignRepo.save(c);
        auditService.audit(adminId, "ADMIN", "ADMIN_NEWSLETTER_CANCELLED", "NEWSLETTER", String.valueOf(id), message);
        return ok(message, view(c, true));
    }

    /**
     * Sends one copy to the given address, marked [Test], through the normal mail queue.
     * A blocked address is refused here (409); the mailer checks again at send time.
     */
    @Transactional
    public BaseResponse sendTest(String adminId, Long id, String rawEmail) {
        Optional<NewsletterCampaignEntity> found = find(id);
        if (found.isEmpty()) return status("404", "Campaign not found");
        String email = email(rawEmail);
        if (email == null) return error("Enter a valid email address");
        if (suppressionRepo.existsByEmail(email)) {
            return status("409", "Can't send a test to " + email + ": it's on the blocked list (it bounced, "
                    + "complained or was blocked by an admin). Unblock it first or use another address.");
        }
        outboxEventService.newsletterEmail(OutboxEventType.NEWSLETTER_TEST, String.valueOf(id),
                Map.of("emailAddress", email, "campaignId", String.valueOf(id)));
        auditService.audit(adminId, "ADMIN", "ADMIN_NEWSLETTER_TEST", "NEWSLETTER", String.valueOf(id), email);
        return ok("Test email on its way to " + email, null);
    }

    // ── sending ─────────────────────────────────────────────────────────────

    /** Every minute: start due campaigns, hand out the next envelopes, close finished ones. */
    @Scheduled(fixedDelayString = "${app.newsletter.tick-ms:60000}", initialDelayString = "${app.newsletter.tick-ms:60000}")
    @Transactional
    public void tick() {
        if (readOnly) return;
        try {
            for (NewsletterCampaignEntity c : campaignRepo.findByStatusAndScheduledAtLessThanEqual(SCHEDULED, now())) {
                start(c);
            }
            int budget = Math.min(perMinute, (int) Math.max(0, perDay - deliveryRepo.countByQueuedAtAfter(now().minusHours(24))));
            for (NewsletterCampaignEntity c : campaignRepo.findByStatusOrderByStartedAt(SENDING)) {
                if (budget > 0) budget -= queue(c, budget);
                if (deliveryRepo.countByCampaignIdAndStatus(c.getId(), PENDING) == 0
                        && deliveryRepo.countByCampaignIdAndStatus(c.getId(), "QUEUED") == 0) {
                    c.setStatus(SENT);
                    c.setFinishedAt(now());
                    c.setUpdatedAt(now());
                    campaignRepo.save(c);
                }
            }
        } catch (Exception ex) {
            LOG.warning("Newsletter tick failed: " + ex.getMessage());
        }
    }

    private void start(NewsletterCampaignEntity c) {
        for (NewsletterSubscriberEntity s : recipients(c.getAudienceRole(), c.getProvinces())) {
            NewsletterDeliveryEntity d = new NewsletterDeliveryEntity();
            d.setCampaignId(c.getId());
            d.setSubscriberId(s.getId());
            d.setEmail(s.getEmail());
            d.setStatus(PENDING);
            deliveryRepo.save(d);
        }
        c.setStatus(SENDING);
        c.setStartedAt(now());
        c.setUpdatedAt(now());
        campaignRepo.save(c);
    }

    private int queue(NewsletterCampaignEntity c, int max) {
        List<NewsletterDeliveryEntity> next = deliveryRepo.findByCampaignIdAndStatusOrderById(c.getId(), PENDING,
                PageRequest.of(0, max));
        for (NewsletterDeliveryEntity d : next) {
            d.setStatus("QUEUED");
            d.setQueuedAt(now());
            deliveryRepo.save(d);
            outboxEventService.newsletterEmail(OutboxEventType.NEWSLETTER_EMAIL, String.valueOf(d.getId()),
                    Map.of("deliveryId", String.valueOf(d.getId())));
        }
        return next.size();
    }

    /** Subscribed people matching the audience, minus blocked addresses. */
    List<NewsletterSubscriberEntity> recipients(String audienceRole, String provinces) {
        Set<String> wanted = provinces == null || provinces.isBlank() ? Set.of()
                : Arrays.stream(provinces.split(",")).map(p -> p.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return subscriberRepo.findByStatus(SUBSCRIBED).stream()
                .filter(s -> "ALL".equals(audienceRole) || audienceRole.equals(s.getRole()))
                .filter(s -> wanted.isEmpty()
                        || (s.getProvince() != null && wanted.contains(s.getProvince().trim().toLowerCase(Locale.ROOT))))
                .filter(s -> !suppressionRepo.existsByEmail(s.getEmail()))
                .collect(Collectors.toList());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Optional<NewsletterCampaignEntity> find(Long id) {
        return id == null ? Optional.empty() : campaignRepo.findById(id);
    }

    private Map<String, Object> view(NewsletterCampaignEntity c, boolean full) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", c.getId());
        v.put("name", c.getName());
        v.put("subject", c.getSubject());
        if (full) v.put("body", c.getBody());
        v.put("audienceRole", c.getAudienceRole());
        v.put("provinces", c.getProvinces() == null ? List.of() : List.of(c.getProvinces().split(",")));
        v.put("status", c.getStatus());
        v.put("scheduledAt", c.getScheduledAt());
        v.put("startedAt", c.getStartedAt());
        v.put("finishedAt", c.getFinishedAt());
        v.put("createdAt", c.getCreatedAt());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("total", deliveryRepo.countByCampaignId(c.getId()));
        for (String st : List.of(PENDING, "QUEUED", "SENT", "FAILED", "SKIPPED")) {
            counts.put(st.toLowerCase(Locale.ROOT), deliveryRepo.countByCampaignIdAndStatus(c.getId(), st));
        }
        v.put("deliveries", counts);
        if (full && (DRAFT.equals(c.getStatus()) || SCHEDULED.equals(c.getStatus()))) {
            v.put("audienceCount", recipients(c.getAudienceRole(), c.getProvinces()).size());
        }
        return v;
    }

    private static String text(String raw, String name, int max) {
        String value = raw == null ? null : AppUtils.sanitizeText(raw.trim());
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (value.length() > max) throw new IllegalArgumentException("Keep " + name + " under " + max + " characters");
        return value.trim();
    }

    private static String body(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("body is required");
        String text = raw.replace("\r\n", "\n").trim();
        if (text.length() > 50000) throw new IllegalArgumentException("Keep body under 50000 characters");
        if (text.contains("{{")) throw new IllegalArgumentException("Placeholders like {{name}} aren't supported in newsletters");
        return text;
    }

    private static String audience(String raw) {
        String a = raw == null || raw.isBlank() ? "ALL" : raw.trim().toUpperCase(Locale.ROOT);
        if (!AUDIENCES.contains(a)) throw new IllegalArgumentException("audienceRole must be one of " + AUDIENCES);
        return a;
    }

    private static String provinces(List<String> raw) {
        if (raw == null || raw.isEmpty()) return null;
        List<String> clean = raw.stream().map(NewsletterRules::province).filter(Objects::nonNull)
                .map(p -> p.replace(",", " ")).distinct().collect(Collectors.toList());
        if (clean.size() > 20) throw new IllegalArgumentException("Pick at most 20 provinces");
        String joined = String.join(",", clean);
        return joined.isEmpty() ? null : joined;
    }

    private static LocalDateTime utc(String raw) {
        try {
            return OffsetDateTime.parse(raw).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception ex) {
            return LocalDateTime.parse(raw);
        }
    }
}

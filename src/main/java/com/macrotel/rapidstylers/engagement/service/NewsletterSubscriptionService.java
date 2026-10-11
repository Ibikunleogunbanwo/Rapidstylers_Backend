package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.engagement.entity.EmailSuppressionEntity;
import com.macrotel.rapidstylers.engagement.entity.NewsletterSubscriberEntity;
import com.macrotel.rapidstylers.engagement.repo.EmailSuppressionRepo;
import com.macrotel.rapidstylers.engagement.repo.NewsletterSubscriberRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.outbox.OutboxEventType;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.engagement.service.NewsletterRules.*;

/**
 * Who gets the newsletter. Picture a guest list with a tick box per name:
 * only people who ticked it themselves are on it, the tick and its wording are
 * kept as proof, and anyone can cross themselves off from the link in every
 * email. A website visitor's tick only counts once they click the link we
 * email them (double opt-in), so nobody can sign up someone else's address.
 * A signed-in account's address is already verified, so its tick counts at once.
 *
 * <p>The suppression list sits beside it: addresses that bounced, complained
 * or an admin blocked. Nothing is ever sent to them, whatever the list says.
 */
@Service
public class NewsletterSubscriptionService {

    static final int CONFIRM_DAYS = 7;
    private static final String CHECK_INBOX = "Check your inbox and click the link to confirm your subscription.";

    @Autowired NewsletterSubscriberRepo subscriberRepo;
    @Autowired EmailSuppressionRepo suppressionRepo;
    @Autowired OutboxEventService outboxEventService;
    @Autowired AuditService auditService;
    @Autowired CustomerRecords customerRecords;
    @Autowired StylerRecords stylerRecords;
    @Autowired(required = false) RateLimiterService rateLimiter;

    // ── website visitors (double opt-in) ────────────────────────────────────

    /**
     * Starts a subscription and emails a confirm link. The answer is the same
     * whether the address is new, already subscribed or blocked, so the form
     * can't be used to find out who is on the list.
     */
    @Transactional
    public BaseResponse subscribe(String rawEmail, String province, boolean consent) {
        if (!consent) return error("Please tick the box to agree to receive emails");
        String email = email(rawEmail);
        if (email == null) return error("Enter a valid email address");
        if (limited("newsletter:ip:" + (rateLimiter == null ? "" : rateLimiter.clientIp()), 3600, 10)
                || limited("newsletter:email:" + email, 86400, 3)) {
            return status("429", "Too many attempts. Please try again later.");
        }
        if (suppressionRepo.existsByEmail(email)) return ok(CHECK_INBOX, null);
        NewsletterSubscriberEntity s = subscriberRepo.findByEmail(email).orElseGet(() -> newSubscriber(email, GUEST));
        if (SUBSCRIBED.equals(s.getStatus())) return ok(CHECK_INBOX, null);

        s.setStatus(PENDING);
        if (province(province) != null) s.setProvince(province(province));
        s.setSource("website");
        s.setConsentText(CONSENT_TEXT);
        s.setConfirmToken(token());
        s.setConfirmSentAt(now());
        s.setUpdatedAt(now());
        subscriberRepo.save(s);
        outboxEventService.newsletterEmail(OutboxEventType.NEWSLETTER_CONFIRM, String.valueOf(s.getId()),
                Map.of("emailAddress", email, "token", s.getConfirmToken()));
        return ok(CHECK_INBOX, null);
    }

    /** The click on the confirm link. */
    @Transactional
    public BaseResponse confirm(String token) {
        Optional<NewsletterSubscriberEntity> found = token == null || token.isBlank()
                ? Optional.empty() : subscriberRepo.findByConfirmToken(token);
        if (found.isEmpty()) return error("This link is invalid or was already used");
        NewsletterSubscriberEntity s = found.get();
        if (s.getConfirmSentAt() == null || s.getConfirmSentAt().isBefore(now().minusDays(CONFIRM_DAYS))) {
            return error("This link has expired. Please subscribe again.");
        }
        s.setStatus(SUBSCRIBED);
        s.setConsentAt(now());
        s.setConfirmToken(null);
        s.setUnsubscribedAt(null);
        s.setUpdatedAt(now());
        subscriberRepo.save(s);
        return ok("You're subscribed. Thanks for joining!", null);
    }

    /** The unsubscribe link in every newsletter. Works every time it is clicked. */
    @Transactional
    public BaseResponse unsubscribe(String token) {
        Optional<NewsletterSubscriberEntity> found = token == null || token.isBlank()
                ? Optional.empty() : subscriberRepo.findByUnsubscribeToken(token);
        if (found.isEmpty()) return error("This link is invalid");
        NewsletterSubscriberEntity s = found.get();
        if (!UNSUBSCRIBED.equals(s.getStatus())) {
            s.setStatus(UNSUBSCRIBED);
            s.setUnsubscribedAt(now());
            s.setConfirmToken(null);
            s.setUpdatedAt(now());
            subscriberRepo.save(s);
        }
        return ok("You've been unsubscribed. You won't get any more newsletters.", null);
    }

    /** What the unsubscribe page shows: a masked address and whether it's subscribed. */
    public BaseResponse preferences(String token) {
        Optional<NewsletterSubscriberEntity> found = token == null || token.isBlank()
                ? Optional.empty() : subscriberRepo.findByUnsubscribeToken(token);
        if (found.isEmpty()) return error("This link is invalid");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", masked(found.get().getEmail()));
        data.put("subscribed", SUBSCRIBED.equals(found.get().getStatus()));
        return ok(null, data);
    }

    // ── signed-in customers and stylists ────────────────────────────────────

    public BaseResponse mine(String accountId, String role) {
        Optional<Account> account = account(accountId, role);
        if (account.isEmpty()) return error("Newsletter settings are for customer and stylist accounts");
        Optional<NewsletterSubscriberEntity> s = subscriberRepo.findByEmail(account.get().email);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", account.get().email);
        data.put("subscribed", s.map(x -> SUBSCRIBED.equals(x.getStatus())).orElse(false));
        data.put("consentText", CONSENT_TEXT);
        return ok(null, data);
    }

    /** The newsletter switch on the account page. */
    @Transactional
    public BaseResponse updateMine(String accountId, String role, boolean subscribed) {
        Optional<Account> found = account(accountId, role);
        if (found.isEmpty()) return error("Newsletter settings are for customer and stylist accounts");
        Account account = found.get();
        if (subscribed && suppressionRepo.existsByEmail(account.email)) {
            return error("We can't send newsletters to this address. Please contact support.");
        }
        NewsletterSubscriberEntity s = subscriberRepo.findByEmail(account.email)
                .orElseGet(() -> newSubscriber(account.email, role));
        s.setRole(role);
        s.setAccountId(accountId);
        if (account.province != null) s.setProvince(account.province);
        if (subscribed && !SUBSCRIBED.equals(s.getStatus())) {
            s.setStatus(SUBSCRIBED);
            s.setSource("account");
            s.setConsentText(CONSENT_TEXT);
            s.setConsentAt(now());
            s.setConfirmToken(null);
            s.setUnsubscribedAt(null);
        } else if (!subscribed && !UNSUBSCRIBED.equals(s.getStatus())) {
            s.setStatus(UNSUBSCRIBED);
            s.setUnsubscribedAt(now());
            s.setConfirmToken(null);
        }
        s.setUpdatedAt(now());
        subscriberRepo.save(s);
        return mine(accountId, role);
    }

    // ── admin ───────────────────────────────────────────────────────────────

    public BaseResponse subscribers(String status, String search, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<NewsletterSubscriberEntity> rows;
        if (search != null && !search.isBlank()) {
            rows = subscriberRepo.findByEmailContaining(search.trim().toLowerCase(Locale.ROOT), pageable);
        } else if (status != null && !status.isBlank()) {
            rows = subscriberRepo.findByStatus(status.trim().toUpperCase(Locale.ROOT), pageable);
        } else {
            rows = subscriberRepo.findAll(pageable);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (NewsletterSubscriberEntity s : rows.getContent()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("email", s.getEmail());
            row.put("status", s.getStatus());
            row.put("role", s.getRole());
            row.put("province", s.getProvince());
            row.put("source", s.getSource());
            row.put("consentAt", s.getConsentAt());
            row.put("unsubscribedAt", s.getUnsubscribedAt());
            row.put("createdAt", s.getCreatedAt());
            row.put("suppressed", suppressionRepo.existsByEmail(s.getEmail()));
            items.add(row);
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String st : List.of(SUBSCRIBED, PENDING, UNSUBSCRIBED)) counts.put(st, subscriberRepo.countByStatus(st));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", rows.getTotalElements());
        data.put("page", rows.getNumber());
        data.put("counts", counts);
        return ok(null, data);
    }

    public BaseResponse suppressions() {
        return ok(null, suppressionRepo.findTop500ByOrderByCreatedAtDesc());
    }

    @Transactional
    public BaseResponse addSuppression(String adminId, String rawEmail, String note) {
        String email = email(rawEmail);
        if (email == null) return error("Enter a valid email address");
        if (suppressionRepo.existsByEmail(email)) return error("This address is already blocked");
        EmailSuppressionEntity e = new EmailSuppressionEntity();
        e.setEmail(email);
        e.setReason("MANUAL");
        e.setNote(note == null ? null : note.trim().substring(0, Math.min(note.trim().length(), 300)));
        e.setCreatedBy(adminId);
        e.setCreatedAt(now());
        suppressionRepo.save(e);
        auditService.audit(adminId, "ADMIN", "ADMIN_EMAIL_SUPPRESSED", "EMAIL", email, e.getNote());
        return ok("Blocked. No newsletter will go to this address.", e);
    }

    @Transactional
    public BaseResponse removeSuppression(String adminId, String rawEmail) {
        String email = email(rawEmail);
        Optional<EmailSuppressionEntity> e = email == null ? Optional.empty() : suppressionRepo.findByEmail(email);
        if (e.isEmpty()) return error("This address isn't blocked");
        suppressionRepo.delete(e.get());
        auditService.audit(adminId, "ADMIN", "ADMIN_EMAIL_UNSUPPRESSED", "EMAIL", email, e.get().getReason());
        return ok("Unblocked. They still only get newsletters if they are subscribed.", null);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static final class Account {
        final String email;
        final String province;

        Account(String email, String province) {
            this.email = email;
            this.province = province;
        }
    }

    private Optional<Account> account(String accountId, String role) {
        Optional<Account> account = Optional.empty();
        if ("CUSTOMER".equals(role)) {
            account = customerRecords.findByUserId(accountId)
                    .map(u -> new Account(email(u.getEmailAddress()), province(u.getState())));
        } else if ("STYLER".equals(role)) {
            account = stylerRecords.findByStylerId(accountId)
                    .map(s -> new Account(email(s.getEmailAddress()), province(s.getProvince())));
        }
        return account.filter(a -> a.email != null);
    }

    private NewsletterSubscriberEntity newSubscriber(String email, String role) {
        NewsletterSubscriberEntity s = new NewsletterSubscriberEntity();
        s.setEmail(email);
        s.setRole(role);
        s.setStatus(PENDING);
        s.setUnsubscribeToken(token());
        s.setCreatedAt(now());
        return s;
    }

    private boolean limited(String key, int windowSeconds, int max) {
        if (rateLimiter == null) return false;
        if (rateLimiter.isBlocked(key, windowSeconds, max)) return true;
        rateLimiter.record(key, windowSeconds);
        return false;
    }
}

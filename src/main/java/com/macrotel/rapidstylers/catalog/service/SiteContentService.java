package com.macrotel.rapidstylers.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.catalog.entity.SiteContentEntity;
import com.macrotel.rapidstylers.catalog.entity.SiteContentVersionEntity;
import com.macrotel.rapidstylers.catalog.repo.SiteContentRepo;
import com.macrotel.rapidstylers.catalog.repo.SiteContentVersionRepo;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Website content with a draft and a published copy, like a document with
 * "save" and "publish" buttons. Saving changes only the draft; publishing
 * copies the draft to what visitors see and keeps a numbered copy, so an
 * older version can be loaded back into the draft and published again.
 * What each kind may hold is in {@link SiteContentRules}.
 */
@Service
public class SiteContentService {

    private static final String CACHE_PREFIX = "site:content:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ObjectMapper json = new ObjectMapper();

    @Autowired SiteContentRepo contentRepo;
    @Autowired SiteContentVersionRepo versionRepo;
    @Autowired AuditService auditService;
    @Autowired(required = false) ReadCacheService readCacheService;

    // ── visitors ────────────────────────────────────────────────────────────

    /** The published FAQ, homepage or one page's SEO tags. Email templates are never public. */
    public BaseResponse publicContent(String key) {
        String kind = SiteContentRules.kindOf(key);
        if (kind == null || SiteContentRules.EMAIL.equals(kind)) return notFound();
        String body = cached(CACHE_PREFIX + key, () -> contentRepo.findByContentKey(key)
                .map(SiteContentEntity::getPublishedBody).orElse(""));
        if (body == null || body.isEmpty()) return notFound();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("content", parse(body));
        return ok(data);
    }

    /** Every published page's SEO tags, by path, so the website can load them once. */
    public BaseResponse publicSeo() {
        String all = cached(CACHE_PREFIX + "seo", () -> {
            Map<String, Object> pages = new TreeMap<>();
            for (SiteContentEntity c : contentRepo.findByKindOrderByContentKey(SiteContentRules.SEO)) {
                if (c.getPublishedBody() != null) pages.put(c.getContentKey().substring(4), parse(c.getPublishedBody()));
            }
            return write(pages);
        });
        return ok(parse(all));
    }

    // ── admin ───────────────────────────────────────────────────────────────

    /** Everything editable of one kind (or all), including pieces never saved yet. */
    public BaseResponse list(String kind) {
        String k = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase(Locale.ROOT);
        if (k != null && !List.of(SiteContentRules.FAQ, SiteContentRules.HOMEPAGE, SiteContentRules.SEO,
                SiteContentRules.EMAIL).contains(k)) {
            return error("kind must be FAQ, HOMEPAGE, SEO or EMAIL");
        }
        Map<String, SiteContentEntity> saved = new LinkedHashMap<>();
        for (SiteContentEntity c : contentRepo.findAll()) saved.put(c.getContentKey(), c);
        List<String> keys = new ArrayList<>(List.of("faq", "homepage"));
        SiteContentRules.EMAIL_TEMPLATES.keySet().forEach(t -> keys.add("email:" + t));
        saved.keySet().stream().filter(key -> !keys.contains(key)).sorted().forEach(keys::add);
        List<Map<String, Object>> items = new ArrayList<>();
        for (String key : keys) {
            String keyKind = SiteContentRules.kindOf(key);
            if (keyKind == null || (k != null && !k.equals(keyKind))) continue;
            items.add(summary(key, keyKind, saved.get(key)));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("emailPlaceholders", SiteContentRules.EMAIL_TEMPLATES);
        return ok(data);
    }

    /** One piece with its draft and published copies. */
    public BaseResponse get(String key) {
        String kind = SiteContentRules.kindOf(key);
        if (kind == null) return notFound();
        Optional<SiteContentEntity> found = contentRepo.findByContentKey(key);
        Map<String, Object> data = summary(key, kind, found.orElse(null));
        data.put("draft", found.map(SiteContentEntity::getDraftBody).map(this::parse).orElse(null));
        data.put("published", found.map(SiteContentEntity::getPublishedBody).map(this::parse).orElse(null));
        if (SiteContentRules.EMAIL.equals(kind)) {
            data.put("placeholders", SiteContentRules.EMAIL_TEMPLATES.get(key.substring(6)));
        }
        return ok(data);
    }

    /** Saves the draft. Visitors see nothing new until it is published. */
    @Transactional
    public BaseResponse saveDraft(String adminId, String key, Map<String, Object> body) {
        Map<String, Object> clean;
        try {
            clean = SiteContentRules.clean(key, body);
        } catch (IllegalArgumentException bad) {
            return error(bad.getMessage());
        }
        SiteContentEntity c = contentRepo.findByContentKey(key).orElseGet(() -> {
            SiteContentEntity fresh = new SiteContentEntity();
            fresh.setContentKey(key);
            fresh.setKind(SiteContentRules.kindOf(key));
            return fresh;
        });
        c.setDraftBody(write(clean));
        c.setDraftUpdatedBy(adminId);
        c.setDraftUpdatedAt(now());
        contentRepo.save(c);
        auditService.audit(adminId, "ADMIN", "ADMIN_CONTENT_SAVED", "SITE_CONTENT", key, "Draft saved");
        return get(key);
    }

    /** Makes the draft what visitors see, and keeps a numbered copy of it. */
    @Transactional
    public BaseResponse publish(String adminId, String key) {
        Optional<SiteContentEntity> found = key == null ? Optional.empty() : contentRepo.findByContentKey(key);
        if (found.isEmpty() || found.get().getDraftBody() == null) return error("Save a draft before publishing");
        SiteContentEntity c = found.get();
        if (c.getDraftBody().equals(c.getPublishedBody())) return error("The draft is already published");
        LocalDateTime now = now();
        c.setPublishedBody(c.getDraftBody());
        c.setVersion(c.getVersion() + 1);
        c.setPublishedBy(adminId);
        c.setPublishedAt(now);
        contentRepo.save(c);
        SiteContentVersionEntity v = new SiteContentVersionEntity();
        v.setContentKey(key);
        v.setVersion(c.getVersion());
        v.setBody(c.getPublishedBody());
        v.setPublishedBy(adminId);
        v.setPublishedAt(now);
        versionRepo.save(v);
        evict(key);
        auditService.audit(adminId, "ADMIN", "ADMIN_CONTENT_PUBLISHED", "SITE_CONTENT", key, "Published version " + c.getVersion());
        return get(key);
    }

    /** Takes the page down: visitors see nothing (the site falls back to its built-in text). The draft stays. */
    @Transactional
    public BaseResponse unpublish(String adminId, String key) {
        Optional<SiteContentEntity> found = key == null ? Optional.empty() : contentRepo.findByContentKey(key);
        if (found.isEmpty() || found.get().getPublishedBody() == null) return error("Nothing is published");
        found.get().setPublishedBody(null);
        contentRepo.save(found.get());
        evict(key);
        auditService.audit(adminId, "ADMIN", "ADMIN_CONTENT_UNPUBLISHED", "SITE_CONTENT", key, "Unpublished");
        return get(key);
    }

    /** Published versions, newest first. */
    public BaseResponse history(String key) {
        if (SiteContentRules.kindOf(key) == null) return notFound();
        List<Map<String, Object>> items = new ArrayList<>();
        for (SiteContentVersionEntity v : versionRepo.findByContentKeyOrderByVersionDesc(key)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", v.getVersion());
            m.put("publishedBy", v.getPublishedBy());
            m.put("publishedAt", v.getPublishedAt());
            m.put("content", parse(v.getBody()));
            items.add(m);
        }
        return ok(items);
    }

    /** Loads an older published version into the draft, ready to publish again. */
    @Transactional
    public BaseResponse restore(String adminId, String key, Integer version) {
        Optional<SiteContentVersionEntity> v = key == null || version == null ? Optional.empty()
                : versionRepo.findByContentKeyAndVersion(key, version);
        if (v.isEmpty()) return notFound();
        SiteContentEntity c = contentRepo.findByContentKey(key).orElseThrow();
        c.setDraftBody(v.get().getBody());
        c.setDraftUpdatedBy(adminId);
        c.setDraftUpdatedAt(now());
        contentRepo.save(c);
        auditService.audit(adminId, "ADMIN", "ADMIN_CONTENT_RESTORED", "SITE_CONTENT", key,
                "Version " + version + " loaded into the draft");
        return get(key);
    }

    /** An email template filled with sample values, from the draft (default) or the published copy. */
    public BaseResponse previewEmail(String key, boolean published) {
        if (!SiteContentRules.EMAIL.equals(SiteContentRules.kindOf(key))) return notFound();
        Optional<SiteContentEntity> c = contentRepo.findByContentKey(key);
        String body = c.map(published ? SiteContentEntity::getPublishedBody : SiteContentEntity::getDraftBody).orElse(null);
        if (body == null) return error(published ? "Nothing is published" : "Save a draft first");
        SiteContentRules.RenderedEmail email = SiteContentRules.render(parse(body), SiteContentRules.sampleValues());
        return ok(Map.of("subject", email.subject, "html", email.html));
    }

    /**
     * The published template for an email, filled in, for code that sends that
     * email; empty when the admin has not published one, so the caller keeps its
     * built-in wording.
     */
    public Optional<SiteContentRules.RenderedEmail> renderEmail(String template, Map<String, String> values) {
        String key = "email:" + template;
        if (!SiteContentRules.EMAIL.equals(SiteContentRules.kindOf(key))) return Optional.empty();
        return contentRepo.findByContentKey(key).map(SiteContentEntity::getPublishedBody)
                .map(body -> SiteContentRules.render(parse(body), values));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> summary(String key, String kind, SiteContentEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("kind", kind);
        m.put("version", c == null ? 0 : c.getVersion());
        m.put("published", c != null && c.getPublishedBody() != null);
        m.put("hasUnpublishedChanges", c != null && c.getDraftBody() != null && !c.getDraftBody().equals(c.getPublishedBody()));
        m.put("draftUpdatedBy", c == null ? null : c.getDraftUpdatedBy());
        m.put("draftUpdatedAt", c == null ? null : c.getDraftUpdatedAt());
        m.put("publishedBy", c == null ? null : c.getPublishedBy());
        m.put("publishedAt", c == null ? null : c.getPublishedAt());
        return m;
    }

    private String cached(String cacheKey, java.util.function.Supplier<String> loader) {
        return readCacheService == null ? loader.get() : readCacheService.getOrLoad(cacheKey, CACHE_TTL, Duration.ZERO, loader);
    }

    private void evict(String key) {
        if (readCacheService != null) readCacheService.evict(CACHE_PREFIX + key, CACHE_PREFIX + "seo");
    }

    private Map<String, Object> parse(String body) {
        try {
            return json.readValue(body, MAP);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored content is not valid JSON", ex);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static BaseResponse ok(Object data) {
        BaseResponse r = new BaseResponse(true);
        r.setStatusCode(SUCCESS_STATUS_CODE);
        r.setMessage(SUCCESS_MESSAGE);
        r.setData(data);
        return r;
    }

    private static BaseResponse error(String message) {
        BaseResponse r = new BaseResponse(true);
        r.setMessage(message);
        return r;
    }

    private static BaseResponse notFound() {
        BaseResponse r = new BaseResponse(true);
        r.setStatusCode("404");
        r.setMessage("Content not found");
        return r;
    }
}

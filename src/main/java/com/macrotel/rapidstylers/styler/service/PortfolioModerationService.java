package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.StylerPortfolioEntity;
import com.macrotel.rapidstylers.styler.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * The admin's gallery queue. A stylist's new portfolio photo starts PENDING:
 * it shows on their own profile but not in the public gallery. An admin
 * approves it (into the gallery) or rejects it with a note the stylist reads.
 * A decision can be changed later; each one is written to the audit log.
 */
@Service
public class PortfolioModerationService {

    private static final Logger LOG = Logger.getLogger(PortfolioModerationService.class.getName());
    private static final List<String> STATES = List.of(
            StylerPortfolioEntity.PENDING, StylerPortfolioEntity.APPROVED, StylerPortfolioEntity.REJECTED);

    @Autowired StylerPortfolioRepo portfolioRepo;
    @Autowired StylerRepo stylerRepo;
    @Autowired AuditService auditService;
    @Autowired ReadCacheService readCacheService;
    @Autowired EmailConfig emailConfig;

    /** {items, page, size, total, totalPages, counts}; status defaults to PENDING, ALL lists every photo. Oldest first for PENDING (a queue), newest first otherwise. */
    public BaseResponse queue(String status, Integer page, Integer size) {
        BaseResponse response = new BaseResponse(true);
        String state = status == null || status.isBlank() ? StylerPortfolioEntity.PENDING : status.trim().toUpperCase(Locale.ROOT);
        if (!"ALL".equals(state) && !STATES.contains(state)) {
            response.setMessage("status must be PENDING, APPROVED, REJECTED or ALL");
            return response;
        }
        int pageNumber = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? 24 : Math.min(size, 100);
        Sort order = StylerPortfolioEntity.PENDING.equals(state) ? Sort.by("id") : Sort.by(Sort.Order.desc("id"));
        PageRequest request = PageRequest.of(pageNumber, pageSize, order);
        Page<StylerPortfolioEntity> found = "ALL".equals(state) ? portfolioRepo.findAll(request)
                : portfolioRepo.findByModerationStatus(state, request);
        Map<String, Optional<StylerEntity>> stylists = new LinkedHashMap<>();
        List<Map<String, Object>> items = found.getContent().stream()
                .map(p -> row(p, stylists.computeIfAbsent(p.getStylerId(), stylerRepo::findByStylerId)))
                .collect(Collectors.toList());
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : STATES) counts.put(s, portfolioRepo.countByModerationStatus(s));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("page", pageNumber);
        data.put("size", pageSize);
        data.put("total", found.getTotalElements());
        data.put("totalPages", found.getTotalPages());
        data.put("counts", counts);
        return ok(response, data);
    }

    /** Approves a photo into the public gallery. The note is optional. */
    public BaseResponse approve(String adminId, Long portfolioId, String note) {
        return decide(adminId, portfolioId, StylerPortfolioEntity.APPROVED, note);
    }

    /** Rejects a photo: off the gallery and the stylist's public profile. The note is required and emailed to the stylist. */
    public BaseResponse reject(String adminId, Long portfolioId, String note) {
        if (note == null || note.isBlank()) {
            BaseResponse response = new BaseResponse(true);
            response.setMessage("Say why the photo is rejected; the stylist will see this note");
            return response;
        }
        return decide(adminId, portfolioId, StylerPortfolioEntity.REJECTED, note);
    }

    /** One photo with every moderation decision made on it. */
    public BaseResponse history(Long portfolioId) {
        BaseResponse response = new BaseResponse(true);
        Optional<StylerPortfolioEntity> found = portfolioId == null ? Optional.empty() : portfolioRepo.findById(portfolioId);
        if (found.isEmpty()) return notFound(response);
        StylerPortfolioEntity p = found.get();
        Map<String, Object> data = row(p, stylerRepo.findByStylerId(p.getStylerId()));
        data.put("history", auditService.searchLogs(null, null, "portfolio:" + p.getId(), PageRequest.of(0, 50)).getContent());
        return ok(response, data);
    }

    private BaseResponse decide(String adminId, Long portfolioId, String state, String note) {
        BaseResponse response = new BaseResponse(true);
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > 500) {
            response.setMessage("Keep the note under 500 characters");
            return response;
        }
        Optional<StylerPortfolioEntity> found = portfolioId == null ? Optional.empty() : portfolioRepo.findById(portfolioId);
        if (found.isEmpty()) return notFound(response);
        StylerPortfolioEntity p = found.get();
        String before = p.getModerationStatus();
        p.setModerationStatus(state);
        p.setModerationNote(cleanNote);
        p.setModeratedBy(adminId);
        p.setModeratedAt(LocalDateTime.now(ZoneOffset.UTC));
        portfolioRepo.save(p);
        if (readCacheService != null) {
            readCacheService.evict(ReadCacheService.KEY_STYLER_PORTFOLIO + p.getStylerId(),
                    ReadCacheService.KEY_STYLER_DTO + p.getStylerId());
        }
        auditService.audit(adminId, "ADMIN",
                StylerPortfolioEntity.APPROVED.equals(state) ? "ADMIN_GALLERY_APPROVED" : "ADMIN_GALLERY_REJECTED",
                "PORTFOLIO", "portfolio:" + p.getId(),
                "from=" + before + " stylerId=" + p.getStylerId() + (cleanNote == null ? "" : " note=" + cleanNote));
        Optional<StylerEntity> stylist = stylerRepo.findByStylerId(p.getStylerId());
        if (StylerPortfolioEntity.REJECTED.equals(state) && !StylerPortfolioEntity.REJECTED.equals(before)) {
            stylist.ifPresent(s -> tellStylist(s, p, cleanNote));
        }
        return ok(response, row(p, stylist));
    }

    /** Best effort: a mail failure never undoes the decision. */
    private void tellStylist(StylerEntity s, StylerPortfolioEntity p, String note) {
        if (s.getEmailAddress() == null || s.getEmailAddress().isBlank()) return;
        try {
            String name = HtmlUtils.htmlEscape(((s.getFirstname() == null ? "" : s.getFirstname()) + " "
                    + (s.getLastname() == null ? "" : s.getLastname())).trim());
            String category = p.getCategory() == null ? "" : HtmlUtils.htmlEscape(p.getCategory());
            String body = "Dear " + (name.isEmpty() ? "Stylist" : name) + ",<br><br>"
                    + "One of your portfolio photos" + (category.isEmpty() ? "" : " (" + category + ")")
                    + " was not approved for the RapidStylers gallery.<br><br>"
                    + "Our team's note: " + HtmlUtils.htmlEscape(note)
                    + "<br><br>You can remove it or upload a different photo from your dashboard."
                    + "<br><br>Thank you,<br>The RapidStylers Team";
            emailConfig.sendSimpleMail(s.getEmailAddress(), "A portfolio photo was not approved", body);
        } catch (Exception ex) {
            LOG.warning("Gallery rejection mail failed: " + ex.getMessage());
        }
    }

    private static Map<String, Object> row(StylerPortfolioEntity p, Optional<StylerEntity> stylist) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", p.getId());
        r.put("imageUrl", p.getImageUrl());
        r.put("name", p.getName());
        r.put("category", p.getCategory());
        r.put("createdAt", p.getCreatedAt());
        r.put("moderationStatus", p.getModerationStatus());
        r.put("moderationNote", p.getModerationNote());
        r.put("moderatedBy", p.getModeratedBy());
        r.put("moderatedAt", p.getModeratedAt());
        r.put("stylerId", p.getStylerId());
        stylist.ifPresent(s -> {
            r.put("businessName", s.getBusinessName());
            r.put("firstname", s.getFirstname());
            r.put("lastname", s.getLastname());
            r.put("emailAddress", s.getEmailAddress());
            r.put("verificationStatus", s.getVerificationStatus());
        });
        return r;
    }

    private static BaseResponse notFound(BaseResponse response) {
        response.setStatusCode("404");
        response.setMessage("Photo not found");
        return response;
    }

    private static BaseResponse ok(BaseResponse response, Object data) {
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return response;
    }
}

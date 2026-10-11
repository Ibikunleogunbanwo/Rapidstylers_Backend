package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.service.RateLimiterService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.payment.entity.PromoCodeEntity;
import com.macrotel.rapidstylers.payment.entity.PromoRedemptionEntity;
import com.macrotel.rapidstylers.payment.repo.PromoCodeRepo;
import com.macrotel.rapidstylers.payment.repo.PromoRedemptionRepo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Promo codes. Picture a coupon book the admin prints: each coupon says how
 * much it takes off (a percent with an optional cap, or a fixed amount), when
 * it is valid, how many times it can be used in all and by one customer, the
 * smallest booking it applies to, and whether it is only for a first booking.
 * The server alone decides whether a code applies and how much it is worth;
 * the browser only ever shows the server's answer.
 *
 * <p>{@link #redeem} records a code against a booking under a row lock, so the
 * last use of a limited code goes to exactly one booking; {@link #release}
 * gives the use back when that booking is declined or cancelled.
 */
@Service
public class PromotionService {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9-]{3,40}");
    /** Card payments below this are refused by Stripe, so a discount never takes the total under it. */
    static final long MIN_CHARGE_CENTS = 50;
    private static final int CHECKS_PER_HOUR = 30;

    @Autowired PromoCodeRepo promoCodeRepo;
    @Autowired PromoRedemptionRepo redemptionRepo;
    @Autowired AppointmentRecords appointmentRecords;
    @Autowired AuditService auditService;
    @Autowired(required = false) RateLimiterService rateLimiterService;

    /** The server's answer for one code on one booking amount. */
    public static final class Quote {
        public final boolean ok;
        public final String message;
        public final PromoCodeEntity promo;
        public final long discountCents;

        public Quote(boolean ok, String message, PromoCodeEntity promo, long discountCents) {
            this.ok = ok;
            this.message = message;
            this.promo = promo;
            this.discountCents = discountCents;
        }

        static Quote no(String message) {
            return new Quote(false, message, null, 0);
        }
    }

    // ── customers ───────────────────────────────────────────────────────────

    /** Preview for the booking form: {code, description, subtotal, discount, total}. Rate-limited per customer. */
    public BaseResponse check(String userId, String code, String subtotalText) {
        BaseResponse response = new BaseResponse(true);
        if (rateLimiterService != null) {
            String key = "promo_check:" + userId;
            if (rateLimiterService.isBlocked(key, 3600, CHECKS_PER_HOUR)) {
                response.setMessage("Too many promo code attempts. Please try again later.");
                return response;
            }
            rateLimiterService.record(key, 3600);
        }
        long subtotal = MoneyUtils.centsFromPrice(subtotalText == null ? "0" : subtotalText);
        Quote q = quote(code, userId, subtotal);
        if (!q.ok) {
            response.setMessage(q.message);
            return response;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", q.promo.getCode());
        data.put("description", q.promo.getDescription());
        data.put("currency", "CAD");
        data.put("subtotal", BigDecimal.valueOf(subtotal, 2));
        data.put("discount", BigDecimal.valueOf(q.discountCents, 2));
        data.put("total", BigDecimal.valueOf(subtotal - q.discountCents, 2));
        return ok(response, data);
    }

    /** Whether {@code code} applies to this customer's booking of {@code subtotalCents}, and for how much. */
    public Quote quote(String code, String userId, long subtotalCents) {
        String c = normalise(code);
        if (c == null) return Quote.no("That promo code isn't valid");
        Optional<PromoCodeEntity> found = promoCodeRepo.findByCode(c);
        return found.map(p -> quote(p, userId, subtotalCents, 0)).orElse(Quote.no("That promo code isn't valid"));
    }

    /** {@code ownBookings}: bookings of this customer's already saved as part of this request (1 inside {@link #redeem}). */
    private Quote quote(PromoCodeEntity p, String userId, long subtotalCents, int ownBookings) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!p.isActive()) return Quote.no("That promo code isn't valid");
        if (p.getStartsAt() != null && now.isBefore(p.getStartsAt())) return Quote.no("That promo code isn't active yet");
        if (p.getExpiresAt() != null && !now.isBefore(p.getExpiresAt())) return Quote.no("That promo code has expired");
        if (p.getMaxRedemptions() != null
                && redemptionRepo.countByPromoIdAndStatus(p.getId(), PromoRedemptionEntity.APPLIED) >= p.getMaxRedemptions()) {
            return Quote.no("That promo code has been fully used");
        }
        if (userId != null && redemptionRepo.countByPromoIdAndUserIdAndStatus(p.getId(), userId,
                PromoRedemptionEntity.APPLIED) >= p.getMaxPerCustomer()) {
            return Quote.no("You've already used this promo code");
        }
        if (p.isFirstBookingOnly() && userId != null && appointmentRecords.countByUserId(userId) > ownBookings) {
            return Quote.no("This promo code is for your first booking only");
        }
        if (p.getMinBookingCents() != null && subtotalCents < p.getMinBookingCents()) {
            return Quote.no("This promo code needs a booking of at least $" + BigDecimal.valueOf(p.getMinBookingCents(), 2));
        }
        long discount = discountFor(p, subtotalCents);
        if (discount <= 0) return Quote.no("This promo code doesn't lower this booking's price");
        return new Quote(true, null, p, discount);
    }

    static long discountFor(PromoCodeEntity p, long subtotalCents) {
        long d;
        if (PromoCodeEntity.PERCENT.equals(p.getDiscountType())) {
            d = Math.round(subtotalCents * p.getDiscountValue() / 100.0);
            if (p.getMaxDiscountCents() != null) d = Math.min(d, p.getMaxDiscountCents());
        } else {
            d = p.getDiscountValue();
        }
        return Math.max(0, Math.min(d, subtotalCents - MIN_CHARGE_CENTS));
    }

    /**
     * Records the code against a booking, re-checking it under a lock on the
     * code's row. Call inside the booking's transaction, after the booking row
     * is saved. Returns the quote that was applied, or a refusal (the booking
     * should then stop).
     */
    @Transactional
    public Quote redeem(String code, String userId, String appointmentId, long subtotalCents) {
        String c = normalise(code);
        if (c == null) return Quote.no("That promo code isn't valid");
        Optional<PromoCodeEntity> locked = promoCodeRepo.findByCodeForUpdate(c);
        if (locked.isEmpty()) return Quote.no("That promo code isn't valid");
        Quote q = quote(locked.get(), userId, subtotalCents, 1);
        if (!q.ok) return q;
        PromoRedemptionEntity r = new PromoRedemptionEntity();
        r.setPromoId(q.promo.getId());
        r.setCode(q.promo.getCode());
        r.setUserId(userId);
        r.setAppointmentId(appointmentId);
        r.setDiscountCents(q.discountCents);
        r.setStatus(PromoRedemptionEntity.APPLIED);
        r.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        redemptionRepo.save(r);
        return q;
    }

    /** Gives a declined or cancelled booking's use back to the code. Safe to call for any booking. */
    @Transactional
    public void release(String appointmentId) {
        if (appointmentId == null) return;
        redemptionRepo.findByAppointmentId(appointmentId).ifPresent(r -> {
            if (PromoRedemptionEntity.APPLIED.equals(r.getStatus())) {
                r.setStatus(PromoRedemptionEntity.RELEASED);
                redemptionRepo.save(r);
            }
        });
    }

    /** The discount recorded on a booking, in cents (0 when none). */
    public long discountOn(String appointmentId) {
        if (appointmentId == null) return 0;
        return redemptionRepo.findByAppointmentId(appointmentId)
                .filter(r -> PromoRedemptionEntity.APPLIED.equals(r.getStatus()))
                .map(PromoRedemptionEntity::getDiscountCents).orElse(0L);
    }

    /**
     * What a stylist's share is worked out on. The platform pays for promo
     * discounts, so this is what the platform kept plus the discount, with the
     * discount scaled down by the same fraction as any refund (a full refund
     * leaves nothing).
     */
    public static long stylistBaseCents(Long discountCents, long keptCents, long chargedCents) {
        if (discountCents == null || discountCents <= 0 || keptCents <= 0 || chargedCents <= 0) return keptCents;
        return keptCents + Math.round((double) discountCents * Math.min(keptCents, chargedCents) / chargedCents);
    }

    // ── admin ───────────────────────────────────────────────────────────────

    /** Every code, newest first, with how often it has been used and the discount given. */
    public BaseResponse list() {
        List<Map<String, Object>> items = new ArrayList<>();
        List<PromoCodeEntity> all = promoCodeRepo.findAll();
        all.sort(Comparator.comparing(PromoCodeEntity::getId).reversed());
        for (PromoCodeEntity p : all) {
            Map<String, Object> m = row(p);
            List<PromoRedemptionEntity> used = redemptionRepo.findByPromoIdAndStatus(p.getId(), PromoRedemptionEntity.APPLIED);
            m.put("redemptions", used.size());
            m.put("discountGiven", BigDecimal.valueOf(used.stream().mapToLong(PromoRedemptionEntity::getDiscountCents).sum(), 2));
            items.add(m);
        }
        return ok(new BaseResponse(true), items);
    }

    /** The latest 200 bookings that used a code. */
    public BaseResponse redemptions(Long promoId) {
        BaseResponse response = new BaseResponse(true);
        if (promoId == null || promoCodeRepo.findById(promoId).isEmpty()) return notFound(response);
        List<Map<String, Object>> items = new ArrayList<>();
        for (PromoRedemptionEntity r : redemptionRepo.findByPromoIdOrderByIdDesc(promoId, PageRequest.of(0, 200))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("appointmentId", r.getAppointmentId());
            m.put("userId", r.getUserId());
            m.put("discount", BigDecimal.valueOf(r.getDiscountCents(), 2));
            m.put("status", r.getStatus());
            m.put("createdAt", r.getCreatedAt());
            items.add(m);
        }
        return ok(response, items);
    }

    /**
     * Creates (no id) or edits a code. Dollar amounts arrive as text ("10.00");
     * times as ISO date-times in UTC ("2026-12-31T23:59:59"). The code itself
     * cannot change once anyone has used it.
     */
    public BaseResponse save(String adminId, Map<String, Object> body) {
        BaseResponse response = new BaseResponse(true);
        try {
            Long id = longValue(body.get("id"));
            PromoCodeEntity p;
            if (id == null) {
                p = new PromoCodeEntity();
                p.setCreatedBy(adminId);
                p.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            } else {
                Optional<PromoCodeEntity> found = promoCodeRepo.findById(id);
                if (found.isEmpty()) return notFound(response);
                p = found.get();
            }
            String code = normalise(text(body.get("code")));
            if (code == null) throw new IllegalArgumentException("Codes are 3 to 40 letters, digits or dashes");
            if (!code.equals(p.getCode())) {
                Optional<PromoCodeEntity> clash = promoCodeRepo.findByCode(code);
                if (clash.isPresent()) throw new IllegalArgumentException(code + " already exists");
                if (p.getId() != null && redemptionRepo.countByPromoIdAndStatus(p.getId(), PromoRedemptionEntity.APPLIED) > 0) {
                    throw new IllegalArgumentException("This code has been used, so it can't be renamed");
                }
            }
            String type = text(body.get("discountType"));
            type = type == null ? null : type.trim().toUpperCase(Locale.ROOT);
            long value;
            if (PromoCodeEntity.PERCENT.equals(type)) {
                value = wholeNumber(body.get("discountValue"), "discountValue");
                if (value < 1 || value > 100) throw new IllegalArgumentException("A percent discount is 1 to 100");
            } else if (PromoCodeEntity.FIXED.equals(type)) {
                value = cents(body.get("discountValue"), "discountValue");
                if (value <= 0) throw new IllegalArgumentException("A fixed discount must be more than $0");
            } else {
                throw new IllegalArgumentException("discountType must be PERCENT or FIXED");
            }
            Long maxDiscount = body.get("maxDiscount") == null || text(body.get("maxDiscount")).isBlank() ? null : cents(body.get("maxDiscount"), "maxDiscount");
            Long minBooking = body.get("minBooking") == null || text(body.get("minBooking")).isBlank() ? null : cents(body.get("minBooking"), "minBooking");
            LocalDateTime starts = time(body.get("startsAt"), "startsAt");
            LocalDateTime expires = time(body.get("expiresAt"), "expiresAt");
            if (starts != null && expires != null && !expires.isAfter(starts)) {
                throw new IllegalArgumentException("expiresAt must be after startsAt");
            }
            Long maxRedemptions = body.get("maxRedemptions") == null || text(body.get("maxRedemptions")).isBlank() ? null : wholeNumber(body.get("maxRedemptions"), "maxRedemptions");
            Long perCustomer = body.get("maxPerCustomer") == null || text(body.get("maxPerCustomer")).isBlank() ? 1L : wholeNumber(body.get("maxPerCustomer"), "maxPerCustomer");
            if ((maxRedemptions != null && maxRedemptions < 1) || perCustomer < 1) {
                throw new IllegalArgumentException("Use limits must be at least 1");
            }
            String description = text(body.get("description"));
            if (description != null && description.length() > 300) throw new IllegalArgumentException("Keep the description under 300 characters");

            p.setCode(code);
            p.setDescription(description == null || description.isBlank() ? null : description.trim());
            p.setDiscountType(type);
            p.setDiscountValue(value);
            p.setMaxDiscountCents(PromoCodeEntity.PERCENT.equals(type) ? maxDiscount : null);
            p.setMinBookingCents(minBooking);
            p.setStartsAt(starts);
            p.setExpiresAt(expires);
            p.setMaxRedemptions(maxRedemptions == null ? null : maxRedemptions.intValue());
            p.setMaxPerCustomer(perCustomer.intValue());
            p.setFirstBookingOnly(Boolean.parseBoolean(String.valueOf(body.get("firstBookingOnly"))));
            p.setActive(body.get("active") == null || Boolean.parseBoolean(String.valueOf(body.get("active"))));
            p.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            promoCodeRepo.save(p);
            auditService.audit(adminId, "ADMIN", "ADMIN_PROMO_SAVED", "PROMO_CODE", "promo:" + p.getId(),
                    code + " " + type + "=" + value + " active=" + p.isActive());
            return ok(response, row(p));
        } catch (IllegalArgumentException bad) {
            response.setMessage(bad.getMessage());
            return response;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    static String normalise(String code) {
        if (code == null) return null;
        String c = code.trim().toUpperCase(Locale.ROOT);
        return CODE.matcher(c).matches() ? c : null;
    }

    private static Map<String, Object> row(PromoCodeEntity p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("code", p.getCode());
        m.put("description", p.getDescription());
        m.put("discountType", p.getDiscountType());
        m.put("discountValue", PromoCodeEntity.FIXED.equals(p.getDiscountType())
                ? BigDecimal.valueOf(p.getDiscountValue(), 2) : BigDecimal.valueOf(p.getDiscountValue()));
        m.put("maxDiscount", p.getMaxDiscountCents() == null ? null : BigDecimal.valueOf(p.getMaxDiscountCents(), 2));
        m.put("minBooking", p.getMinBookingCents() == null ? null : BigDecimal.valueOf(p.getMinBookingCents(), 2));
        m.put("startsAt", p.getStartsAt());
        m.put("expiresAt", p.getExpiresAt());
        m.put("maxRedemptions", p.getMaxRedemptions());
        m.put("maxPerCustomer", p.getMaxPerCustomer());
        m.put("firstBookingOnly", p.isFirstBookingOnly());
        m.put("active", p.isActive());
        m.put("createdBy", p.getCreatedBy());
        m.put("createdAt", p.getCreatedAt());
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static Long longValue(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("id must be a number");
        }
    }

    private static long wholeNumber(Object raw, String name) {
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }

    private static long cents(Object raw, String name) {
        try {
            return new BigDecimal(String.valueOf(raw).replace("$", "").replace(",", "").trim())
                    .movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException(name + " must be an amount like 10.00");
        }
    }

    private static LocalDateTime time(Object raw, String name) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            String t = String.valueOf(raw).trim();
            return t.length() == 10 ? java.time.LocalDate.parse(t).atStartOfDay() : LocalDateTime.parse(t.replace("Z", ""));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(name + " must be a UTC date-time like 2026-12-31T23:59:59");
        }
    }

    private static BaseResponse notFound(BaseResponse response) {
        response.setStatusCode("404");
        response.setMessage("Promo code not found");
        return response;
    }

    private static BaseResponse ok(BaseResponse response, Object data) {
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return response;
    }
}

package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.booking.entity.QuoteEntity;
import com.macrotel.rapidstylers.booking.pojo.QuoteOfferData;
import com.macrotel.rapidstylers.booking.pojo.QuoteRequestData;
import com.macrotel.rapidstylers.booking.repo.QuoteRepo;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.engagement.service.NotificationService;
import com.macrotel.rapidstylers.payment.service.MoneyUtils;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.service.StylerEligibility;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Custom quotes: a customer asks a stylist to price a job, the stylist answers
 * with a price and duration, and the customer books it at that price through
 * the normal checkout ({@link BookingService} reads the quote by its ref).
 */
@Service
public class QuoteService {
    /** Quotes outside this range are almost certainly typos ($15 meant as $1500, or the reverse). */
    static final long MIN_PRICE_CENTS = 20_00;
    static final long MAX_PRICE_CENTS = 5_000_00;
    static final int DEFAULT_VALID_DAYS = 3;
    /** Open requests a customer may have at once, in total and with one stylist, so nobody is flooded. */
    static final int MAX_OPEN_PER_CUSTOMER = 5;
    static final int MAX_OPEN_PER_STYLIST = 2;
    private static final int SLOT_MINUTES = 15;
    private static final List<String> OPEN = List.of(QuoteEntity.REQUESTED, QuoteEntity.QUOTED);

    @Autowired
    QuoteRepo quoteRepo;
    @Autowired
    StylerRecords stylerRecords;
    @Autowired
    CustomerRecords customerRecords;
    @Autowired
    StylerEligibility stylerEligibility;
    @Autowired(required = false)
    NotificationService notificationService;
    @Autowired(required = false)
    AuditService auditService;
    Clock clock = Clock.systemUTC();

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse request(String userId, QuoteRequestData data) {
        BaseResponse response = new BaseResponse(true);
        if (customerRecords.findByUserId(userId).isEmpty()) {
            return Responses.errorResponse(response, "Invalid User Id, Kindly create account");
        }
        Optional<StylerEntity> styler = stylerRecords.findByStylerId(data.getStylerId());
        if (styler.isEmpty() || !stylerEligibility.isApprovedStyler(styler.get())) {
            return Responses.errorResponse(response, "This professional is not taking quote requests right now");
        }
        Optional<SubServiceEntity> service = stylerRecords.findSubService(data.getStylerId(), Long.parseLong(data.getSubServiceId()));
        if (service.isEmpty()) {
            return Responses.errorResponse(response, "Choose one of this professional's services");
        }
        boolean homeVisit = "homeService".equals(data.getServiceTime());
        if (homeVisit && !styler.get().isOffersHomeVisits()) {
            return Responses.errorResponse(response, "This professional doesn't offer home visits");
        }
        if (!homeVisit && !styler.get().isOffersSalonVisits()) {
            return Responses.errorResponse(response, "This professional only offers home visits");
        }
        LocalDateTime now = now();
        List<QuoteEntity> open = quoteRepo.findByUserIdAndStatusIn(userId, OPEN).stream()
                .filter(quote -> quote.isOpen(now)).toList();
        if (open.size() >= MAX_OPEN_PER_CUSTOMER) {
            return Responses.errorResponse(response, "You have " + open.size()
                    + " quote requests waiting. Book or close one before asking for another.");
        }
        if (open.stream().filter(quote -> quote.getStylerId().equals(data.getStylerId())).count() >= MAX_OPEN_PER_STYLIST) {
            return Responses.errorResponse(response, "You already have quote requests waiting with this professional");
        }

        QuoteEntity quote = new QuoteEntity();
        quote.setQuoteRef(newRef());
        quote.setUserId(userId);
        quote.setStylerId(data.getStylerId());
        quote.setSubServiceId(data.getSubServiceId());
        quote.setDescription(AppUtils.sanitizeText(data.getDescription().trim()));
        quote.setPreferredDates(blankToNull(data.getPreferredDates()) == null ? null : AppUtils.sanitizeText(data.getPreferredDates().trim()));
        quote.setServiceTime(homeVisit ? "homeService" : "visitBarber");
        quote.setNoOfPeople(data.getNoOfPeople() == null ? 1 : data.getNoOfPeople());
        quote.setStatus(QuoteEntity.REQUESTED);
        quote.setCreatedAt(now);
        quoteRepo.save(quote);
        audit(userId, "CUSTOMER", "REQUEST_QUOTE", quote.getQuoteRef(), "Quote requested");
        notify(quote, true, "New quote request",
                "A client asked for a price on " + service.get().getName() + ": \"" + shorten(quote.getDescription(), 160) + "\"");
        return ok(response, "Quote request sent", view(quote, false, now));
    }

    @Transactional(rollbackFor = Exception.class)
    public BaseResponse offer(String stylerId, QuoteOfferData data) {
        BaseResponse response = new BaseResponse(true);
        Optional<QuoteEntity> found = quoteRepo.findByQuoteRefForUpdate(data.getQuoteRef());
        if (found.isEmpty() || !found.get().getStylerId().equals(stylerId)) {
            return Responses.errorResponse(response, "Quote not found");
        }
        QuoteEntity quote = found.get();
        LocalDateTime now = now();
        if (!quote.isOpen(now)) {
            return Responses.errorResponse(response, "This quote is closed and can't be priced");
        }
        long cents = MoneyUtils.centsFromPrice(data.getPrice());
        if (cents < MIN_PRICE_CENTS || cents > MAX_PRICE_CENTS) {
            return Responses.errorResponse(response, "Quote between $" + MoneyUtils.moneyCents(MIN_PRICE_CENTS)
                    + " and $" + MoneyUtils.moneyCents(MAX_PRICE_CENTS));
        }
        int duration = data.getDurationMinutes();
        if (duration < MIN_SERVICE_DURATION_MINUTES || duration > MAX_SERVICE_DURATION_MINUTES || duration % SLOT_MINUTES != 0) {
            return Responses.errorResponse(response, "Duration must be between " + MIN_SERVICE_DURATION_MINUTES
                    + " minutes and " + (MAX_SERVICE_DURATION_MINUTES / 60) + " hours, in 15-minute steps");
        }
        boolean revised = QuoteEntity.QUOTED.equals(quote.getStatus());
        quote.setPriceCents(cents);
        quote.setDurationMinutes(duration);
        quote.setStylistNote(blankToNull(data.getNote()) == null ? null : AppUtils.sanitizeText(data.getNote().trim()));
        quote.setValidUntil(now.plusDays(data.getValidDays() == null ? DEFAULT_VALID_DAYS : data.getValidDays()));
        quote.setQuotedAt(now);
        quote.setStatus(QuoteEntity.QUOTED);
        quoteRepo.save(quote);
        audit(stylerId, "STYLER", "OFFER_QUOTE", quote.getQuoteRef(), "Quoted $" + MoneyUtils.moneyCents(cents));
        notify(quote, false, revised ? "Your quote was updated" : "Your quote is ready",
                stylistName(stylerId) + " quoted $" + MoneyUtils.moneyCents(cents) + " for " + durationText(duration)
                        + ". Book it before it expires.");
        return ok(response, revised ? "Quote updated" : "Quote sent", view(quote, true, now));
    }

    /** The customer closes their request or turns the price down. */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse decline(String userId, String quoteRef) {
        BaseResponse response = new BaseResponse(true);
        Optional<QuoteEntity> found = quoteRepo.findByQuoteRefForUpdate(quoteRef);
        if (found.isEmpty() || !found.get().getUserId().equals(userId)) {
            return Responses.errorResponse(response, "Quote not found");
        }
        QuoteEntity quote = found.get();
        LocalDateTime now = now();
        if (!quote.isOpen(now)) {
            return Responses.errorResponse(response, "This quote is already closed");
        }
        close(quote, QuoteEntity.DECLINED, now);
        audit(userId, "CUSTOMER", "DECLINE_QUOTE", quoteRef, "Quote declined");
        notify(quote, true, "Quote closed by the client", "The client closed their quote request.");
        return ok(response, "Quote closed", view(quote, false, now));
    }

    /** The stylist turns the request down or takes back a price not yet booked. */
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse withdraw(String stylerId, String quoteRef) {
        BaseResponse response = new BaseResponse(true);
        Optional<QuoteEntity> found = quoteRepo.findByQuoteRefForUpdate(quoteRef);
        if (found.isEmpty() || !found.get().getStylerId().equals(stylerId)) {
            return Responses.errorResponse(response, "Quote not found");
        }
        QuoteEntity quote = found.get();
        LocalDateTime now = now();
        if (!quote.isOpen(now)) {
            return Responses.errorResponse(response, "This quote is already closed");
        }
        close(quote, QuoteEntity.WITHDRAWN, now);
        audit(stylerId, "STYLER", "WITHDRAW_QUOTE", quoteRef, "Quote withdrawn");
        notify(quote, false, "Quote request closed",
                stylistName(stylerId) + " can't take this one on. You can book one of their listed services or ask someone else.");
        return ok(response, "Quote closed", view(quote, true, now));
    }

    public BaseResponse listForCustomer(String userId) {
        LocalDateTime now = now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (QuoteEntity quote : quoteRepo.findByUserIdOrderByCreatedAtDesc(userId)) {
            items.add(view(quote, false, now));
        }
        return ok(new BaseResponse(true), SUCCESS_MESSAGE, items);
    }

    /** {items, waiting}: waiting counts requests the stylist has not priced yet. */
    public BaseResponse listForStylist(String stylerId) {
        LocalDateTime now = now();
        List<Map<String, Object>> items = new ArrayList<>();
        long waiting = 0;
        for (QuoteEntity quote : quoteRepo.findByStylerIdOrderByCreatedAtDesc(stylerId)) {
            items.add(view(quote, true, now));
            if (QuoteEntity.REQUESTED.equals(quote.effectiveStatus(now))) waiting++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("waiting", waiting);
        return ok(new BaseResponse(true), SUCCESS_MESSAGE, data);
    }

    /**
     * The quote a booking pays for, locked until the booking commits so it is
     * booked once and not re-priced mid-checkout. Throws with a message for
     * the customer when it can't be booked.
     */
    public QuoteEntity forCheckout(String quoteRef, String userId, String stylerId) {
        return bookable(quoteRepo.findByQuoteRefForUpdate(quoteRef), userId, stylerId);
    }

    /** The same checks as checkout, without the lock, for pricing the checkout screen. */
    public QuoteEntity forEstimate(String quoteRef, String userId, String stylerId) {
        return bookable(quoteRepo.findByQuoteRef(quoteRef), userId, stylerId);
    }

    private QuoteEntity bookable(Optional<QuoteEntity> found, String userId, String stylerId) {
        QuoteEntity quote = found
                .filter(q -> q.getUserId().equals(userId) && q.getStylerId().equals(stylerId))
                .orElseThrow(() -> new IllegalArgumentException("Quote not found"));
        String status = quote.effectiveStatus(now());
        if (QuoteEntity.EXPIRED.equals(status)) {
            throw new IllegalArgumentException("This quote has expired. Ask the professional for a new one");
        }
        if (!QuoteEntity.QUOTED.equals(status) || quote.getPriceCents() == null || quote.getDurationMinutes() == null) {
            throw new IllegalArgumentException("This quote can't be booked");
        }
        return quote;
    }

    /** Called inside the booking's transaction once the appointment row exists. */
    public void markAccepted(QuoteEntity quote, String appointmentId) {
        quote.setAppointmentId(appointmentId);
        close(quote, QuoteEntity.ACCEPTED, now());
    }

    private void close(QuoteEntity quote, String status, LocalDateTime now) {
        quote.setStatus(status);
        quote.setClosedAt(now);
        quoteRepo.save(quote);
    }

    /** What either side sees. The stylist sees the client's first name only; the customer sees the stylist's business. */
    Map<String, Object> view(QuoteEntity quote, boolean forStylist, LocalDateTime now) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("quoteRef", quote.getQuoteRef());
        item.put("status", quote.effectiveStatus(now));
        item.put("stylerId", quote.getStylerId());
        item.put("subServiceId", quote.getSubServiceId());
        item.put("serviceName", serviceName(quote));
        item.put("description", quote.getDescription());
        item.put("preferredDates", quote.getPreferredDates());
        item.put("serviceTime", quote.getServiceTime());
        item.put("noOfPeople", quote.getNoOfPeople());
        item.put("price", quote.getPriceCents() == null ? null : MoneyUtils.moneyCents(quote.getPriceCents()));
        item.put("durationMinutes", quote.getDurationMinutes());
        item.put("note", quote.getStylistNote());
        item.put("validUntil", utc(quote.getValidUntil()));
        item.put("createdAt", utc(quote.getCreatedAt()));
        item.put("quotedAt", utc(quote.getQuotedAt()));
        item.put("appointmentId", quote.getAppointmentId());
        if (forStylist) {
            item.put("clientName", customerRecords.findByUserId(quote.getUserId())
                    .map(UserEntity::getFirstname).filter(name -> name != null && !name.isBlank()).orElse("Client"));
        } else {
            Optional<StylerEntity> styler = stylerRecords.findByStylerId(quote.getStylerId());
            item.put("stylistName", stylistName(styler));
            item.put("stylistPhoto", styler.map(StylerEntity::getProfileImageUrl).orElse(null));
        }
        return item;
    }

    private String serviceName(QuoteEntity quote) {
        try {
            return stylerRecords.findSubService(quote.getStylerId(), Long.parseLong(quote.getSubServiceId()))
                    .map(SubServiceEntity::getName).orElse("Custom service");
        } catch (NumberFormatException ex) {
            return "Custom service";
        }
    }

    private String stylistName(String stylerId) {
        return stylistName(stylerRecords.findByStylerId(stylerId));
    }

    private static String stylistName(Optional<StylerEntity> styler) {
        return styler.map(s -> s.getBusinessName() != null && !s.getBusinessName().isBlank() ? s.getBusinessName()
                : (Objects.toString(s.getFirstname(), "") + " " + Objects.toString(s.getLastname(), "")).trim())
                .filter(name -> !name.isBlank()).orElse("Your professional");
    }

    static String durationText(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) return rest + " min";
        return hours + (hours == 1 ? " hour" : " hours") + (rest == 0 ? "" : " " + rest + " min");
    }

    private void notify(QuoteEntity quote, boolean toStylist, String title, String message) {
        if (notificationService != null) {
            notificationService.notifyQuote(quote.getUserId(), quote.getStylerId(), toStylist, title, message);
        }
    }

    private void audit(String actor, String role, String action, String quoteRef, String detail) {
        if (auditService != null) auditService.audit(actor, role, action, "QUOTE", quoteRef, detail);
    }

    private String newRef() {
        AppUtils utils = new AppUtils();
        for (int attempt = 0; attempt < 5; attempt++) {
            String ref = "Q" + utils.randomAlphanumeric(7).toUpperCase(Locale.ROOT);
            if (quoteRepo.findByQuoteRef(ref).isEmpty()) return ref;
        }
        throw new IllegalStateException("Could not create a quote id");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    private static String utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    private static String shorten(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BaseResponse ok(BaseResponse response, String message, Object data) {
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(message);
        response.setData(data);
        return response;
    }
}

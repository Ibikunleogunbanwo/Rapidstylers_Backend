package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.booking.service.BookingSearch;
import com.macrotel.rapidstylers.catalog.service.CatalogRecords;
import com.macrotel.rapidstylers.common.entity.AuditLogEntity;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.payment.entity.RefundEntity;
import com.macrotel.rapidstylers.payment.service.RefundRecords;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * The admin's view of every booking: a filtered, paged list with totals, one
 * booking in full, and a CSV of the same filtered list.
 *
 * <p>Picture a ledger the admin can narrow down. The appointment rows live
 * behind booking's counter ({@link AppointmentRecords}); this class asks for
 * the matching rows, then labels each one with the customer, the stylist and
 * the service from their own domains' counters. Reads change nothing; the CSV
 * export is written to the audit log because it takes customer data off the site.
 *
 * <p>Money is CAD, as stored on the booking. Dates are the appointment's own
 * calendar date, which is the stylist's local date ({@code stylistTimeZone}).
 */
@Service
public class AdminBookingService {

    /** Booking status codes as stored, with the names the admin sees. */
    public static final Map<String, String> STATUS_NAMES = Map.of(
            "1", "PENDING", "3", "ACCEPTED", "0", "COMPLETED", "2", "DECLINED", "4", "CANCELLED");

    static final int MAX_PAGE_SIZE = 100;
    /** The most rows one export or one set of totals reads, so a wide filter cannot exhaust memory. */
    static final int MAX_EXPORT_ROWS = 20000;

    @Autowired AppointmentRecords appointmentRecords;
    @Autowired StylerRecords stylerRecords;
    @Autowired CustomerRecords customerRecords;
    @Autowired CatalogRecords catalogRecords;
    @Autowired RefundRecords refundRecords;
    @Autowired AuditService auditService;

    /** The admin's filters as they arrive on the request; every one is optional. */
    @lombok.Data
    public static class Filters {
        private String status;
        private String from;
        private String to;
        private String serviceTypeId;
        private String subServiceId;
        private String province;
        private String stylerId;
        private String userId;
        private String appointmentId;
        private String paymentStatus;
    }

    /** {items, page, size, total, totalPages, totals}. Page counts from 0; size is capped at 100. */
    public BaseResponse list(Filters filters, Integer page, Integer size) {
        BaseResponse response = new BaseResponse(true);
        BookingSearch search;
        try {
            search = toSearch(filters);
        } catch (IllegalArgumentException bad) {
            response.setMessage(bad.getMessage());
            return response;
        }
        int pageNumber = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? 25 : Math.min(size, MAX_PAGE_SIZE);
        Page<BookAppointmentEntity> found = appointmentRecords.search(search,
                PageRequest.of(pageNumber, pageSize, BookingSearch.NEWEST_FIRST));
        Labels labels = new Labels();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", found.getContent().stream().map(b -> row(b, labels)).collect(Collectors.toList()));
        data.put("page", pageNumber);
        data.put("size", pageSize);
        data.put("total", found.getTotalElements());
        data.put("totalPages", found.getTotalPages());
        data.put("totals", totals(limited(appointmentRecords.searchAll(search))));
        return ok(response, data);
    }

    /** One booking in full: the list row, plus refunds and its audit history. */
    public BaseResponse detail(String appointmentId) {
        BaseResponse response = new BaseResponse(true);
        Optional<BookAppointmentEntity> found = blank(appointmentId) ? Optional.empty()
                : appointmentRecords.findByAppointmentId(appointmentId.trim());
        if (found.isEmpty()) {
            response.setStatusCode("404");
            response.setMessage("Booking not found");
            return response;
        }
        BookAppointmentEntity b = found.get();
        Map<String, Object> data = row(b, new Labels());
        data.put("serviceAddress", b.getServiceAddress());
        data.put("noOfPeople", b.getNoOfPeople());
        data.put("travelDistanceKm", b.getTravelDistanceKm());
        data.put("billableTravelKm", b.getBillableTravelKm());
        data.put("commissionPercent", b.getCommissionPercent());
        data.put("paymentIntentId", b.getPaymentIntentId());
        data.put("paymentFailureCode", b.getPaymentFailureCode());
        data.put("stripeTransferId", b.getStripeTransferId());
        data.put("stylerNote", b.getStylerNote());
        data.put("completedAt", b.getCompletedAt());
        data.put("serviceConfirmedAt", b.getServiceConfirmedAt());
        data.put("payoutDueAt", b.getPayoutDueAt());
        data.put("payoutHoldReason", b.getPayoutHoldReason());
        data.put("disputeReason", b.getServiceDisputeReason());
        data.put("disputedAt", b.getServiceDisputedAt());
        data.put("disputeResolutionNote", b.getServiceDisputeResolutionNote());
        data.put("disputeResolvedAt", b.getServiceDisputeResolvedAt());
        List<RefundEntity> refunds = refundRecords.findByAppointmentId(b.getAppointmentId());
        data.put("refunds", refunds);
        data.put("refundedTotal", refunds.stream()
                .filter(r -> "COMPLETED".equalsIgnoreCase(r.getStatus()))
                .map(r -> money(r.getAmount())).reduce(BigDecimal.ZERO, BigDecimal::add));
        List<AuditLogEntity> history = auditService
                .searchLogs(null, null, b.getAppointmentId(), PageRequest.of(0, 100)).getContent();
        data.put("history", history);
        return ok(response, data);
    }

    /**
     * The filtered list as CSV, newest first, at most {@value #MAX_EXPORT_ROWS}
     * rows. Returns null when a filter is invalid (the controller answers 400).
     */
    public String exportCsv(Filters filters, String adminId) {
        BookingSearch search;
        try {
            search = toSearch(filters);
        } catch (IllegalArgumentException bad) {
            return null;
        }
        List<BookAppointmentEntity> rows = limited(appointmentRecords.searchAll(search));
        Labels labels = new Labels();
        StringBuilder csv = new StringBuilder();
        csv.append("appointmentId,appointmentDate,startTime,endTime,stylistTimeZone,status,paymentStatus,")
           .append("price,travelFee,paymentAmount,platformFee,stylistShare,customerName,customerEmail,")
           .append("stylistName,stylistProvince,serviceType,service,disputeStatus,createdAt\n");
        for (BookAppointmentEntity b : rows) {
            Map<String, Object> r = row(b, labels);
            @SuppressWarnings("unchecked") Map<String, Object> customer = (Map<String, Object>) r.get("customer");
            @SuppressWarnings("unchecked") Map<String, Object> stylist = (Map<String, Object>) r.get("stylist");
            @SuppressWarnings("unchecked") Map<String, Object> service = (Map<String, Object>) r.get("service");
            csv.append(cells(r.get("appointmentId"), r.get("appointmentDate"), r.get("startTime"), r.get("endTime"),
                    stylist.get("timeZone"), r.get("status"), r.get("paymentStatus"), r.get("price"),
                    r.get("travelFee"), r.get("paymentAmount"), r.get("platformFee"), r.get("stylistShare"),
                    customer.get("name"), customer.get("email"), stylist.get("name"), stylist.get("province"),
                    service.get("serviceType"), service.get("name"), r.get("disputeStatus"), r.get("createdAt")))
               .append('\n');
        }
        auditService.audit(adminId, "ADMIN", "ADMIN_BOOKINGS_EXPORT", "APPOINTMENT", "export",
                "rows=" + rows.size() + " filters=" + describe(filters));
        return csv.toString();
    }

    // ── filters ─────────────────────────────────────────────────────────────

    BookingSearch toSearch(Filters f) {
        BookingSearch s = new BookingSearch();
        if (f == null) return s;
        if (!blank(f.status)) {
            List<String> codes = new ArrayList<>();
            for (String part : f.status.split(",")) {
                String code = statusCode(part.trim());
                if (code == null) throw new IllegalArgumentException("Unknown status: " + part.trim());
                codes.add(code);
            }
            s.statuses = codes;
        }
        s.from = date(f.from, "from");
        s.to = date(f.to, "to");
        if (s.from != null && s.to != null && s.from.isAfter(s.to)) {
            throw new IllegalArgumentException("'from' must be on or before 'to'");
        }
        s.subServiceId = trim(f.subServiceId);
        s.stylerId = trim(f.stylerId);
        s.userId = trim(f.userId);
        s.appointmentId = trim(f.appointmentId);
        s.paymentStatus = blank(f.paymentStatus) ? null : f.paymentStatus.trim().toUpperCase(Locale.ROOT);
        String province = trim(f.province);
        String serviceTypeId = trim(f.serviceTypeId);
        if (province != null || serviceTypeId != null) {
            // The booking row does not hold these; narrow to the stylists who match.
            s.stylerIds = stylerRecords.findAll().stream()
                    .filter(st -> province == null || province.equalsIgnoreCase(trim(st.getProvince())))
                    .filter(st -> serviceTypeId == null || serviceTypeId.equals(trim(st.getServiceTypeId())))
                    .map(StylerEntity::getStylerId)
                    .collect(Collectors.toSet());
        }
        return s;
    }

    /** Accepts a stored code ("1") or its name ("pending"). */
    static String statusCode(String value) {
        if (STATUS_NAMES.containsKey(value)) return value;
        for (Map.Entry<String, String> e : STATUS_NAMES.entrySet()) {
            if (e.getValue().equalsIgnoreCase(value)) return e.getKey();
        }
        return null;
    }

    private static LocalDate date(String value, String name) {
        if (blank(value)) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("'" + name + "' must be a date like 2026-10-31");
        }
    }

    // ── rows and totals ─────────────────────────────────────────────────────

    /** Looks each customer, stylist and service up once per request. */
    private class Labels {
        final Map<String, Optional<UserEntity>> customers = new HashMap<>();
        final Map<String, Optional<StylerEntity>> stylists = new HashMap<>();
        final Map<String, Optional<SubServiceEntity>> services = new HashMap<>();
        final Map<String, String> serviceTypes = new HashMap<>();

        Optional<UserEntity> customer(String id) {
            return id == null ? Optional.empty() : customers.computeIfAbsent(id, customerRecords::findByUserId);
        }

        Optional<StylerEntity> stylist(String id) {
            return id == null ? Optional.empty() : stylists.computeIfAbsent(id, stylerRecords::findByStylerId);
        }

        Optional<SubServiceEntity> service(String id) {
            if (id == null) return Optional.empty();
            return services.computeIfAbsent(id, key -> {
                try {
                    return stylerRecords.findSubServiceById(Long.parseLong(key.trim()));
                } catch (NumberFormatException ex) {
                    return Optional.empty();
                }
            });
        }

        String serviceType(String id) {
            if (blank(id)) return null;
            return serviceTypes.computeIfAbsent(id, key -> {
                try {
                    return catalogRecords.findServiceById(Long.parseLong(key.trim()))
                            .map(t -> t.getServiceName()).orElse(null);
                } catch (NumberFormatException ex) {
                    return null;
                }
            });
        }
    }

    private Map<String, Object> row(BookAppointmentEntity b, Labels labels) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("appointmentId", b.getAppointmentId());
        r.put("appointmentDate", b.getAppointmentDateValue() != null ? b.getAppointmentDateValue().toString() : b.getAppointmentDate());
        r.put("startTime", b.getAppointmentStartTime());
        r.put("endTime", b.getAppointmentEndTime());
        r.put("durationMinutes", b.getDurationMinutes());
        r.put("status", STATUS_NAMES.getOrDefault(b.getStatus(), b.getStatus()));
        r.put("statusCode", b.getStatus());
        r.put("paymentStatus", b.getPaymentStatus());
        r.put("currency", "CAD");
        r.put("price", money(b.getPrice()));
        r.put("travelFee", money(b.getTravelFee()));
        r.put("paymentAmount", blank(b.getPaymentAmount()) ? null : money(b.getPaymentAmount()));
        r.put("platformFee", cents(b.getPlatformFeeCents()));
        r.put("stylistShare", cents(b.getStylistShareCents()));
        r.put("disputeStatus", b.getServiceDisputeStatus());
        r.put("createdAt", b.getCreatedAt());

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("userId", b.getUserId());
        labels.customer(b.getUserId()).ifPresent(u -> {
            customer.put("name", fullName(u.getFirstname(), u.getLastname()));
            customer.put("email", u.getEmailAddress());
        });
        r.put("customer", customer);

        Map<String, Object> stylist = new LinkedHashMap<>();
        stylist.put("stylerId", b.getStylerId());
        Optional<StylerEntity> st = labels.stylist(b.getStylerId());
        st.ifPresent(s -> {
            stylist.put("name", blank(s.getBusinessName()) ? fullName(s.getFirstname(), s.getLastname()) : s.getBusinessName());
            stylist.put("province", s.getProvince());
            stylist.put("timeZone", s.getTimeZone());
        });
        r.put("stylist", stylist);

        Map<String, Object> service = new LinkedHashMap<>();
        service.put("subServiceId", b.getSubServiceId());
        labels.service(b.getSubServiceId()).ifPresent(sv -> service.put("name", sv.getName()));
        String typeId = st.map(StylerEntity::getServiceTypeId).orElse(null);
        service.put("serviceTypeId", typeId);
        service.put("serviceType", labels.serviceType(typeId));
        r.put("service", service);
        return r;
    }

    /** Totals over the whole filtered set, not just the page shown. */
    static Map<String, Object> totals(List<BookAppointmentEntity> rows) {
        BigDecimal bookingValue = BigDecimal.ZERO;
        BigDecimal platformFees = BigDecimal.ZERO;
        BigDecimal stylistEarnings = BigDecimal.ZERO;
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (BookAppointmentEntity b : rows) {
            bookingValue = bookingValue.add(blank(b.getPaymentAmount()) ? money(b.getPrice()) : money(b.getPaymentAmount()));
            if (b.getPlatformFeeCents() != null) platformFees = platformFees.add(cents(b.getPlatformFeeCents()));
            if (b.getStylistShareCents() != null) stylistEarnings = stylistEarnings.add(cents(b.getStylistShareCents()));
            byStatus.merge(STATUS_NAMES.getOrDefault(b.getStatus(), String.valueOf(b.getStatus())), 1L, Long::sum);
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("count", rows.size());
        totals.put("currency", "CAD");
        totals.put("bookingValue", bookingValue);
        totals.put("platformFees", platformFees);
        totals.put("stylistEarnings", stylistEarnings);
        totals.put("byStatus", byStatus);
        return totals;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<BookAppointmentEntity> limited(List<BookAppointmentEntity> rows) {
        return rows.size() > MAX_EXPORT_ROWS ? rows.subList(0, MAX_EXPORT_ROWS) : rows;
    }

    /** Amounts are stored as text like "1,250.00"; unreadable text counts as zero. */
    static BigDecimal money(String text) {
        if (blank(text)) return BigDecimal.ZERO;
        try {
            return new BigDecimal(text.replace(",", "").replace("$", "").trim()).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal cents(Long cents) {
        return cents == null ? null : BigDecimal.valueOf(cents, 2);
    }

    /**
     * One CSV line. Quotes every cell, and puts an apostrophe before a cell that
     * starts with = + - @, so a spreadsheet opens it as text rather than a formula.
     */
    static String cells(Object... values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) line.append(',');
            String v = values[i] == null ? "" : String.valueOf(values[i]);
            if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) v = "'" + v;
            line.append('"').append(v.replace("\"", "\"\"")).append('"');
        }
        return line.toString();
    }

    private static String describe(Filters f) {
        if (f == null) return "{}";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("status", f.status); m.put("from", f.from); m.put("to", f.to);
        m.put("serviceTypeId", f.serviceTypeId); m.put("subServiceId", f.subServiceId);
        m.put("province", f.province); m.put("stylerId", f.stylerId); m.put("userId", f.userId);
        m.put("paymentStatus", f.paymentStatus);
        m.values().removeIf(AdminBookingService::blank);
        return m.toString();
    }

    private static String fullName(String first, String last) {
        String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return name.isEmpty() ? null : name;
    }

    private static BaseResponse ok(BaseResponse response, Object data) {
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return response;
    }

    private static String trim(String value) {
        return blank(value) ? null : value.trim();
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

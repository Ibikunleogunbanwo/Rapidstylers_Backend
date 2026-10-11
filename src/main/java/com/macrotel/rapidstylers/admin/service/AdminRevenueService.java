package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.booking.service.BookingSearch;
import com.macrotel.rapidstylers.catalog.service.CatalogRecords;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.payment.entity.RefundEntity;
import com.macrotel.rapidstylers.payment.service.RefundRecords;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.admin.service.AdminBookingService.blank;
import static com.macrotel.rapidstylers.admin.service.AdminBookingService.money;
import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Revenue for a date range: what was charged, what was refunded, what the
 * platform kept and what the stylists earned, with a trend line, breakdowns by
 * service type and province, and the same figures for the period just before.
 *
 * <p>Picture a till roll. Every time a customer's card is charged
 * ({@code paymentCapturedAt}), a line is printed on that day; every completed
 * refund prints a minus line on the day it went through. A report reads the
 * lines between two dates, counting days in the reporting time zone
 * (Edmonton by default, {@code app.time-zone}). Money is CAD.
 */
@Service
public class AdminRevenueService {

    /** The longest range one report covers (about three years). */
    static final int MAX_DAYS = 1100;
    private static final DateTimeFormatter REFUND_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired AppointmentRecords appointmentRecords;
    @Autowired RefundRecords refundRecords;
    @Autowired StylerRecords stylerRecords;
    @Autowired CatalogRecords catalogRecords;

    @Value("${app.time-zone:America/Edmonton}")
    String reportTimeZone;

    /**
     * {@code from}/{@code to} are inclusive calendar dates (default: the last 30
     * days); {@code groupBy} is day, week (starting Monday) or month (default:
     * day up to 62 days, week up to a year, month beyond).
     */
    public BaseResponse report(String fromText, String toText, String groupByText) {
        BaseResponse response = new BaseResponse(true);
        ZoneId zone = zone();
        Range range;
        try {
            range = range(fromText, toText, groupByText, zone);
        } catch (IllegalArgumentException bad) {
            response.setMessage(bad.getMessage());
            return response;
        }
        long days = ChronoUnit.DAYS.between(range.from, range.to) + 1;
        LocalDate previousFrom = range.from.minusDays(days);
        LocalDate previousTo = range.from.minusDays(1);

        // One read covers both periods: charges since the previous period began.
        BookingSearch search = new BookingSearch();
        search.capturedFrom = utcStartOf(previousFrom, zone);
        search.capturedBefore = utcStartOf(range.to.plusDays(1), zone);
        List<Line> current = new ArrayList<>();
        List<Line> previous = new ArrayList<>();
        for (BookAppointmentEntity b : appointmentRecords.searchAll(search)) {
            Line line = charge(b, zone);
            (line.day.isBefore(range.from) ? previous : current).add(line);
        }
        for (RefundEntity r : refundRecords.findCompleted()) {
            Line line = refund(r, zone);
            if (line == null) continue;
            if (!line.day.isBefore(range.from) && !line.day.isAfter(range.to)) current.add(line);
            else if (!line.day.isBefore(previousFrom) && !line.day.isAfter(previousTo)) previous.add(line);
        }

        Map<String, Object> summary = sum(current);
        Map<String, Object> before = sum(previous);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("currency", "CAD");
        data.put("timeZone", zone.getId());
        data.put("from", range.from.toString());
        data.put("to", range.to.toString());
        data.put("groupBy", range.groupBy);
        data.put("summary", summary);
        Map<String, Object> previousPeriod = new LinkedHashMap<>();
        previousPeriod.put("from", previousFrom.toString());
        previousPeriod.put("to", previousTo.toString());
        previousPeriod.put("summary", before);
        data.put("previousPeriod", previousPeriod);
        data.put("changePercent", change(summary, before));
        data.put("trend", trend(current, range));
        Map<String, StylerEntity> stylists = new HashMap<>();
        Function<String, StylerEntity> stylist = id -> id == null ? null
                : stylists.computeIfAbsent(id, key -> stylerRecords.findByStylerId(key).orElse(null));
        Map<String, String> typeNames = new HashMap<>();
        data.put("byServiceType", breakdown(current, l -> {
            StylerEntity s = stylist.apply(l.stylerId);
            String typeId = s == null || blank(s.getServiceTypeId()) ? null : s.getServiceTypeId().trim();
            return new String[]{typeId, typeId == null ? "Unknown" : typeNames.computeIfAbsent(typeId, this::serviceTypeName)};
        }));
        data.put("byProvince", breakdown(current, l -> {
            StylerEntity s = stylist.apply(l.stylerId);
            String province = s == null || blank(s.getProvince()) ? null : s.getProvince().trim();
            return new String[]{province, province == null ? "Unknown" : province};
        }));
        data.put("definitions", definitions(zone));

        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return response;
    }

    /** The trend rows of a report as CSV, or null when the dates are invalid. */
    @SuppressWarnings("unchecked")
    public String trendCsv(String fromText, String toText, String groupByText) {
        BaseResponse report = report(fromText, toText, groupByText);
        if (!SUCCESS_STATUS_CODE.equals(report.getStatusCode())) return null;
        Map<String, Object> data = (Map<String, Object>) report.getData();
        StringBuilder csv = new StringBuilder("periodStart,periodEnd,charges,capturedValue,refundCount,refunds,netValue,platformRevenue,stylistEarnings\n");
        for (Map<String, Object> row : (List<Map<String, Object>>) data.get("trend")) {
            csv.append(AdminBookingService.cells(row.get("periodStart"), row.get("periodEnd"), row.get("charges"),
                    row.get("capturedValue"), row.get("refundCount"), row.get("refunds"), row.get("netValue"),
                    row.get("platformRevenue"), row.get("stylistEarnings"))).append('\n');
        }
        return csv.toString();
    }

    // ── lines on the till roll ──────────────────────────────────────────────

    /** One charge (positive) or refund (negative), on its reporting day. */
    static final class Line {
        LocalDate day;
        String stylerId;
        boolean refund;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal platform = BigDecimal.ZERO;
        BigDecimal stylist = BigDecimal.ZERO;
    }

    private static Line charge(BookAppointmentEntity b, ZoneId zone) {
        Line l = new Line();
        l.day = b.getPaymentCapturedAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate();
        l.stylerId = b.getStylerId();
        l.amount = money(blank(b.getPaymentAmount()) ? b.getPrice() : b.getPaymentAmount());
        if (b.getPlatformFeeCents() != null) {
            l.platform = BigDecimal.valueOf(b.getPlatformFeeCents(), 2);
        } else if (b.getCommissionPercent() != null) {
            // Fee not settled yet (it is fixed at completion): use the rate snapped at booking.
            l.platform = l.amount.multiply(BigDecimal.valueOf(b.getCommissionPercent()))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        l.stylist = b.getStylistShareCents() != null ? BigDecimal.valueOf(b.getStylistShareCents(), 2)
                : l.amount.subtract(l.platform);
        return l;
    }

    /** Refund times are written in the server's own clock; unreadable ones are skipped. */
    private static Line refund(RefundEntity r, ZoneId zone) {
        if (blank(r.getCompletedAt())) return null;
        try {
            Line l = new Line();
            l.refund = true;
            l.day = LocalDateTime.parse(r.getCompletedAt().trim(), REFUND_TIME)
                    .atZone(ZoneId.systemDefault()).withZoneSameInstant(zone).toLocalDate();
            l.amount = money(r.getAmount());
            return l;
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    // ── adding up ───────────────────────────────────────────────────────────

    static Map<String, Object> sum(List<Line> lines) {
        long charges = 0, refundCount = 0;
        BigDecimal captured = BigDecimal.ZERO, refunds = BigDecimal.ZERO, platform = BigDecimal.ZERO, stylist = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l.refund) {
                refundCount++;
                refunds = refunds.add(l.amount);
            } else {
                charges++;
                captured = captured.add(l.amount);
                platform = platform.add(l.platform);
                stylist = stylist.add(l.stylist);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("charges", charges);
        m.put("capturedValue", captured);
        m.put("refundCount", refundCount);
        m.put("refunds", refunds);
        m.put("netValue", captured.subtract(refunds));
        m.put("platformRevenue", platform);
        m.put("stylistEarnings", stylist);
        m.put("averageCharge", charges == 0 ? BigDecimal.ZERO.setScale(2)
                : captured.divide(BigDecimal.valueOf(charges), 2, RoundingMode.HALF_UP));
        return m;
    }

    /** Percent change per figure; null where the previous period was zero. */
    static Map<String, Object> change(Map<String, Object> now, Map<String, Object> before) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String key : now.keySet()) {
            BigDecimal a = new BigDecimal(String.valueOf(now.get(key)));
            BigDecimal b = new BigDecimal(String.valueOf(before.get(key)));
            m.put(key, b.signum() == 0 ? null
                    : a.subtract(b).multiply(BigDecimal.valueOf(100)).divide(b.abs(), 1, RoundingMode.HALF_UP));
        }
        return m;
    }

    private static List<Map<String, Object>> trend(List<Line> lines, Range range) {
        Map<LocalDate, List<Line>> byBucket = new HashMap<>();
        for (Line l : lines) byBucket.computeIfAbsent(bucketStart(l.day, range.groupBy), k -> new ArrayList<>()).add(l);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LocalDate start = bucketStart(range.from, range.groupBy); !start.isAfter(range.to); start = next(start, range.groupBy)) {
            Map<String, Object> row = new LinkedHashMap<>();
            // The first and last buckets are clipped to the range.
            row.put("periodStart", (start.isBefore(range.from) ? range.from : start).toString());
            LocalDate end = next(start, range.groupBy).minusDays(1);
            row.put("periodEnd", (end.isAfter(range.to) ? range.to : end).toString());
            row.putAll(sum(byBucket.getOrDefault(start, List.of())));
            rows.add(row);
        }
        return rows;
    }

    /** Charges grouped by a key (id, label); refunds are not split by group. Largest first. */
    private static List<Map<String, Object>> breakdown(List<Line> lines, Function<Line, String[]> keyOf) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, List<Line>> groups = new LinkedHashMap<>();
        for (Line l : lines) {
            if (l.refund) continue;
            String[] key = keyOf.apply(l);
            String id = key[0] == null ? "" : key[0];
            labels.put(id, key[1]);
            groups.computeIfAbsent(id, k -> new ArrayList<>()).add(l);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Line>> g : groups.entrySet()) {
            Map<String, Object> s = sum(g.getValue());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", g.getKey().isEmpty() ? null : g.getKey());
            row.put("name", labels.get(g.getKey()));
            row.put("charges", s.get("charges"));
            row.put("capturedValue", s.get("capturedValue"));
            row.put("platformRevenue", s.get("platformRevenue"));
            row.put("stylistEarnings", s.get("stylistEarnings"));
            rows.add(row);
        }
        rows.sort((x, y) -> ((BigDecimal) y.get("capturedValue")).compareTo((BigDecimal) x.get("capturedValue")));
        return rows;
    }

    private static Map<String, String> definitions(ZoneId zone) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("currency", "All amounts are Canadian dollars (CAD).");
        d.put("days", "Days run midnight to midnight in " + zone.getId() + ".");
        d.put("capturedValue", "Money charged to customers' cards, counted on the day the charge went through.");
        d.put("refunds", "Completed refunds, counted on the day the refund went through.");
        d.put("netValue", "capturedValue minus refunds.");
        d.put("platformRevenue", "The platform's commission on those charges. Before a booking is completed this is "
                + "estimated from the commission rate saved at booking; refunds are not taken off it.");
        d.put("stylistEarnings", "The stylists' share of those charges (charge minus commission).");
        d.put("previousPeriod", "The same number of days immediately before 'from'.");
        return d;
    }

    // ── dates ───────────────────────────────────────────────────────────────

    static final class Range {
        LocalDate from;
        LocalDate to;
        String groupBy;
    }

    static Range range(String fromText, String toText, String groupByText, ZoneId zone) {
        Range r = new Range();
        LocalDate today = LocalDate.now(zone);
        r.to = blank(toText) ? today : date(toText, "to");
        r.from = blank(fromText) ? r.to.minusDays(29) : date(fromText, "from");
        if (r.from.isAfter(r.to)) throw new IllegalArgumentException("'from' must be on or before 'to'");
        long days = ChronoUnit.DAYS.between(r.from, r.to) + 1;
        if (days > MAX_DAYS) throw new IllegalArgumentException("Pick a range of at most " + MAX_DAYS + " days");
        if (blank(groupByText)) {
            r.groupBy = days <= 62 ? "day" : days <= 366 ? "week" : "month";
        } else {
            r.groupBy = groupByText.trim().toLowerCase(Locale.ROOT);
            if (!List.of("day", "week", "month").contains(r.groupBy)) {
                throw new IllegalArgumentException("groupBy must be day, week or month");
            }
            if ("day".equals(r.groupBy) && days > 366) {
                throw new IllegalArgumentException("Group by week or month for ranges over a year");
            }
        }
        return r;
    }

    private static LocalDate date(String value, String name) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("'" + name + "' must be a date like 2026-10-31");
        }
    }

    static LocalDate bucketStart(LocalDate day, String groupBy) {
        if ("week".equals(groupBy)) return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if ("month".equals(groupBy)) return day.withDayOfMonth(1);
        return day;
    }

    static LocalDate next(LocalDate start, String groupBy) {
        if ("week".equals(groupBy)) return start.plusWeeks(1);
        if ("month".equals(groupBy)) return start.plusMonths(1);
        return start.plusDays(1);
    }

    private static LocalDateTime utcStartOf(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(blank(reportTimeZone) ? "America/Edmonton" : reportTimeZone.trim());
        } catch (Exception ex) {
            return ZoneId.of("America/Edmonton");
        }
    }

    private String serviceTypeName(String id) {
        try {
            return catalogRecords.findServiceById(Long.parseLong(id)).map(t -> t.getServiceName()).orElse("Unknown");
        } catch (NumberFormatException ex) {
            return "Unknown";
        }
    }
}

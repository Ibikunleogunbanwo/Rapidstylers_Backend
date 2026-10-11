package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.common.service.FunnelService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * The sign-up funnel report: how many people reached each step in a date
 * range, what share moved on from the step before, a trend, and the same
 * counts for the period just before. Uses the same dates, zone and grouping
 * rules as the revenue report ({@link AdminRevenueService#range}).
 */
@Service
public class AdminFunnelService {

    @Autowired FunnelService funnelService;

    /** role: CUSTOMER, STYLIST or ALL (default). Visits count for every role, since nobody has picked one yet. */
    public BaseResponse report(String fromText, String toText, String groupByText, String roleText) {
        BaseResponse response = new BaseResponse(true);
        ZoneId zone = funnelService.reportZone();
        String role = roleText == null || roleText.isBlank() ? "ALL" : roleText.trim().toUpperCase(Locale.ROOT);
        if ("STYLER".equals(role)) role = "STYLIST";
        if (!List.of("ALL", "CUSTOMER", "STYLIST").contains(role)) {
            response.setMessage("role must be CUSTOMER, STYLIST or ALL");
            return response;
        }
        AdminRevenueService.Range range;
        try {
            range = AdminRevenueService.range(fromText, toText, groupByText, zone);
        } catch (IllegalArgumentException bad) {
            response.setMessage(bad.getMessage());
            return response;
        }
        long days = ChronoUnit.DAYS.between(range.from, range.to) + 1;
        LocalDate previousFrom = range.from.minusDays(days);
        LocalDate previousTo = range.from.minusDays(1);

        // Each event: step, role, person, day.
        List<String[]> current = new ArrayList<>();
        List<String[]> previous = new ArrayList<>();
        for (Object[] e : funnelService.eventsBetween(previousFrom, range.to)) {
            String step = (String) e[0];
            String eventRole = (String) e[1];
            if (!"ALL".equals(role) && !FunnelService.VISITED.equals(step) && !role.equals(eventRole)) continue;
            String[] row = {step, eventRole, (String) e[2], (String) e[3]};
            (LocalDate.parse(row[3]).isBefore(range.from) ? previous : current).add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", range.from.toString());
        data.put("to", range.to.toString());
        data.put("timeZone", zone.getId());
        data.put("role", role);
        data.put("groupBy", range.groupBy);
        data.put("steps", steps(current));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("from", previousFrom.toString());
        before.put("to", previousTo.toString());
        before.put("steps", steps(previous));
        data.put("previousPeriod", before);
        data.put("trend", trend(current, range));
        data.put("definitions", definitions(zone));
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return response;
    }

    /** People per step (each person once, however many days they came back). */
    static Map<String, Long> counts(List<String[]> events) {
        Map<String, Set<String>> people = new HashMap<>();
        for (String[] e : events) people.computeIfAbsent(e[0], k -> new HashSet<>()).add(e[1] + ":" + e[2]);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String step : FunnelService.STEPS) counts.put(step, (long) people.getOrDefault(step, Set.of()).size());
        return counts;
    }

    static List<Map<String, Object>> steps(List<String[]> events) {
        Map<String, Long> counts = counts(events);
        long first = counts.get(FunnelService.STEPS.get(0));
        List<Map<String, Object>> rows = new ArrayList<>();
        Long before = null;
        for (String step : FunnelService.STEPS) {
            long n = counts.get(step);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("step", step);
            row.put("count", n);
            row.put("conversionFromPrevious", before == null ? null : percent(n, before));
            row.put("conversionFromStart", percent(n, first));
            rows.add(row);
            before = n;
        }
        return rows;
    }

    private static List<Map<String, Object>> trend(List<String[]> events, AdminRevenueService.Range range) {
        Map<LocalDate, List<String[]>> byBucket = new HashMap<>();
        for (String[] e : events) {
            byBucket.computeIfAbsent(AdminRevenueService.bucketStart(LocalDate.parse(e[3]), range.groupBy),
                    k -> new ArrayList<>()).add(e);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LocalDate start = AdminRevenueService.bucketStart(range.from, range.groupBy); !start.isAfter(range.to);
             start = AdminRevenueService.next(start, range.groupBy)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("periodStart", (start.isBefore(range.from) ? range.from : start).toString());
            LocalDate end = AdminRevenueService.next(start, range.groupBy).minusDays(1);
            row.put("periodEnd", (end.isAfter(range.to) ? range.to : end).toString());
            row.putAll(counts(byBucket.getOrDefault(start, List.of())));
            rows.add(row);
        }
        return rows;
    }

    private static BigDecimal percent(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    private static Map<String, String> definitions(ZoneId zone) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("days", "Days run midnight to midnight in " + zone.getId() + "; each step counts on the day it happened.");
        d.put("people", "Each person counts once per step in a period, however often they repeat it.");
        d.put(FunnelService.VISITED, "A browser opened the site (one count per browser per day). Visits are counted for every role.");
        d.put(FunnelService.SIGNUP_STARTED, "A sign-up email code was sent to a new email address.");
        d.put(FunnelService.EMAIL_VERIFIED, "That email code was entered correctly.");
        d.put(FunnelService.ACCOUNT_CREATED, "An account was created. Google sign-ups skip the two email steps.");
        d.put(FunnelService.FIRST_BOOKING, "A customer made their first booking request.");
        d.put("conversion", "conversionFromPrevious is this step as a share of the step before; conversionFromStart is a share of visits. "
                + "These are counts in the same period, not the same people followed through, so they can pass 100%.");
        return d;
    }
}

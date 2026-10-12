package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.security.access.Roles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;

/**
 * What a MANAGER may see of the bookings, customers and dashboard answers: no
 * money. Prices, fees, shares, totals in currency, refunds, payment and
 * transfer ids, payout details and the audit history are dropped; status words
 * (status, paymentStatus, disputeStatus) stay. Owners get the full answer.
 *
 * Each shape is an allowlist, so a money field added to a service later stays
 * owner-only until someone lists it here on purpose.
 */
public final class ManagerView {

    private ManagerView() {
    }

    static final Set<String> BOOKING_ROW = Set.of(
            "appointmentId", "appointmentDate", "startTime", "endTime", "durationMinutes",
            "status", "statusCode", "paymentStatus", "disputeStatus", "createdAt",
            "customer", "stylist", "service");

    static final Set<String> BOOKING_DETAIL = union(BOOKING_ROW, Set.of(
            "serviceAddress", "noOfPeople", "travelDistanceKm", "billableTravelKm", "stylerNote",
            "completedAt", "serviceConfirmedAt", "disputeReason", "disputedAt",
            "disputeResolutionNote", "disputeResolvedAt"));

    static final Set<String> TOTALS = Set.of("count", "byStatus");

    static final Set<String> PAGE = Set.of("page", "size", "total", "totalPages");

    static final Set<String> CUSTOMER_ROW = Set.of(
            "userId", "firstname", "lastname", "emailAddress", "phoneNumber", "status",
            "registrationMethod", "dateRegistered", "bookingCount", "lastAppointmentDate");

    static final Set<String> CUSTOMER_DETAIL = union(CUSTOMER_ROW, Set.of(
            "address", "state", "country", "termsAcceptedAt"));

    static final Set<String> KPIS = Set.of(
            "customers", "stylists", "approvedStylists", "appointments", "pendingAppointments",
            "completedAppointments", "openSupportTickets", "reviews");

    /** True when the signed-in caller is a manager. */
    public static boolean isManager(HttpServletRequest request) {
        return Roles.MANAGER.equals(CurrentAccount.role(request));
    }

    /** /admin/bookings: rows and the count-only totals. */
    public static BaseResponse bookingList(BaseResponse response) {
        Map<String, Object> data = asMap(response.getData());
        if (data == null) return response;
        Map<String, Object> out = keep(data, PAGE);
        out.put("items", rows(data.get("items"), BOOKING_ROW));
        if (data.get("totals") != null) out.put("totals", keep(asMap(data.get("totals")), TOTALS));
        response.setData(out);
        return response;
    }

    /** /admin/booking_detail: the booking without money, refunds or audit history. */
    public static BaseResponse bookingDetail(BaseResponse response) {
        Map<String, Object> data = asMap(response.getData());
        if (data != null) response.setData(keep(data, BOOKING_DETAIL));
        return response;
    }

    /** /admin/customers: the account rows (they carry no money today; the allowlist keeps it so). */
    public static BaseResponse customerList(BaseResponse response) {
        Map<String, Object> data = asMap(response.getData());
        if (data == null) return response;
        Map<String, Object> out = keep(data, PAGE);
        out.put("items", rows(data.get("items"), CUSTOMER_ROW));
        response.setData(out);
        return response;
    }

    /** /admin/customer_detail: the account and its bookings, without money or audit history. */
    public static BaseResponse customerDetail(BaseResponse response) {
        Map<String, Object> data = asMap(response.getData());
        if (data == null) return response;
        Map<String, Object> out = keep(data, CUSTOMER_DETAIL);
        out.put("bookings", rows(data.get("bookings"), BOOKING_ROW));
        if (data.get("bookingTotals") != null) out.put("bookingTotals", keep(asMap(data.get("bookingTotals")), TOTALS));
        response.setData(out);
        return response;
    }

    /** /admin/kpis: the counts. */
    public static BaseResponse kpis(BaseResponse response) {
        Map<String, Object> data = asMap(response.getData());
        if (data != null) response.setData(keep(data, KPIS));
        return response;
    }

    private static List<Map<String, Object>> rows(Object items, Set<String> allowed) {
        if (!(items instanceof List)) return List.of();
        return ((List<?>) items).stream().map(ManagerView::asMap)
                .filter(row -> row != null)
                .map(row -> keep(row, allowed))
                .collect(Collectors.toList());
    }

    private static Map<String, Object> keep(Map<String, Object> source, Set<String> allowed) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (source == null) return out;
        source.forEach((key, value) -> {
            if (allowed.contains(key)) out.put(key, value);
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.HashSet<String> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}

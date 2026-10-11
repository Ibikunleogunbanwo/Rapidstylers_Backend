package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.auth.service.RefreshTokenService;
import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * The admin's view of customer accounts: search, one account in full with its
 * booking history, and the one change an admin may make, suspending or
 * reinstating an account.
 *
 * <p>Suspension reuses the account's existing status: sign-in (email and
 * Google) already admits only status "0", so a suspended customer cannot sign
 * back in, and suspending also signs them out everywhere at once. Every
 * suspension and reinstatement needs a reason and lands in the audit log.
 */
@Service
public class AdminCustomerService {

    public static final String ACTIVE = "0";
    public static final String SUSPENDED = "SUSPENDED";

    @Autowired CustomerRecords customerRecords;
    @Autowired AppointmentRecords appointmentRecords;
    @Autowired AdminBookingService adminBookingService;
    @Autowired RefreshTokenService refreshTokenService;
    @Autowired AuditService auditService;

    /** {items, page, size, total, totalPages}. {@code q} matches email, name, phone or id; status is ACTIVE or SUSPENDED. */
    public BaseResponse search(String q, String status, Integer page, Integer size) {
        BaseResponse response = new BaseResponse(true);
        String storedStatus = null;
        if (status != null && !status.isBlank()) {
            storedStatus = storedStatus(status.trim());
            if (storedStatus == null) {
                response.setMessage("status must be ACTIVE or SUSPENDED");
                return response;
            }
        }
        int pageNumber = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? 25 : Math.min(size, 100);
        Page<UserEntity> found = customerRecords.search(q, storedStatus,
                PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Order.desc("id"))));
        Map<String, Object[]> stats = appointmentRecords.bookingStatsForUsers(
                found.getContent().stream().map(UserEntity::getUserId).collect(Collectors.toList()));
        List<Map<String, Object>> items = found.getContent().stream().map(u -> {
            Map<String, Object> row = summary(u);
            Object[] s = stats.get(u.getUserId());
            row.put("bookingCount", s == null ? 0L : s[0]);
            row.put("lastAppointmentDate", s == null || s[1] == null ? null : s[1].toString());
            return row;
        }).collect(Collectors.toList());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("page", pageNumber);
        data.put("size", pageSize);
        data.put("total", found.getTotalElements());
        data.put("totalPages", found.getTotalPages());
        return ok(response, data);
    }

    /** One account: details, booking totals, the latest 50 bookings and its audit history. */
    public BaseResponse detail(String userId) {
        BaseResponse response = new BaseResponse(true);
        Optional<UserEntity> found = userId == null || userId.isBlank() ? Optional.empty()
                : customerRecords.findByUserId(userId.trim());
        if (found.isEmpty()) {
            response.setStatusCode("404");
            response.setMessage("Customer not found");
            return response;
        }
        UserEntity u = found.get();
        Map<String, Object> data = summary(u);
        data.put("address", u.getAddress());
        data.put("state", u.getState());
        data.put("country", u.getCountry());
        data.put("termsAcceptedAt", u.getTermsAcceptedAt());
        AdminBookingService.Filters mine = new AdminBookingService.Filters();
        mine.setUserId(u.getUserId());
        @SuppressWarnings("unchecked")
        Map<String, Object> bookings = (Map<String, Object>) adminBookingService.list(mine, 0, 50).getData();
        data.put("bookings", bookings.get("items"));
        data.put("bookingTotals", bookings.get("totals"));
        data.put("history", auditService.searchLogs(null, null, u.getUserId(), PageRequest.of(0, 100)).getContent());
        return ok(response, data);
    }

    /**
     * Suspends ({@code suspend=true}) or reinstates a customer. A reason is
     * required; it is stored in the audit log with the admin's id.
     */
    @Transactional
    public BaseResponse setSuspended(String adminId, String userId, boolean suspend, String reason) {
        BaseResponse response = new BaseResponse(true);
        if (reason == null || reason.isBlank()) {
            response.setMessage("A reason is required");
            return response;
        }
        if (reason.length() > 500) {
            response.setMessage("Keep the reason under 500 characters");
            return response;
        }
        Optional<UserEntity> found = userId == null || userId.isBlank() ? Optional.empty()
                : customerRecords.findByUserId(userId.trim());
        if (found.isEmpty()) {
            response.setStatusCode("404");
            response.setMessage("Customer not found");
            return response;
        }
        UserEntity u = found.get();
        String target = suspend ? SUSPENDED : ACTIVE;
        if (target.equals(u.getStatus())) {
            response.setMessage(suspend ? "This account is already suspended" : "This account is already active");
            return response;
        }
        String before = u.getStatus();
        u.setStatus(target);
        customerRecords.save(u);
        if (suspend) {
            // Signs them out everywhere: refresh tokens deleted, access tokens refused.
            refreshTokenService.revokeAllForAccount(u.getUserId(), "CUSTOMER");
        }
        auditService.audit(adminId, "ADMIN", suspend ? "ADMIN_CUSTOMER_SUSPENDED" : "ADMIN_CUSTOMER_REINSTATED",
                "CUSTOMER", u.getUserId(), "from=" + before + " reason=" + reason.trim());
        return ok(response, summary(u));
    }

    private static Map<String, Object> summary(UserEntity u) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("userId", u.getUserId());
        row.put("firstname", u.getFirstname());
        row.put("lastname", u.getLastname());
        row.put("emailAddress", u.getEmailAddress());
        row.put("phoneNumber", u.getPhoneNumber());
        row.put("status", statusName(u.getStatus()));
        row.put("registrationMethod", u.getRegistrationMethod());
        row.put("dateRegistered", u.getInsertedDt());
        return row;
    }

    static String statusName(String stored) {
        if (ACTIVE.equals(stored)) return "ACTIVE";
        if (SUSPENDED.equals(stored)) return "SUSPENDED";
        return stored;
    }

    private static String storedStatus(String name) {
        if ("ACTIVE".equalsIgnoreCase(name) || ACTIVE.equals(name)) return ACTIVE;
        if (SUSPENDED.equalsIgnoreCase(name)) return SUSPENDED;
        return null;
    }

    private static BaseResponse ok(BaseResponse response, Object data) {
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return response;
    }
}

package com.macrotel.rapidstylers.admin.controller;

import com.macrotel.rapidstylers.admin.service.AdminBookingService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin bookings: the filtered list with totals, one booking in full, and a
 * CSV export. Filters (all optional, the same on list and export): status
 * (codes or names, comma separated), from/to (appointment date, yyyy-MM-dd),
 * serviceTypeId, subServiceId, province, stylerId, userId, appointmentId,
 * paymentStatus.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class AdminBookingController {

    @Autowired
    AdminBookingService adminBookingService;

    @GetMapping("/admin/bookings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> bookings(@ModelAttribute AdminBookingService.Filters filters,
                                                 @RequestParam(value = "page", required = false) Integer page,
                                                 @RequestParam(value = "size", required = false) Integer size,
                                                 HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminBookingService.list(filters, page, size));
    }

    @GetMapping("/admin/booking_detail")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> booking(@RequestParam("appointmentId") String appointmentId,
                                                HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminBookingService.detail(appointmentId));
    }

    @GetMapping("/admin/bookings_export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> export(@ModelAttribute AdminBookingService.Filters filters, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        String csv = adminBookingService.exportCsv(filters, adminId);
        if (csv == null) {
            // Same filters as the list, so the list call says which one is wrong.
            return ApiResponses.respond(adminBookingService.list(filters, 0, 1));
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"rapidstylers-bookings-" + LocalDate.now() + ".csv\"")
                .body(csv);
    }
}

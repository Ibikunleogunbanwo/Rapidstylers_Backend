package com.macrotel.rapidstylers.admin.controller;

import com.macrotel.rapidstylers.admin.service.AdminRevenueService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin revenue: totals, trend, breakdowns and previous-period comparison for
 * a date range (from/to as yyyy-MM-dd, groupBy day|week|month, all optional),
 * and the trend as CSV.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class AdminRevenueController {

    @Autowired
    AdminRevenueService adminRevenueService;

    @GetMapping("/admin/revenue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> revenue(@RequestParam(value = "from", required = false) String from,
                                                @RequestParam(value = "to", required = false) String to,
                                                @RequestParam(value = "groupBy", required = false) String groupBy,
                                                HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminRevenueService.report(from, to, groupBy));
    }

    @GetMapping("/admin/revenue_export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> export(@RequestParam(value = "from", required = false) String from,
                                    @RequestParam(value = "to", required = false) String to,
                                    @RequestParam(value = "groupBy", required = false) String groupBy,
                                    HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        String csv = adminRevenueService.trendCsv(from, to, groupBy);
        if (csv == null) return ApiResponses.respond(adminRevenueService.report(from, to, groupBy));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"rapidstylers-revenue.csv\"")
                .body(csv);
    }
}

package com.macrotel.rapidstylers.admin.controller;

import com.macrotel.rapidstylers.admin.service.AdminCustomerService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Admin customers: search, one account with its bookings, and audited suspend/reinstate. */
@RestController
@RequestMapping("/rapid_stylers")
public class AdminCustomerController {

    @Autowired
    AdminCustomerService adminCustomerService;

    @GetMapping("/admin/customers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> customers(@RequestParam(value = "q", required = false) String q,
                                                  @RequestParam(value = "status", required = false) String status,
                                                  @RequestParam(value = "page", required = false) Integer page,
                                                  @RequestParam(value = "size", required = false) Integer size,
                                                  HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminCustomerService.search(q, status, page, size));
    }

    @GetMapping("/admin/customer_detail")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> customer(@RequestParam("userId") String userId, HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminCustomerService.detail(userId));
    }

    /** Body: {userId, reason}. */
    @PostMapping("/admin/suspend_customer")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> suspend(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminCustomerService.setSuspended(adminId, body.get("userId"), true, body.get("reason")));
    }

    /** Body: {userId, reason}. */
    @PostMapping("/admin/reinstate_customer")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> reinstate(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(adminCustomerService.setSuspended(adminId, body.get("userId"), false, body.get("reason")));
    }
}

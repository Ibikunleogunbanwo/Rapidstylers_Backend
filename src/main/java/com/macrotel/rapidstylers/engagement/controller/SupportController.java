package com.macrotel.rapidstylers.engagement.controller;

import com.macrotel.rapidstylers.engagement.pojo.SupportTicketActionData;
import com.macrotel.rapidstylers.engagement.pojo.SupportTicketData;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.controller.ApiResponses;
import com.macrotel.rapidstylers.engagement.service.SupportService;
import com.macrotel.rapidstylers.pojo.*;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Support tickets for customers, stylists and admins. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class SupportController {
    @Autowired
    SupportService supportService;

    @PostMapping("/support_tickets")
    public ResponseEntity<BaseResponse> createSupportTicket(@Valid @RequestBody SupportTicketData data, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(supportService.createSupportTicket(accountId, data));
    }

    @GetMapping("/support_tickets")
    public ResponseEntity<BaseResponse> supportTickets(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(supportService.listSupportTickets(accountId));
    }

    @GetMapping("/admin/support_tickets")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminSupportTickets(HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(supportService.listAllSupportTickets());
    }

    @PostMapping("/admin/update_support_ticket")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminUpdateSupportTicket(@Valid @RequestBody SupportTicketActionData data, HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        return ApiResponses.respond(supportService.updateSupportTicket(data, adminId));
    }
}

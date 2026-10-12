package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.engagement.entity.SupportTicketEntity;
import com.macrotel.rapidstylers.engagement.pojo.SupportTicketActionData;
import com.macrotel.rapidstylers.engagement.pojo.SupportTicketData;
import com.macrotel.rapidstylers.engagement.repo.SupportTicketRepo;
import com.macrotel.rapidstylers.security.access.Roles;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Locale;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Support tickets raised by customers and stylists and handled by admins.
 */
@Service
public class SupportService {
    private static final Logger LOG = Logger.getLogger(SupportService.class.getName());
    @Autowired
    CustomerRecords customerRecords;
    @Autowired
    StylerRecords stylerRecords;
    @Autowired
    SupportTicketRepo supportTicketRepo;
    // Shared audit-log / ops-alert sink (see simplification-plan.md).
    @Autowired
    AuditService auditService = new AuditService();

    public BaseResponse createSupportTicket(String userId, SupportTicketData data){
        return createSupportTicket(userId, Roles.CUSTOMER, data);
    }

    /** A ticket from the signed-in customer or stylist, attributed to that account. */
    public BaseResponse createSupportTicket(String accountId, String role, SupportTicketData data){
        BaseResponse response = new BaseResponse(true);
        try {
            String senderRole = senderRole(role);
            if(!accountExists(accountId, senderRole)) return Responses.errorResponse(response, "Invalid User Id");
            SupportTicketEntity ticket = supportTicketRepo.save(new SupportTicketEntity(accountId, senderRole,
                    AppUtils.sanitizeText(data.getSubject().trim()), AppUtils.sanitizeText(data.getMessage().trim())));
            auditService.audit(accountId, SENDER_STYLIST.equals(senderRole) ? Roles.STYLER : Roles.CUSTOMER, "CREATE_SUPPORT_TICKET", "SUPPORT_TICKET", String.valueOf(ticket.getId()), ticket.getSubject());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Support ticket created");
            response.setData(ticket.getId());
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse listSupportTickets(String userId){
        return listSupportTickets(userId, Roles.CUSTOMER);
    }

    /** The signed-in customer's or stylist's own tickets, newest activity first. */
    public BaseResponse listSupportTickets(String accountId, String role){
        BaseResponse response = new BaseResponse(true);
        try {
            if(!accountExists(accountId, senderRole(role))) return Responses.errorResponse(response, "Invalid User Id");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(supportTicketRepo.findByUserIdOrderByUpdatedAtDesc(accountId));
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    /**
     * Every ticket, newest first, each with who sent it: the ticket's own
     * fields plus ticketId, senderName, senderEmail and senderRole.
     */
    public BaseResponse listAllSupportTickets(){
        BaseResponse response = new BaseResponse(true);
        try {
            List<SupportTicketEntity> tickets = new ArrayList<>(supportTicketRepo.findAll());
            tickets.sort(Comparator.comparing(SupportTicketEntity::getId, Comparator.nullsLast(Comparator.reverseOrder())));
            Map<String, String[]> senders = new HashMap<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            for(SupportTicketEntity ticket : tickets){
                String role = senderRole(ticket.getSenderRole());
                String[] sender = senders.computeIfAbsent(role + ":" + ticket.getUserId(), key -> sender(ticket.getUserId(), role));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", ticket.getId());
                row.put("userId", ticket.getUserId());
                row.put("subject", ticket.getSubject());
                row.put("message", ticket.getMessage());
                row.put("status", ticket.getStatus());
                row.put("adminResponse", ticket.getAdminResponse());
                row.put("createdAt", ticket.getCreatedAt());
                row.put("updatedAt", ticket.getUpdatedAt());
                row.put("senderRole", role);
                row.put("ticketId", ticket.getId());
                row.put("senderName", sender[0]);
                row.put("senderEmail", sender[1]);
                rows.add(row);
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(rows);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    /** A ticket's sender role as the admin list reports it: STYLIST or CUSTOMER. */
    static final String SENDER_STYLIST = "STYLIST";
    static final String SENDER_CUSTOMER = "CUSTOMER";

    /**
     * STYLIST for a stylist (the STYLER token role, or a stored STYLIST), CUSTOMER
     * for anything else, which covers every ticket from before stylists could write one.
     */
    private static String senderRole(String role){
        return Roles.STYLER.equalsIgnoreCase(role) || SENDER_STYLIST.equalsIgnoreCase(role) ? SENDER_STYLIST : SENDER_CUSTOMER;
    }

    private boolean accountExists(String accountId, String senderRole){
        return SENDER_STYLIST.equals(senderRole) ? stylerRecords.findByStylerId(accountId).isPresent()
                : customerRecords.findByUserId(accountId).isPresent();
    }

    /** {name, email} of a ticket's sender; nulls when the account is gone. */
    private String[] sender(String accountId, String senderRole){
        if(SENDER_STYLIST.equals(senderRole)){
            return stylerRecords.findByStylerId(accountId)
                    .map(s -> new String[]{fullName(s.getFirstname(), s.getLastname()), s.getEmailAddress()})
                    .orElse(new String[]{null, null});
        }
        return customerRecords.findByUserId(accountId)
                .map(u -> new String[]{fullName(u.getFirstname(), u.getLastname()), u.getEmailAddress()})
                .orElse(new String[]{null, null});
    }

    private static String fullName(String first, String last){
        String name = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
        return name.isEmpty() ? null : name;
    }

    public BaseResponse updateSupportTicket(SupportTicketActionData data, String adminId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<SupportTicketEntity> ticketOpt = supportTicketRepo.findById(data.getTicketId());
            String status = data.getStatus().trim().toUpperCase(Locale.ROOT);
            if(ticketOpt.isEmpty() || !Arrays.asList("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED").contains(status)) return Responses.errorResponse(response, "Invalid support ticket or status");
            SupportTicketEntity ticket = ticketOpt.get();
            ticket.setStatus(status);
            ticket.setAdminResponse(AppUtils.sanitizeText(data.getAdminResponse()));
            ticket.setUpdatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            supportTicketRepo.save(ticket);
            auditService.audit(adminId, "ADMIN", "UPDATE_SUPPORT_TICKET", "SUPPORT_TICKET", String.valueOf(ticket.getId()), status);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Support ticket updated");
            response.setData(ticket);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }
}

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
    SupportTicketRepo supportTicketRepo;
    // Shared audit-log / ops-alert sink (see simplification-plan.md).
    @Autowired
    AuditService auditService = new AuditService();

    public BaseResponse createSupportTicket(String userId, SupportTicketData data){
        BaseResponse response = new BaseResponse(true);
        try {
            if(customerRecords.findByUserId(userId).isEmpty()) return Responses.errorResponse(response, "Invalid User Id");
            SupportTicketEntity ticket = supportTicketRepo.save(new SupportTicketEntity(userId,
                    AppUtils.sanitizeText(data.getSubject().trim()), AppUtils.sanitizeText(data.getMessage().trim())));
            auditService.audit(userId, "CUSTOMER", "CREATE_SUPPORT_TICKET", "SUPPORT_TICKET", String.valueOf(ticket.getId()), ticket.getSubject());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Support ticket created");
            response.setData(ticket.getId());
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse listSupportTickets(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(customerRecords.findByUserId(userId).isEmpty()) return Responses.errorResponse(response, "Invalid User Id");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(supportTicketRepo.findByUserIdOrderByUpdatedAtDesc(userId));
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse listAllSupportTickets(){
        BaseResponse response = new BaseResponse(true);
        try {
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(supportTicketRepo.findAll());
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
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

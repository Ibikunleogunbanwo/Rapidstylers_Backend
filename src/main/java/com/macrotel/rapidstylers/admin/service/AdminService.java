package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.auth.service.AuthRecords;
import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.engagement.service.EngagementRecords;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.styler.service.StylerEligibility;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import com.stripe.model.Event;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Admin dashboard tools: KPIs, audit log, cache stats, test email, recovery campaigns and failed outbox events.
 */
@Service
public class AdminService {
    private static final Logger LOG = Logger.getLogger(AdminService.class.getName());
    @Autowired
    AuthRecords authRecords;
    @Autowired
    EmailConfig emailConfig;
    @Autowired
    CustomerRecords customerRecords;
    @Autowired
    StylerRecords stylerRecords;
    @Autowired
    EngagementRecords engagementRecords;
    @Autowired
    AppointmentRecords appointmentRecords;
    @Autowired
    OutboxEventService outboxEventService;
    @Autowired
    ReadCacheService readCacheService;
    // Shared audit-log / ops-alert sink (see simplification-plan.md).
    @Autowired
    AuditService auditService = new AuditService();
    @Autowired
    StylerEligibility stylerEligibility;

    /**
     * Admin recovery-campaign funnel: every email that started a sign-up, with
     * the recovery stage it reached, when the last stage email was sent, and
     * whether the sign-up eventually converted to an account.
     */
    public BaseResponse listRecoveryCampaigns(){
        BaseResponse response = new BaseResponse(true);
        try {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (Object[] r : authRecords.findRecoveryCampaigns()) {
                Map<String, Object> row = new LinkedHashMap<>();
                String email = String.valueOf(r[0]);
                int stage = r[2] == null ? 0 : ((Number) r[2]).intValue();
                row.put("email", email);
                row.put("stage", stage);
                row.put("stageLabel", stageLabel(stage));
                row.put("attemptedAt", r[1] == null ? null : String.valueOf(r[1]));
                row.put("lastSentAt", r[3] == null ? null : String.valueOf(r[3]));
                Object rawConverted = r[4];
                boolean converted = rawConverted != null
                        && (rawConverted instanceof Boolean ? (Boolean) rawConverted : ((Number) rawConverted).intValue() == 1);
                row.put("converted", converted);
                rows.add(row);
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(rows);
        } catch(Exception ex){ LOG.warning("listRecoveryCampaigns: " + ex.getMessage()); }
        return response;
    }

    private String stageLabel(int stage){
        return switch (stage) {
            case 1 -> "24h reminder";
            case 2 -> "7-day message";
            case 3 -> "14-day nudge";
            case 4 -> "1-month final";
            default -> "None";
        };
    }

    /** Admin-only: sends a test email through the same EmailConfig path used for real notifications. */
    public BaseResponse sendTestEmail(String adminId, String recipient){
        BaseResponse response = new BaseResponse(true);
        try{
            if(recipient == null || recipient.isBlank()
                    || !recipient.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")){
                return Responses.errorResponse(response, "Enter a valid recipient email address");
            }
            String result = emailConfig.sendSimpleMail(recipient, "RapidStylers test email",
                    "<h2>RapidStylers</h2><p>This is a test email sent from the admin console to verify email delivery.</p>");
            if("Mail Sent Successfully...".equals(result)){
                auditService.audit(adminId, "ADMIN", "SEND_TEST_EMAIL", "EMAIL", recipient, "sent");
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("Test email sent to " + recipient);
                response.setData(EMPTY_DATA);
            } else {
                return Responses.errorResponse(response, "Email provider rejected the send — check the backend log");
            }
        } catch(Exception ex){
            LOG.warning("Test email failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not send test email");
        }
        return response;
    }

    /** Cache-effectiveness snapshot (hits/misses/loads/evictions + per-key top) since boot. */
    public BaseResponse getCacheStats(){
        BaseResponse response = new BaseResponse(true);
        try {
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(readCacheService == null ? new LinkedHashMap<String, Object>() : readCacheService.stats());
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse getAdminKpis(){
        BaseResponse response = new BaseResponse(true);
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("customers", customerRecords.count());
            data.put("stylists", stylerRecords.count());
            data.put("approvedStylists", stylerRecords.findAll().stream().filter(stylerEligibility::isApprovedStyler).count());
            data.put("appointments", appointmentRecords.count());
            data.put("pendingAppointments", appointmentRecords.findAll().stream().filter(a -> "1".equals(a.getStatus())).count());
            data.put("completedAppointments", appointmentRecords.findAll().stream().filter(a -> "0".equals(a.getStatus())).count());
            data.put("openSupportTickets", engagementRecords.countTicketsByStatus("OPEN"));
            data.put("reviews", engagementRecords.countReviews());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse listAuditLogs(){
        BaseResponse response = new BaseResponse(true);
        try {
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(auditService.recentLogs());
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    /**
     * One page of the activity log, newest first, optionally narrowed to one
     * action, actor or record. Returns {items, page, size, total, totalPages};
     * page counts from 0 and size is capped at 200.
     */
    public BaseResponse searchAuditLogs(Integer page, Integer size, String action, String actorId, String resourceId){
        BaseResponse response = new BaseResponse(true);
        int pageNumber = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? 50 : Math.min(size, 200);
        org.springframework.data.domain.Page<com.macrotel.rapidstylers.common.entity.AuditLogEntity> found =
                auditService.searchLogs(blankToNull(action), blankToNull(actorId), blankToNull(resourceId),
                        org.springframework.data.domain.PageRequest.of(pageNumber, pageSize));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", found.getContent());
        data.put("page", pageNumber);
        data.put("size", pageSize);
        data.put("total", found.getTotalElements());
        data.put("totalPages", found.getTotalPages());
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return response;
    }

    private static String blankToNull(String value){
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** List failed outbox events for admin review. */
    public BaseResponse listFailedOutboxEvents() {
        BaseResponse response = new BaseResponse(true);
        try {
            List<com.macrotel.rapidstylers.outbox.OutboxEventEntity> failed =
                outboxEventService.failedEvents();
            List<Map<String, Object>> result = new ArrayList<>();
            for (var event : failed) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", event.getId());
                map.put("eventId", event.getEventId());
                map.put("eventType", event.getEventType() != null ? event.getEventType().name() : "UNKNOWN");
                map.put("aggregateType", event.getAggregateType());
                map.put("aggregateId", event.getAggregateId());
                map.put("status", event.getStatus().name());
                map.put("attempts", event.getAttempts());
                map.put("lastError", event.getLastError());
                map.put("createdAt", event.getCreatedAt().toString());
                result.add(map);
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        } catch (Exception ex) {
            LOG.warning("Failed to list outbox events: " + ex.getMessage());
        }
        return response;
    }

    /** Reset a failed outbox event back to PENDING for retry. */
    public BaseResponse retryFailedOutboxEvent(Long eventId, String adminId) {
        BaseResponse response = new BaseResponse(true);
        try {
            var event = outboxEventService.findEvent(eventId);
            if (event.isEmpty()) {
                return Responses.errorResponse(response, "Event not found");
            }
            var entity = event.get();
            if (entity.getStatus() != com.macrotel.rapidstylers.outbox.OutboxStatus.FAILED) {
                return Responses.errorResponse(response, "Only failed events can be retried");
            }
            entity.setStatus(com.macrotel.rapidstylers.outbox.OutboxStatus.PENDING);
            entity.setAttempts(0);
            entity.setLastError(null);
            entity.setNextAttemptAt(LocalDateTime.now());
            outboxEventService.saveEvent(entity);
            auditService.audit(adminId, "ADMIN", "RETRY_OUTBOX_EVENT", "OUTBOX_EVENT",
                    String.valueOf(eventId), "Admin retried failed outbox event");
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Event queued for retry");
            response.setData(EMPTY_DATA);
        } catch (Exception ex) {
            LOG.warning("Failed to retry outbox event: " + ex.getMessage());
        }
        return response;
    }
}

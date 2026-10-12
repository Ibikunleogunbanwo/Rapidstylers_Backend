package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.engagement.entity.NotificationEntity;
import com.macrotel.rapidstylers.engagement.repo.NotificationRepo;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * A stylist's own in-app inbox: the user_notifications rows addressed to them
 * (recipientRole STYLIST), such as admin announcements. Same item shape as the
 * customer inbox in NotificationService; every action is scoped to the signed-in stylist.
 */
@Service
public class StylerInboxService {
    private static final Logger LOG = Logger.getLogger(StylerInboxService.class.getName());
    private static final String STYLIST = NotificationEntity.RECIPIENT_STYLIST;

    // Package-private so a unit test can hand in stand-ins without a Spring context.
    @Autowired
    NotificationRepo notificationRepo;
    @Autowired
    StylerRecords stylerRecords;

    /** {items: [{id, type, title, message, read, createdAt, createdAtUtc}], unreadCount}, newest first. */
    public BaseResponse listNotifications(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(stylerRecords.findByStylerId(stylerId).isEmpty()) return Responses.errorResponse(response, "Invalid Styler Id");
            List<Map<String, Object>> items = new ArrayList<>();
            for(NotificationEntity notification : notificationRepo.findByStylerIdAndRecipientRoleOrderByCreatedAtDesc(stylerId, STYLIST)){
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", notification.getId());
                item.put("type", notification.getType());
                item.put("title", notification.getTitle());
                item.put("message", notification.getMessage());
                item.put("read", notification.isRead());
                item.put("createdAt", notification.getCreatedAt());
                item.put("createdAtUtc", NotificationTimes.utc(notification.getCreatedAt()));
                items.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("unreadCount", notificationRepo.countByStylerIdAndRecipientRoleAndReadFalse(stylerId, STYLIST));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse markRead(String stylerId, Long notificationId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<NotificationEntity> notification = own(stylerId, notificationId);
            if(notification.isEmpty()) return Responses.errorResponse(response, "Notification not found");
            notification.get().setRead(true);
            notificationRepo.save(notification.get());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Notification marked as read");
            response.setData(Collections.emptyMap());
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse markAllRead(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try {
            List<NotificationEntity> notifications = notificationRepo.findByStylerIdAndRecipientRoleOrderByCreatedAtDesc(stylerId, STYLIST);
            notifications.forEach(notification -> notification.setRead(true));
            notificationRepo.saveAll(notifications);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Notifications marked as read");
            response.setData(Collections.emptyMap());
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse delete(String stylerId, Long notificationId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<NotificationEntity> notification = own(stylerId, notificationId);
            if(notification.isEmpty()) return Responses.errorResponse(response, "Notification not found");
            notificationRepo.delete(notification.get());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Notification deleted");
            response.setData(Collections.emptyMap());
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse deleteAll(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try {
            notificationRepo.deleteAll(notificationRepo.findByStylerIdAndRecipientRoleOrderByCreatedAtDesc(stylerId, STYLIST));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Notifications deleted");
            response.setData(Collections.emptyMap());
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** The notification only when it is in this stylist's own inbox. */
    private Optional<NotificationEntity> own(String stylerId, Long notificationId){
        if(notificationId == null || stylerId == null) return Optional.empty();
        return notificationRepo.findByIdAndStylerIdAndRecipientRole(notificationId, stylerId, STYLIST);
    }
}

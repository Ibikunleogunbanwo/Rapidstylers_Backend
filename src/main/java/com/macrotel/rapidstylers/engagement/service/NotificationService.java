package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.customer.entity.SavedStylistEntity;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.SavedStylistRepo;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.engagement.entity.NotificationEntity;
import com.macrotel.rapidstylers.engagement.pojo.NotificationPreferencesData;
import com.macrotel.rapidstylers.engagement.repo.NotificationRepo;
import com.macrotel.rapidstylers.outbox.OutboxEventService;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import com.macrotel.rapidstylers.styler.service.VendorZoneResolver;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * In-app notifications and email alerts: the inbox, preferences, booking emails and 'your saved stylist changed' alerts.
 */
@Service
public class NotificationService {
    private static final Logger LOG = Logger.getLogger(NotificationService.class.getName());
    @Autowired
    EmailConfig emailConfig;
    @Autowired
    UserRepo userRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    SubServiceRepo subServiceRepo;
    @Autowired
    SavedStylistRepo savedStylistRepo;
    @Autowired
    NotificationRepo notificationRepo;
    @Autowired
    OutboxEventService outboxEventService;
    @Autowired
    com.macrotel.rapidstylers.common.service.AuditService auditService;

    /**
     * Emails both parties about an appointment event with role-appropriate
     * wording. Uses the appointment's stored user/styler ids to look up
     * current addresses and names.
     */
    public void sendAppointmentNotification(BookAppointmentEntity appointment, String eventLabel,
                                             String customerHeadline, String customerDetail,
                                             String stylerHeadline, String stylerDetail){
        recordBookingInApp(appointment, customerHeadline, customerDetail);
        try{
            if(outboxEventService != null){
                outboxEventService.appointmentNotification(appointment, eventLabel, customerHeadline,
                        customerDetail, stylerHeadline, stylerDetail);
                return;
            }
            String serviceName = "Service";
            if(appointment.getSubServiceId() != null && !appointment.getSubServiceId().isEmpty()){
                try{
                    Optional<SubServiceEntity> sub = subServiceRepo.isServiceExistById(appointment.getStylerId(), Long.parseLong(appointment.getSubServiceId()));
                    if(sub.isPresent() && sub.get().getName() != null){
                        serviceName = sub.get().getName();
                    }
                } catch (Exception ignored){}
            }
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(appointment.getStylerId());
            // The appointment's times live on the stylist's clock, so the email
            // names the zone rather than leaving a cross-province reader to
            // guess whose 2:00 PM this is.
            String zoneSuffix = VendorZoneResolver.timeSuffixForStyler(
                    stylerOpt.map(StylerEntity::getTimeZone).orElse(null),
                    stylerOpt.map(StylerEntity::getProvince).orElse(null));
            String when = (appointment.getAppointmentDate() == null ? "" : appointment.getAppointmentDate())
                    + (appointment.getArrivalTime() == null || appointment.getArrivalTime().isBlank()
                            ? "" : " at " + appointment.getArrivalTime() + zoneSuffix);
            String price = appointment.getPrice() == null ? "" : appointment.getPrice();
            String servicePrice = appointment.getServicePrice() == null ? price : appointment.getServicePrice();
            String travelFee = appointment.getTravelFee() == null ? "0.00" : appointment.getTravelFee();

            String customerName = "there";
            Optional<UserEntity> userOpt = userRepo.findByUserId(appointment.getUserId());
            if(userOpt.isPresent()){
                customerName = (userOpt.get().getFirstname() + " " + userOpt.get().getLastname()).trim();
                if(customerName.isBlank()) customerName = "there";
            }
            String stylistName = "Stylist";
            if(stylerOpt.isPresent()){
                stylistName = (stylerOpt.get().getFirstname() + " " + stylerOpt.get().getLastname()).trim();
                if(stylistName.isBlank()) stylistName = stylerOpt.get().getBusinessName();
            }

            String subject = "RapidStylers — Appointment " + eventLabel;
            String details = "<p>Service: " + serviceName + "<br>"
                    + "Date: " + when + "<br>"
                    + "Service price: $" + (servicePrice.isBlank() ? "—" : servicePrice) + "<br>"
                    + "Travel fee: $" + travelFee + "<br>"
                    + "Total: $" + (price.isBlank() ? "—" : price) + "<br>"
                    + "Appointment ref: " + appointment.getAppointmentId() + "</p>"
                    + "<p>Thank you,<br>The RapidStylers Team</p>";

            String customerEmail = userOpt.map(UserEntity::getEmailAddress).orElse(null);
            if(customerEmail != null && !customerEmail.isBlank()){
                String body = "<p>Dear " + customerName + ",</p><p><strong>" + customerHeadline + "</strong></p>"
                        + "<p>" + customerDetail + "</p>" + details;
                emailConfig.sendSimpleMail(customerEmail, subject, body);
            }
            String stylerEmail = stylerOpt.map(StylerEntity::getEmailAddress).orElse(null);
            if(stylerEmail != null && !stylerEmail.isBlank()){
                String body = "<p>Dear " + stylistName + ",</p><p><strong>" + stylerHeadline + "</strong></p>"
                        + "<p>" + stylerDetail + "</p>" + details;
                emailConfig.sendSimpleMail(stylerEmail, subject, body);
            }
        }
        catch (Exception ex){
            LOG.warning("Appointment notification mail failed: " + ex.getMessage());
        }
    }

    /**
     * Puts the customer's side of a booking event in their in-app inbox, next
     * to the email. Never blocks the booking: a failure here is only logged.
     */
    void recordBookingInApp(BookAppointmentEntity appointment, String headline, String detail){
        if(notificationRepo == null || appointment == null || appointment.getUserId() == null) return;
        try{
            String when = appointment.getAppointmentDate() == null || appointment.getAppointmentDate().isBlank() ? ""
                    : " Appointment: " + appointment.getAppointmentDate()
                    + (appointment.getArrivalTime() == null || appointment.getArrivalTime().isBlank() ? "" : " at " + appointment.getArrivalTime()) + ".";
            String message = (detail == null ? "" : detail) + when;
            if(message.length() > 1000) message = message.substring(0, 1000);
            notificationRepo.save(new NotificationEntity(appointment.getUserId(),
                    appointment.getStylerId() == null ? "" : appointment.getStylerId(), "BOOKING", headline, message));
        } catch (Exception ex){
            LOG.warning("In-app booking notification failed: " + ex.getMessage());
        }
    }

    /** Deletes one notification, only if it belongs to the authenticated customer. */
    public BaseResponse deleteNotification(String userId, Long notificationId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<NotificationEntity> notification = notificationId == null ? Optional.empty()
                    : notificationRepo.findByIdAndUserId(notificationId, userId);
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

    /** Deletes every notification of the authenticated customer. */
    public BaseResponse deleteAllNotifications(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            notificationRepo.deleteAll(notificationRepo.findByUserIdOrderByCreatedAtDesc(userId));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Notifications deleted");
            response.setData(Collections.emptyMap());
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Admin announcement: an in-app notification for one customer (by email)
     * or, with no email, for every active customer. In-app only, no email.
     */
    public BaseResponse sendAnnouncement(String adminId, String title, String message, String recipientEmail){
        BaseResponse response = new BaseResponse(true);
        try {
            String cleanTitle = title == null ? "" : title.trim();
            String cleanMessage = message == null ? "" : message.trim();
            if(cleanTitle.isEmpty() || cleanMessage.isEmpty()) return Responses.errorResponse(response, "Title and message are required");
            if(cleanTitle.length() > 120) return Responses.errorResponse(response, "Title must be 120 characters or fewer");
            if(cleanMessage.length() > 1000) return Responses.errorResponse(response, "Message must be 1000 characters or fewer");
            List<String> recipients;
            if(recipientEmail != null && !recipientEmail.isBlank()){
                Optional<UserEntity> user = userRepo.findByEmailAddress(recipientEmail.trim());
                if(user.isEmpty()) return Responses.errorResponse(response, "No customer with that email");
                recipients = List.of(user.get().getUserId());
            } else {
                recipients = userRepo.findActiveUserIds();
            }
            List<NotificationEntity> rows = new ArrayList<>();
            for(String userId : recipients){
                rows.add(new NotificationEntity(userId, "", "ANNOUNCEMENT", cleanTitle, cleanMessage));
            }
            notificationRepo.saveAll(rows);
            if(auditService != null){
                auditService.audit(adminId, "ADMIN", "ADMIN_ANNOUNCEMENT", "NOTIFICATION",
                        recipients.size() == 1 ? recipients.get(0) : "ALL",
                        "Sent \"" + cleanTitle + "\" to " + rows.size() + " customer(s)");
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Announcement sent");
            response.setData(Map.of("sent", rows.size()));
        } catch (Exception ex) {
            LOG.warning("Announcement failed: " + ex.getMessage());
        }
        return response;
    }

    /** Returns customer notifications and the unread count for the authenticated account. */
    public BaseResponse listNotifications(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(userRepo.findByUserId(userId).isEmpty()){
                return Responses.errorResponse(response, "Invalid User Id");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for(NotificationEntity notification : notificationRepo.findByUserIdOrderByCreatedAtDesc(userId)){
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", notification.getId());
                item.put("stylerId", notification.getStylerId());
                item.put("type", notification.getType());
                item.put("title", notification.getTitle());
                item.put("message", notification.getMessage());
                item.put("read", notification.isRead());
                item.put("createdAt", notification.getCreatedAt());
                result.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", result);
            data.put("unreadCount", notificationRepo.countByUserIdAndReadFalse(userId));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Marks one notification as read, scoped to the authenticated customer. */
    public BaseResponse markNotificationRead(String userId, Long notificationId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<NotificationEntity> notification = notificationRepo.findByIdAndUserId(notificationId, userId);
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

    /** Marks all notifications read for the authenticated customer. */
    public BaseResponse markAllNotificationsRead(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            List<NotificationEntity> notifications = notificationRepo.findByUserIdOrderByCreatedAtDesc(userId);
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

    /** Reads and updates saved-stylist notification preferences for one customer. */
    public BaseResponse getNotificationPreferences(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<UserEntity> user = userRepo.findByUserId(userId);
            if(user.isEmpty()) return Responses.errorResponse(response, "Invalid User Id");
            Map<String, Boolean> data = new LinkedHashMap<>();
            data.put("availability", !Boolean.FALSE.equals(user.get().getNotifySavedAvailability()));
            data.put("price", !Boolean.FALSE.equals(user.get().getNotifySavedPrice()));
            data.put("verification", !Boolean.FALSE.equals(user.get().getNotifySavedVerification()));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateNotificationPreferences(String userId, NotificationPreferencesData data){
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<UserEntity> user = userRepo.findByUserId(userId);
            if(user.isEmpty()) return Responses.errorResponse(response, "Invalid User Id");
            UserEntity entity = user.get();
            if(data.getAvailability() != null) entity.setNotifySavedAvailability(data.getAvailability());
            if(data.getPrice() != null) entity.setNotifySavedPrice(data.getPrice());
            if(data.getVerification() != null) entity.setNotifySavedVerification(data.getVerification());
            userRepo.save(entity);
            return getNotificationPreferences(userId);
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Creates an in-app event and sends optional email for every saved customer. */
    public void notifySavedCustomers(String stylerId, String type, String title, String message){
        if(notificationRepo == null || savedStylistRepo == null) return;
        try {
            List<SavedStylistEntity> allSaved = savedStylistRepo.findAll();
            for(SavedStylistEntity saved : allSaved){
                if(!stylerId.equals(saved.getStylerId())) continue;
                Optional<UserEntity> user = userRepo.findByUserId(saved.getUserId());
                if(user.isEmpty()) continue;
                notificationRepo.save(new NotificationEntity(saved.getUserId(), stylerId, type, title, message));
                boolean emailEnabled = ("AVAILABILITY".equals(type) && !Boolean.FALSE.equals(user.get().getNotifySavedAvailability()))
                        || ("PRICE".equals(type) && !Boolean.FALSE.equals(user.get().getNotifySavedPrice()))
                        || ("VERIFICATION".equals(type) && !Boolean.FALSE.equals(user.get().getNotifySavedVerification()));
                if(emailEnabled && user.get().getEmailAddress() != null && !user.get().getEmailAddress().isBlank()){
                    try {
                        emailConfig.sendSimpleMail(user.get().getEmailAddress(), title, "Dear "
                                + user.get().getFirstname() + ",<br><br>" + message
                                + "<br><br>The RapidStylers Team");
                    } catch (Exception mailEx) {
                        LOG.warning("Saved stylist notification email failed: " + mailEx.getMessage());
                    }
                }
            }
        } catch (Exception ex) {
            LOG.warning("Saved stylist notification failed: " + ex.getMessage());
        }
    }
}

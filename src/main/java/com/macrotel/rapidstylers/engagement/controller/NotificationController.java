package com.macrotel.rapidstylers.engagement.controller;

import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.engagement.pojo.NotificationPreferencesData;
import com.macrotel.rapidstylers.engagement.service.NotificationService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * In-app notifications and notification preferences. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class NotificationController {
    @Autowired
    NotificationService notificationService;

    @GetMapping("/notifications")
    public ResponseEntity<BaseResponse> notifications(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        BaseResponse baseResponse = notificationService.listNotifications(accountId);
        return ApiResponses.respond(baseResponse);
    }

    @PostMapping("/notifications/read")
    public ResponseEntity<BaseResponse> markNotificationRead(@RequestBody Map<String, Long> body, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        BaseResponse baseResponse = notificationService.markNotificationRead(accountId, body.get("notificationId"));
        return ApiResponses.respond(baseResponse);
    }

    @PostMapping("/notifications/read_all")
    public ResponseEntity<BaseResponse> markAllNotificationsRead(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        BaseResponse baseResponse = notificationService.markAllNotificationsRead(accountId);
        return ApiResponses.respond(baseResponse);
    }

    @PostMapping("/notifications/delete")
    public ResponseEntity<BaseResponse> deleteNotification(@RequestBody Map<String, Long> body, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(notificationService.deleteNotification(accountId, body.get("notificationId")));
    }

    @PostMapping("/notifications/delete_all")
    public ResponseEntity<BaseResponse> deleteAllNotifications(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(notificationService.deleteAllNotifications(accountId));
    }

    @GetMapping("/notification_preferences")
    public ResponseEntity<BaseResponse> notificationPreferences(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(notificationService.getNotificationPreferences(accountId));
    }

    @PostMapping("/notification_preferences")
    public ResponseEntity<BaseResponse> updateNotificationPreferences(@RequestBody NotificationPreferencesData data, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(notificationService.updateNotificationPreferences(accountId, data));
    }
}

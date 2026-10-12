package com.macrotel.rapidstylers.engagement.controller;

import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.engagement.service.StylerInboxService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The signed-in stylist's in-app notifications (admin announcements to stylists),
 * mirroring the customer /notifications routes under /rapid_stylers/styler.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class StylerNotificationController {
    @Autowired
    StylerInboxService stylerInboxService;

    @GetMapping("/styler/notifications")
    public ResponseEntity<BaseResponse> notifications(HttpServletRequest request){
        String stylerId = CurrentAccount.id(request);
        if(stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(stylerInboxService.listNotifications(stylerId));
    }

    @PostMapping("/styler/notifications/read")
    public ResponseEntity<BaseResponse> markRead(@RequestBody Map<String, Long> body, HttpServletRequest request){
        String stylerId = CurrentAccount.id(request);
        if(stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(stylerInboxService.markRead(stylerId, body.get("notificationId")));
    }

    @PostMapping("/styler/notifications/read_all")
    public ResponseEntity<BaseResponse> markAllRead(HttpServletRequest request){
        String stylerId = CurrentAccount.id(request);
        if(stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(stylerInboxService.markAllRead(stylerId));
    }

    @PostMapping("/styler/notifications/delete")
    public ResponseEntity<BaseResponse> delete(@RequestBody Map<String, Long> body, HttpServletRequest request){
        String stylerId = CurrentAccount.id(request);
        if(stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(stylerInboxService.delete(stylerId, body.get("notificationId")));
    }

    @PostMapping("/styler/notifications/delete_all")
    public ResponseEntity<BaseResponse> deleteAll(HttpServletRequest request){
        String stylerId = CurrentAccount.id(request);
        if(stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(stylerInboxService.deleteAll(stylerId));
    }
}

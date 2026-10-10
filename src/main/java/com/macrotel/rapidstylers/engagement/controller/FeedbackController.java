package com.macrotel.rapidstylers.engagement.controller;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.engagement.pojo.UserFeedbackData;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.engagement.service.FeedbackService;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * General app feedback. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class FeedbackController {
    @Autowired
    FeedbackService feedbackService;

    @PostMapping("/add_feedback")
    public ResponseEntity<BaseResponse> addFeedBack(@Valid @RequestBody UserFeedbackData userFeedbackData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        // Feedback is attributed to the authenticated account.
        userFeedbackData.setUserId(accountId);
        BaseResponse baseResponse = feedbackService.addUserFeedBack(userFeedbackData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_feedback")
    public ResponseEntity<BaseResponse> listFeedback(HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        BaseResponse baseResponse = feedbackService.listUserFeedBack();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }
}

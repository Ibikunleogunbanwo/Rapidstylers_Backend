package com.macrotel.rapidstylers.engagement.controller;

import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.engagement.pojo.ReviewData;
import com.macrotel.rapidstylers.engagement.service.ReviewService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Writing, listing and moderating stylist reviews. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class ReviewController {
    @Autowired
    ReviewService reviewService;

    @GetMapping("/admin/review_moderation_queue")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<BaseResponse> reviewModerationQueue(HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(reviewService.getReviewModerationQueue());
    }

    /** Published (APPROVED, the default), hidden (REJECTED) or PENDING reviews, searchable and paged. */
    @GetMapping("/admin/reviews")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<BaseResponse> adminReviews(@RequestParam(value = "status", required = false) String status,
                                                     @RequestParam(value = "q", required = false) String q,
                                                     @RequestParam(value = "page", required = false) Integer page,
                                                     @RequestParam(value = "size", required = false) Integer size,
                                                     HttpServletRequest request){
        if(CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(reviewService.listReviewsForAdmin(status, q, page, size));
    }

    @PostMapping("/admin/update_review_moderation")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<BaseResponse> updateReviewModeration(@RequestBody Map<String, Object> body, HttpServletRequest request){
        String adminId = CurrentAccount.id(request);
        if(adminId == null) return CurrentAccount.unauthorized();
        Long reviewId = body.get("reviewId") == null ? null : Long.valueOf(String.valueOf(body.get("reviewId")));
        return ApiResponses.respond(reviewService.updateReviewModeration(reviewId, String.valueOf(body.get("action")), adminId));
    }

    @PostMapping("/create_review")
    public ResponseEntity <BaseResponse> createReview(@Valid @RequestBody ReviewData reviewData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        // The reviewer is the authenticated account — never trust the body's userId.
        reviewData.setUserId(accountId);
        BaseResponse baseResponse = reviewService.createStylerReview(reviewData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_review")
    public ResponseEntity<BaseResponse> listReview(@RequestParam("stylerId") String stylerId){
        BaseResponse baseResponse = reviewService.listStylerReviews(stylerId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    /** The logged-in stylist's own reviews (approved list plus a moderation count). */
    @GetMapping("/styler/reviews")
    public ResponseEntity<BaseResponse> stylerOwnReviews(HttpServletRequest request){
        String stylerId = CurrentAccount.id(request);
        if(stylerId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(reviewService.getOwnStylerReviews(stylerId));
    }
}

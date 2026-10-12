package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.service.DTOService;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.engagement.dto.StylerReviewDTO;
import com.macrotel.rapidstylers.engagement.entity.ReviewEntity;
import com.macrotel.rapidstylers.engagement.pojo.ReviewData;
import com.macrotel.rapidstylers.engagement.repo.ReviewRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.util.*;
import java.util.Locale;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Customer reviews of stylists: writing them, admin moderation and the review lists.
 */
@Service
public class ReviewService {
    @Autowired
    DTOService dtoService;
    private static final Logger LOG = Logger.getLogger(ReviewService.class.getName());
    @Autowired
    CustomerRecords customerRecords;
    @Autowired
    StylerRecords stylerRecords;
    @Autowired
    ReviewRepo reviewRepo;
    @Autowired
    AppointmentRecords appointmentRecords;
    @Autowired
    ReadCacheService readCacheService;
    // Shared audit-log / ops-alert sink (see simplification-plan.md).
    @Autowired
    AuditService auditService = new AuditService();

    @Transactional
    public BaseResponse createStylerReview(ReviewData reviewData){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if user account exist
            Optional<UserEntity> isUserExist= customerRecords.findByUserId(reviewData.getUserId()) ;
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Check if styler account exist
            Optional<StylerEntity> isStylerExist= stylerRecords.findByStylerId(reviewData.getStylerId()) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Reviews are only valid for a COMPLETED booking with this stylist,
            // and the booking must belong to the authenticated customer.
            Optional<BookAppointmentEntity> bookingOpt = appointmentRecords.findByAppointmentId(reviewData.getBookingId());
            if(bookingOpt.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid booking reference");
                response.setData(EMPTY_DATA);
                return response;
            }
            BookAppointmentEntity booking = bookingOpt.get();
            if(!booking.getUserId().equals(reviewData.getUserId())){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You can only review your own bookings");
                response.setData(EMPTY_DATA);
                return response;
            }
            if(!booking.getStylerId().equals(reviewData.getStylerId())){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("This booking is not with this stylist");
                response.setData(EMPTY_DATA);
                return response;
            }
            if(!"0".equals(booking.getStatus())){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You can only review a completed appointment");
                response.setData(EMPTY_DATA);
                return response;
            }
            if(reviewRepo.findByBookingId(reviewData.getBookingId()).isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You have already reviewed this booking");
                response.setData(EMPTY_DATA);
                return response;
            }
            //Get User data
            UserEntity userEntity = isUserExist.get();
            String userName = userEntity.getFirstname() +" " + userEntity.getLastname();
            ReviewEntity reviewEntity = new ReviewEntity();
            reviewEntity.setStylerId(reviewData.getStylerId());
            reviewEntity.setUserId(reviewData.getUserId());
            reviewEntity.setBookingId(reviewData.getBookingId());
            reviewEntity.setUserName(userName);
            int rating = Integer.parseInt(reviewData.getRatingScore());
            if(rating < 1 || rating > 5){
                return Responses.errorResponse(response, "Rating Score must be between 1 and 5");
            }
            reviewEntity.setMessage(AppUtils.sanitizeText(reviewData.getReviewMessage()));
            reviewEntity.setModerationStatus("PENDING");
            reviewEntity.setRatingScore(rating);
            reviewRepo.save(reviewEntity);
            auditService.audit("" + reviewData.getUserId(), "CUSTOMER", "CREATE_REVIEW", "REVIEW", reviewData.getBookingId(), "Review submitted");

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Rating Submitted Successful");
            response.setData(EMPTY_DATA);
        }
        catch (DataIntegrityViolationException ex){
            throw ex;
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse getReviewModerationQueue(){
        BaseResponse response = new BaseResponse(true);
        try {
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(reviewRepo.findByModerationStatusOrderByCreatedAtDesc("PENDING"));
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    /**
     * Admin review list for one moderation status (APPROVED by default, also
     * PENDING or REJECTED = hidden), optionally searched by stylist business
     * name, reviewer name or message. Returns {items, total, page, size}; page
     * counts from 0 and size is capped at 100.
     */
    public BaseResponse listReviewsForAdmin(String status, String query, Integer page, Integer size){
        BaseResponse response = new BaseResponse(true);
        try {
            String wanted = status == null || status.isBlank() ? "APPROVED" : status.trim().toUpperCase(Locale.ROOT);
            if(!Arrays.asList("APPROVED", "REJECTED", "PENDING").contains(wanted)){
                return Responses.errorResponse(response, "Status must be APPROVED, REJECTED or PENDING");
            }
            int pageNumber = page == null || page < 0 ? 0 : page;
            int pageSize = size == null || size < 1 ? 20 : Math.min(size, 100);
            String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            // A % or _ typed into the search would match anything, so they are dropped.
            text = text.replace("%", "").replace("_", "");
            String pattern = text.isEmpty() ? null : "%" + (text.length() > 100 ? text.substring(0, 100) : text) + "%";
            org.springframework.data.domain.Page<ReviewEntity> found = reviewRepo.adminSearch(wanted, pattern,
                    org.springframework.data.domain.PageRequest.of(pageNumber, pageSize));
            Map<String, String> businessNames = new HashMap<>();
            List<Map<String, Object>> items = new ArrayList<>();
            for(ReviewEntity review : found.getContent()){
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("reviewId", review.getId());
                item.put("stylerId", review.getStylerId());
                item.put("businessName", businessNames.computeIfAbsent(String.valueOf(review.getStylerId()),
                        id -> stylerRecords.findByStylerId(id).map(StylerEntity::getBusinessName).orElse(null)));
                item.put("userName", review.getUserName());
                item.put("ratingScore", review.getRatingScore());
                item.put("message", review.getMessage());
                item.put("createdAt", review.getCreatedAt());
                item.put("moderationStatus", review.getModerationStatus());
                items.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("total", found.getTotalElements());
            data.put("page", pageNumber);
            data.put("size", pageSize);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch(Exception ex){
            LOG.warning("Admin review list failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not load reviews");
        }
        return response;
    }

    public BaseResponse updateReviewModeration(Long reviewId, String action, String adminId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(reviewId == null){
                return Responses.errorResponse(response, "Review id is required");
            }
            Optional<ReviewEntity> review = reviewRepo.findById(reviewId);
            String status = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
            if(review.isEmpty() || !Arrays.asList("APPROVED", "REJECTED").contains(status)) return Responses.errorResponse(response, "Invalid review or moderation action");
            review.get().setModerationStatus(status);
            reviewRepo.save(review.get());
            evict(ReadCacheService.KEY_STYLER_REVIEWS + review.get().getStylerId(),
                    ReadCacheService.KEY_STYLER_DTO + review.get().getStylerId());
            auditService.audit(adminId, "ADMIN", "MODERATE_REVIEW", "REVIEW", String.valueOf(reviewId), status);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Review moderation updated");
            response.setData(EMPTY_DATA);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse listStylerReviews(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if styler account exist
            Optional<StylerEntity> isStylerExist= stylerRecords.findByStylerId(stylerId) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(cachedReviews(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * The logged-in professional's own reviews. This is deliberately not just
     * the public list: an approved review is what clients see, and a count of
     * what is still in moderation stops a review the stylist was told about
     * from looking lost. The average covers approved rows only, so it matches
     * the number the public profile shows.
     */
    public BaseResponse getOwnStylerReviews(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            if(stylerRecords.findByStylerId(stylerId).isEmpty()){
                return Responses.errorResponse(response, "Invalid Styler Id");
            }
            List<Object> approved = cachedReviews(stylerId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reviews", approved);
            data.put("reviewCount", approved.size());
            data.put("averageRating", averageReviewRating(approved));
            data.put("pendingCount", pendingReviewCount(stylerId));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        }
        catch (Exception ex){
            LOG.warning("Own reviews failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not load reviews");
        }
        return response;
    }

    /**
     * Rounded to one decimal, matching how the public profile reports a rating
     * (see DTOService), so the stylist and their clients read the same number.
     */
    static Double averageReviewRating(List<Object> reviews){
        double total = 0;
        int scored = 0;
        for(Object row : reviews){
            if(!(row instanceof StylerReviewDTO dto) || dto.getRatingScore() == null) continue;
            try{
                total += Double.parseDouble(dto.getRatingScore().trim());
                scored++;
            }
            catch (NumberFormatException ignored){
                // A malformed score is left out rather than dragging the average down.
            }
        }
        if(scored == 0) return null;
        return Math.round((total / scored) * 10.0) / 10.0;
    }

    /** How many of this stylist's reviews are waiting on moderation. */
    private int pendingReviewCount(String stylerId){
        List<ReviewEntity> rows = reviewRepo.findByStylerId(stylerId);
        if(rows == null) return 0;
        int pending = 0;
        for(ReviewEntity row : rows){
            if("PENDING".equalsIgnoreCase(row.getModerationStatus())) pending++;
        }
        return pending;
    }

    /** Approved reviews through the read cache; evicted on review moderation writes. */
    public List<Object> cachedReviews(String stylerId){
        return readCacheService.getOrLoad(
                ReadCacheService.KEY_STYLER_REVIEWS + stylerId,
                ReadCacheService.STYLER_PART_TTL, ReadCacheService.STYLER_PART_JITTER,
                () -> {
                    List<ReviewEntity> rows = reviewRepo.findByStylerIdAndModerationStatus(stylerId, "APPROVED");
                    List<Object> built = new ArrayList<>();
                    for(ReviewEntity entity : rows){
                        built.add(dtoService.stylerReviewDTO(entity));
                    }
                    Collections.reverse(built);
                    return built;
                });
    }

    /** Null-safe eviction — unit tests that build this service without a cache never break. */
    private void evict(String... keys){
        if(readCacheService != null){
            readCacheService.evict(keys);
        }
    }
}

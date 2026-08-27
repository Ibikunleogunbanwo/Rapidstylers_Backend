package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.dto.*;
import com.macrotel.rapidstylers.entity.*;
import com.macrotel.rapidstylers.repo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.macrotel.rapidstylers.config.AppConstants.DEFAULT_SERVICE_DURATION_MINUTES;
import static com.macrotel.rapidstylers.config.AppConstants.EMPTY_DATA;

@Service
public class DTOService {
    @Autowired
    ServiceRepo serviceRepo;
    @Autowired
    UserRepo userRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    SubServiceRepo subServiceRepo;
    @Autowired
    CardDetailsRepo cardDetailsRepo;
    @Autowired
    ReviewRepo reviewRepo;
    @Autowired
    RefundRepo refundRepo;
    @Autowired
    StripeService stripeService;
    public UserAccountDTO userAccountDTO(UserEntity userEntity){
        UserAccountDTO userAccountDTO = new UserAccountDTO();
        userAccountDTO.setAddress(userEntity.getAddress());
        userAccountDTO.setCountry(userEntity.getCountry());
        userAccountDTO.setLastname(userEntity.getLastname());
        userAccountDTO.setFirstname(userEntity.getFirstname());
        userAccountDTO.setEmailAddress(userEntity.getEmailAddress());
        userAccountDTO.setState(userEntity.getState());
        userAccountDTO.setPhoneNumber(userEntity.getPhoneNumber());
        userAccountDTO.setUserId(userEntity.getUserId());
        userAccountDTO.setDateRegistered(userEntity.getInsertedDt());
        return userAccountDTO;
    }

    public StylerAccountDTO stylerAccountDTO (StylerEntity stylerEntity){
        Optional<ServiceEntity> getServiceType = serviceRepo == null || stylerEntity.getServiceTypeId() == null
                ? Optional.empty()
                : serviceRepo.findById(Long.parseLong(stylerEntity.getServiceTypeId()));
        String serviceName="";
        if(getServiceType.isPresent()){
            ServiceEntity serviceEntity = getServiceType.get();
            serviceName = serviceEntity.getServiceName();
        }
        StylerAccountDTO stylerAccountDTO = new StylerAccountDTO();
        stylerAccountDTO.setFirstname(stylerEntity.getFirstname());
        stylerAccountDTO.setLastname(stylerEntity.getLastname());
        stylerAccountDTO.setStylerId(stylerEntity.getStylerId());
        stylerAccountDTO.setEmailAddress(stylerEntity.getEmailAddress());
        stylerAccountDTO.setServiceTypeId(stylerEntity.getServiceTypeId());
        stylerAccountDTO.setServiceTypeName(serviceName);
        stylerAccountDTO.setAccountStatus(Objects.equals(stylerEntity.getStatus(),"0") ?"Active": "Inactive");
        stylerAccountDTO.setVisibilityStatus(Objects.equals(stylerEntity.getIsOnline(),"0")?"Online": "Offline");
        stylerAccountDTO.setProfileImageUrl(stylerEntity.getProfileImageUrl());
        stylerAccountDTO.setBusinessName(stylerEntity.getBusinessName());
        stylerAccountDTO.setBusinessAddress(stylerEntity.getBusinessAddress());
        stylerAccountDTO.setProvince(stylerEntity.getProvince());
        stylerAccountDTO.setStreetAddress(stylerEntity.getStreetAddress());
        stylerAccountDTO.setUnit(stylerEntity.getUnit());
        stylerAccountDTO.setCity(stylerEntity.getCity());
        stylerAccountDTO.setPostalCode(stylerEntity.getPostalCode());
        stylerAccountDTO.setCountry(stylerEntity.getCountry());
        stylerAccountDTO.setLatitude(stylerEntity.getLatitude());
        stylerAccountDTO.setLongitude(stylerEntity.getLongitude());
        stylerAccountDTO.setIncludedTravelKm(stylerEntity.getIncludedTravelKm() == null ? 15.0 : stylerEntity.getIncludedTravelKm());
        stylerAccountDTO.setExtraTravelRatePerKm(stylerEntity.getExtraTravelRatePerKm() == null ? "0.00" : stylerEntity.getExtraTravelRatePerKm());
        stylerAccountDTO.setMaxServiceDistanceKm(stylerEntity.getMaxServiceDistanceKm());
        stylerAccountDTO.setPhoneNumber(stylerEntity.getPhoneNumber());
        stylerAccountDTO.setDescription(stylerEntity.getDescription());
        stylerAccountDTO.setVerificationStatus(stylerEntity.getVerificationStatus());
        // Marketplace payout flag: a stylist can receive money only when Connect
        // onboarding is COMPLETE — or when payments aren't configured at all
        // (dev mode), in which case nothing blocks the flow.
        stylerAccountDTO.setPayoutReady(!stripeService.isConfigured()
                || "COMPLETE".equals(stylerEntity.getConnectOnboardingStatus()));
        // Real review aggregates — cards and lists show actual ratings instead of placeholders.
        List<ReviewEntity> reviews = reviewRepo == null
                ? Collections.emptyList()
                : reviewRepo.findByStylerIdAndModerationStatus(stylerEntity.getStylerId(), "APPROVED");
        if(reviews != null && !reviews.isEmpty()){
            double sum = 0;
            for(ReviewEntity review : reviews){
                sum += review.getRatingScore();
            }
            stylerAccountDTO.setAverageRating(Math.round((sum / reviews.size()) * 10.0) / 10.0);
            stylerAccountDTO.setReviewCount((long) reviews.size());
        } else {
            stylerAccountDTO.setAverageRating(0.0);
            stylerAccountDTO.setReviewCount(0L);
        }
        return stylerAccountDTO;
    }

    public SubServiceDTO subServiceDTO(SubServiceEntity subServiceEntity){
        SubServiceDTO subServiceDTO = new SubServiceDTO();
        subServiceDTO.setName(subServiceEntity.getName());
        subServiceDTO.setId(String.valueOf(subServiceEntity.getId()));
        subServiceDTO.setStatus(subServiceEntity.getStatus().equals("0") ? "Active" : "Inactive");
        subServiceDTO.setPrice(subServiceEntity.getPrice());
        subServiceDTO.setDurationMinutes(subServiceEntity.getDurationMinutes() == null
                ? DEFAULT_SERVICE_DURATION_MINUTES : subServiceEntity.getDurationMinutes());
        subServiceDTO.setCreatedAt(subServiceEntity.getCreatedAt());
        return subServiceDTO;
    }

    public StylerPortfolioDTO stylerPortfolioDTO(StylerPortfolioEntity stylerPortfolioEntity){
        StylerPortfolioDTO stylerPortfolioDTO = new StylerPortfolioDTO();
        stylerPortfolioDTO.setId(stylerPortfolioEntity.getId());
        stylerPortfolioDTO.setName(stylerPortfolioEntity.getName());
        stylerPortfolioDTO.setImageUrl(stylerPortfolioEntity.getImageUrl());
        stylerPortfolioDTO.setCategory(stylerPortfolioEntity.getCategory());
        stylerPortfolioDTO.setStatus(stylerPortfolioEntity.getStatus().equals("0") ? "Active" : "Inactive");
        stylerPortfolioDTO.setCreatedAt(stylerPortfolioEntity.getCreatedAt());
        return stylerPortfolioDTO;
    }

    public StylerReviewDTO stylerReviewDTO(ReviewEntity reviewEntity){
        StylerReviewDTO stylerReviewDTO = new StylerReviewDTO();
        stylerReviewDTO.setUserName(reviewEntity.getUserName());
        stylerReviewDTO.setUserId(reviewEntity.getUserId());
        stylerReviewDTO.setStylerId(reviewEntity.getStylerId());
        stylerReviewDTO.setMessage(reviewEntity.getMessage());
        stylerReviewDTO.setRatingScore(String.valueOf(reviewEntity.getRatingScore()));
        stylerReviewDTO.setCreatedAt(reviewEntity.getCreatedAt());
        stylerReviewDTO.setBookingId(reviewEntity.getBookingId());
        return stylerReviewDTO;
    }

    public AppointmentDTO appointmentDTO(BookAppointmentEntity bookAppointmentEntity){
        AppointmentDTO appointmentDTO = new AppointmentDTO();
        //Get Userdata
        String subServiceName ="";
        Optional<UserEntity> userData = userRepo == null
                ? Optional.empty()
                : userRepo.findByUserId(bookAppointmentEntity.getUserId());
        if(userData.isPresent()){
            UserEntity userEntity = userData.get();
            appointmentDTO.setUserData(this.userAccountDTO(userEntity));
        }
        //Get Styler data
        Optional<StylerEntity> stylerData = stylerRepo == null
                ? Optional.empty()
                : stylerRepo.findByStylerId(bookAppointmentEntity.getStylerId());
        if(stylerData.isPresent()){
            StylerEntity stylerEntity = stylerData.get();
            appointmentDTO.setStylerData(this.stylerAccountDTO(stylerEntity));
        }
        //Get service data
        Optional<SubServiceEntity> subServiceData = subServiceRepo == null
                ? Optional.empty()
                : subServiceRepo.isServiceExistById(bookAppointmentEntity.getStylerId(), Long.parseLong(bookAppointmentEntity.getSubServiceId()));
        if(subServiceData.isPresent()){
            SubServiceEntity subServiceEntity = subServiceData.get();
            appointmentDTO.setSubServiceData(this.subServiceDTO(subServiceEntity));
        }

        appointmentDTO.setAppointmentDate(bookAppointmentEntity.getAppointmentDate());
        appointmentDTO.setAppointmentId(bookAppointmentEntity.getAppointmentId());
        appointmentDTO.setServicePrice(bookAppointmentEntity.getServicePrice() == null
                ? bookAppointmentEntity.getPrice() : bookAppointmentEntity.getServicePrice());
        appointmentDTO.setTravelFee(bookAppointmentEntity.getTravelFee() == null
                ? "0.00" : bookAppointmentEntity.getTravelFee());
        appointmentDTO.setIncludedTravelKm(bookAppointmentEntity.getIncludedTravelKm() == null
                ? 15.0 : bookAppointmentEntity.getIncludedTravelKm());
        appointmentDTO.setTravelDistanceKm(bookAppointmentEntity.getTravelDistanceKm() == null
                ? 0.0 : bookAppointmentEntity.getTravelDistanceKm());
        appointmentDTO.setBillableTravelKm(bookAppointmentEntity.getBillableTravelKm() == null
                ? 0.0 : bookAppointmentEntity.getBillableTravelKm());
        appointmentDTO.setExtraTravelRatePerKm(bookAppointmentEntity.getExtraTravelRatePerKm() == null
                ? "0.00" : bookAppointmentEntity.getExtraTravelRatePerKm());
        appointmentDTO.setPrice(bookAppointmentEntity.getPrice());
        appointmentDTO.setServiceTime(bookAppointmentEntity.getServiceTime());
        appointmentDTO.setArrivalTime(bookAppointmentEntity.getArrivalTime());
        appointmentDTO.setDurationMinutes(bookAppointmentEntity.getDurationMinutes() == null
                ? DEFAULT_SERVICE_DURATION_MINUTES : bookAppointmentEntity.getDurationMinutes());
        appointmentDTO.setNoOfPeople(bookAppointmentEntity.getNoOfPeople());
        // Marketplace lifecycle: 1 pending → 3 accepted → 0 completed; 2 rejected; 4 cancelled.
        appointmentDTO.setStatusCode(bookAppointmentEntity.getStatus());
        appointmentDTO.setStatus(bookAppointmentEntity.getStatus().equals("0") ? "Completed"
                : bookAppointmentEntity.getStatus().equals("1") ? "Pending"
                : bookAppointmentEntity.getStatus().equals("2") ? "Rejected"
                : bookAppointmentEntity.getStatus().equals("3") ? "Accepted"
                : bookAppointmentEntity.getStatus().equals("4") ? "Cancelled"
                : "Pending");
        appointmentDTO.setCreatedAt(bookAppointmentEntity.getCreatedAt());
        appointmentDTO.setPaymentStatus(bookAppointmentEntity.getPaymentStatus());
        appointmentDTO.setPaymentFailureCode(bookAppointmentEntity.getPaymentFailureCode());
        appointmentDTO.setStripeTransferId(bookAppointmentEntity.getStripeTransferId());
        appointmentDTO.setCompletedAt(bookAppointmentEntity.getCompletedAt());
        populateRefund(appointmentDTO, bookAppointmentEntity.getAppointmentId());
        return appointmentDTO;
    }

    /** Attaches the completed refund (if any) so customers see when a cancelled booking was refunded. */
    private void populateRefund(AppointmentDTO appointmentDTO, String appointmentId){
        if(refundRepo == null || appointmentId == null || appointmentId.isBlank()){
            return;
        }
        try{
            refundRepo.findByAppointmentId(appointmentId).stream()
                    .filter(refund -> "COMPLETED".equals(refund.getStatus()))
                    .max(Comparator.comparing(RefundEntity::getCompletedAt, Comparator.nullsLast(String::compareTo)))
                    .ifPresent(refund -> {
                        appointmentDTO.setRefundId(refund.getRefundId());
                        appointmentDTO.setRefundStatus(refund.getStatus());
                        appointmentDTO.setRefundAmount(refund.getAmount());
                        appointmentDTO.setRefundCompletedAt(refund.getCompletedAt());
                    });
        } catch(Exception ex){
            // Refund lookup must never break appointment lists.
        }
    }

    public FeedBackDTO feedBackDTO(FeedbackEntity feedbackEntity){
        FeedBackDTO feedBackDTO = new FeedBackDTO();
        Optional<UserEntity> getUserDetails = userRepo.findByUserId(feedbackEntity.getUserId());
        if(getUserDetails.isPresent()){
            UserEntity userEntity = getUserDetails.get();
            feedBackDTO.setUserData(this.userAccountDTO(userEntity));
        }
        feedBackDTO.setInsertedDt(feedbackEntity.getInsertedDt());
        feedBackDTO.setMessage(feedbackEntity.getMessage());
        feedBackDTO.setMessageType(feedbackEntity.getFeedBackType());
        feedBackDTO.setId(String.valueOf(feedbackEntity.getId()));
        feedBackDTO.setEmailAddress(feedbackEntity.getEmailAddress());
        return feedBackDTO;
    }

    public CardDetailsDTO cardDetailsDTO(CardDetailsEntity cardDetailsEntity){
        CardDetailsDTO cardDetailsDTO = new CardDetailsDTO();
        // Display-only metadata. The full PAN and CVV no longer exist anywhere.
        cardDetailsDTO.setCardName(cardDetailsEntity.getCardName());
        cardDetailsDTO.setLast4(cardDetailsEntity.getLast4());
        cardDetailsDTO.setBrand(cardDetailsEntity.getBrand());
        cardDetailsDTO.setExpMonth(cardDetailsEntity.getExpMonth());
        cardDetailsDTO.setExpYear(cardDetailsEntity.getExpYear());
        return cardDetailsDTO;
    }
    public UserDataDTO userDataDTO (String userId) throws Exception {
        UserDataDTO userDataDTO = new UserDataDTO();
        //Get user details
        Optional<UserEntity> getUserDetails = userRepo.findByUserId(userId);
        if(getUserDetails.isPresent()){
            UserEntity userEntity = getUserDetails.get();
            userDataDTO.setUserData(this.userAccountDTO(userEntity));
        }
        //Get card details (plain userId — legacy encrypted rows are migrated on startup).
        Optional<CardDetailsEntity> getUserCardDetails = cardDetailsRepo.findByUserId(userId);
        if(getUserCardDetails.isPresent()){
            CardDetailsEntity cardDetailsEntity = getUserCardDetails.get();
            userDataDTO.setUserCardData(this.cardDetailsDTO(cardDetailsEntity));
        }


        return userDataDTO;
    }
}

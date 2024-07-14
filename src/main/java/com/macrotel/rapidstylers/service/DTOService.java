package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EncryptionConfig;
import com.macrotel.rapidstylers.dto.*;
import com.macrotel.rapidstylers.entity.*;
import com.macrotel.rapidstylers.repo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

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
        Optional<ServiceEntity> getServiceType = serviceRepo.findById(Long.parseLong(stylerEntity.getServiceTypeId()));
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
        stylerAccountDTO.setPhoneNumber(stylerEntity.getPhoneNumber());
        stylerAccountDTO.setDescription(stylerEntity.getDescription());
        return stylerAccountDTO;
    }

    public SubServiceDTO subServiceDTO(SubServiceEntity subServiceEntity){
        SubServiceDTO subServiceDTO = new SubServiceDTO();
        subServiceDTO.setName(subServiceEntity.getName());
        subServiceDTO.setId(String.valueOf(subServiceEntity.getId()));
        subServiceDTO.setStatus(subServiceEntity.getStatus().equals("0") ? "Active" : "Inactive");
        subServiceDTO.setPrice(subServiceEntity.getPrice());
        subServiceDTO.setCreatedAt(subServiceEntity.getCreatedAt());
        return subServiceDTO;
    }

    public StylerPortfolioDTO stylerPortfolioDTO(StylerPortfolioEntity stylerPortfolioEntity){
        StylerPortfolioDTO stylerPortfolioDTO = new StylerPortfolioDTO();
        stylerPortfolioDTO.setName(stylerPortfolioEntity.getName());
        stylerPortfolioDTO.setImageUrl(stylerPortfolioEntity.getImageUrl());
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
        return stylerReviewDTO;
    }

    public AppointmentDTO appointmentDTO(BookAppointmentEntity bookAppointmentEntity){
        AppointmentDTO appointmentDTO = new AppointmentDTO();
        //Get Userdata
        String subServiceName ="";
        Optional<UserEntity> userData = userRepo.findByUserId(bookAppointmentEntity.getUserId());
        if(userData.isPresent()){
            UserEntity userEntity = userData.get();
            appointmentDTO.setUserData(this.userAccountDTO(userEntity));
        }
        //Get Styler data
        Optional<StylerEntity> stylerData = stylerRepo.findByStylerId(bookAppointmentEntity.getStylerId());
        if(stylerData.isPresent()){
            StylerEntity stylerEntity = stylerData.get();
            appointmentDTO.setStylerData(this.stylerAccountDTO(stylerEntity));
        }
        //Get service data
        Optional<SubServiceEntity> subServiceData = subServiceRepo.isServiceExistById(bookAppointmentEntity.getStylerId(), Long.parseLong(bookAppointmentEntity.getSubServiceId()));
        if(subServiceData.isPresent()){
            SubServiceEntity subServiceEntity = subServiceData.get();
            appointmentDTO.setSubServiceData(this.subServiceDTO(subServiceEntity));
        }

        appointmentDTO.setAppointmentDate(bookAppointmentEntity.getAppointmentDate());
        appointmentDTO.setAppointmentId(bookAppointmentEntity.getAppointmentId());
        appointmentDTO.setPrice(bookAppointmentEntity.getPrice());
        appointmentDTO.setServiceTime(bookAppointmentEntity.getServiceTime());
        appointmentDTO.setArrivalTime(bookAppointmentEntity.getArrivalTime());
        appointmentDTO.setNoOfPeople(bookAppointmentEntity.getNoOfPeople());
        appointmentDTO.setStatus(bookAppointmentEntity.getStatus().equals("0") ?"Completed" : bookAppointmentEntity.getStatus().equals("1") ? "Pending" : "Rejected");
        appointmentDTO.setCreatedAt(bookAppointmentEntity.getCreatedAt());
        return appointmentDTO;
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
        cardDetailsDTO.setCardName(cardDetailsEntity.getCardName());
        cardDetailsDTO.setCardNumber(cardDetailsEntity.getCardNumber());
        cardDetailsDTO.setCvv(cardDetailsEntity.getCvv());
        cardDetailsDTO.setExpiryDate(cardDetailsEntity.getExpiryDate());
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
        //Get card details
        Optional<CardDetailsEntity> getUserCardDetails = cardDetailsRepo.findByUserId(EncryptionConfig.encrypt(userId));
        if(getUserCardDetails.isPresent()){
            CardDetailsEntity cardDetailsEntity = getUserCardDetails.get();
            userDataDTO.setUserCardData(this.cardDetailsDTO(cardDetailsEntity));
        }


        return userDataDTO;
    }
}
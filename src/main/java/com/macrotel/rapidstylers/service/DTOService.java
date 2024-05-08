package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.dto.*;
import com.macrotel.rapidstylers.entity.*;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import com.macrotel.rapidstylers.repo.SubServiceRepo;
import com.macrotel.rapidstylers.repo.UserRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

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
        return stylerAccountDTO;
    }

    public SubServiceDTO subServiceDTO(SubServiceEntity subServiceEntity){
        SubServiceDTO subServiceDTO = new SubServiceDTO();
        subServiceDTO.setName(subServiceEntity.getName());
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
        List<Object> userResultMap = new ArrayList<>();
        List<Object> stylerResultMap = new ArrayList<>();
        List<Object> subServiceMap = new ArrayList<>();
        String subServiceName ="";
        Optional<UserEntity> userData = userRepo.findByUserId(bookAppointmentEntity.getUserId());
        if(userData.isPresent()){
            UserEntity userEntity = userData.get();
            userResultMap.add(this.userAccountDTO(userEntity));
        }
        //Get Styler data
        Optional<StylerEntity> stylerData = stylerRepo.findByStylerId(bookAppointmentEntity.getStylerId());
        if(stylerData.isPresent()){
            StylerEntity stylerEntity = stylerData.get();
            stylerResultMap.add(this.stylerAccountDTO(stylerEntity));
        }
        //Get service data
        Optional<SubServiceEntity> subServiceData = subServiceRepo.isServiceExistById(bookAppointmentEntity.getStylerId(), Long.parseLong(bookAppointmentEntity.getSubServiceId()));
        if(subServiceData.isPresent()){
            SubServiceEntity subServiceEntity = subServiceData.get();
            subServiceMap.add(this.subServiceDTO(subServiceEntity));
        }

        appointmentDTO.setAppointmentDate(bookAppointmentEntity.getAppointmentDate());
        appointmentDTO.setAppointmentId(bookAppointmentEntity.getAppointmentId());
        appointmentDTO.setPrice(bookAppointmentEntity.getPrice());
        appointmentDTO.setServiceTime(bookAppointmentEntity.getServiceTime());
        appointmentDTO.setUserData(userResultMap);
        appointmentDTO.setStylerData(stylerResultMap);
        appointmentDTO.setSubServiceData(subServiceMap);
        appointmentDTO.setArrivalTime(bookAppointmentEntity.getArrivalTime());
        appointmentDTO.setNoOfPeople(bookAppointmentEntity.getNoOfPeople());
        appointmentDTO.setStatus(bookAppointmentEntity.getStatus().equals("0") ?"Completed" : bookAppointmentEntity.getStatus().equals("1") ? "Pending" : "Rejected");
        appointmentDTO.setCreatedAt(bookAppointmentEntity.getCreatedAt());
        return appointmentDTO;
    }
}
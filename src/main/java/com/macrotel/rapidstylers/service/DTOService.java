package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.dto.StylerPortfolioDTO;
import com.macrotel.rapidstylers.dto.SubServiceDTO;
import com.macrotel.rapidstylers.dto.UserAccountDTO;
import com.macrotel.rapidstylers.entity.*;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
@Service
public class DTOService {
    @Autowired
    ServiceRepo serviceRepo;
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
}
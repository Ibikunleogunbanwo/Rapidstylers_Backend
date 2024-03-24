package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.dto.UserAccountDTO;
import com.macrotel.rapidstylers.entity.ServiceEntity;
import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.UserEntity;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;
import java.util.Optional;

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
        return stylerAccountDTO;
    }
}
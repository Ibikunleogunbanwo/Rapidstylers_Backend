package com.macrotel.rapidstylers.customer.service;

import com.macrotel.rapidstylers.common.service.DTOService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.customer.entity.SavedStylistEntity;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.pojo.UpdateData;
import com.macrotel.rapidstylers.customer.repo.SavedStylistRepo;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerEligibility;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import com.macrotel.rapidstylers.styler.service.StylerService;
import com.stripe.model.Account;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Customer profiles and saved stylists.
 */
@Service
public class CustomerService {
    @Autowired
    DTOService dtoService;
    private static final Logger LOG = Logger.getLogger(CustomerService.class.getName());
    @Autowired
    UserRepo userRepo;
    @Autowired
    StylerRecords stylerRecords;
    @Autowired
    SavedStylistRepo savedStylistRepo;
    @Autowired
    StylerEligibility stylerEligibility;
    @Autowired
    StylerService stylerService;

    public BaseResponse singleUserData(String userId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<UserEntity> isUserExist = userRepo.findByUserId(userId);
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id, Kindly create account");
                response.setData(EMPTY_DATA);
                return response;
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(dtoService.userDataDTO(userId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateUserData(UpdateData updateData, String accountId){
        BaseResponse response = new BaseResponse(true);
        try{
            // Ownership: the account being edited is the authenticated token subject, never the request body.
            Optional<UserEntity> isUserExist = userRepo.findByUserId(accountId);
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id, Kindly create an account");
                response.setData(EMPTY_DATA);
                return response;
            }
            UserEntity userEntity = isUserExist.get();
            // A field left out or blank keeps what is saved.
            userEntity.setFirstname(givenOr(updateData.getFirstname(), userEntity.getFirstname()));
            userEntity.setLastname(givenOr(updateData.getLastname(), userEntity.getLastname()));
            userEntity.setAddress(givenOr(updateData.getAddress(), userEntity.getAddress()));
            userEntity.setCountry(givenOr(updateData.getCountry(), userEntity.getCountry()));
            userEntity.setPhoneNumber(givenOr(updateData.getPhoneNumber(), userEntity.getPhoneNumber()));
            userEntity.setState(givenOr(updateData.getState(), userEntity.getState()));
            userRepo.save(userEntity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Account Updated Successful");
            response.setData(EMPTY_DATA);

        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    private static String givenOr(String given, String saved){
        return given == null || given.isEmpty() ? saved : given;
    }

    /** Saves an approved stylist for the authenticated customer. */
    public BaseResponse saveStylist(String userId, String stylerId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(userRepo.findByUserId(userId).isEmpty()){
                return Responses.errorResponse(response, "Invalid User Id");
            }
            Optional<StylerEntity> styler = stylerRecords.findByStylerId(stylerId);
            if(styler.isEmpty() || !stylerEligibility.isBookableStyler(styler.get())){
                return Responses.errorResponse(response, "This professional is not available");
            }
            if(savedStylistRepo.findByUserIdAndStylerId(userId, stylerId).isEmpty()){
                savedStylistRepo.save(new SavedStylistEntity(userId, stylerId));
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Professional saved");
            response.setData(Collections.singletonMap("saved", true));
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Removes a saved stylist only from the authenticated customer's collection. */
    public BaseResponse removeSavedStylist(String userId, String stylerId){
        BaseResponse response = new BaseResponse(true);
        try {
            savedStylistRepo.deleteByUserIdAndStylerId(userId, stylerId);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Professional removed from saved list");
            response.setData(Collections.singletonMap("saved", false));
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Returns the authenticated customer's saved stylists with live profile DTOs. */
    public BaseResponse listSavedStylists(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(userRepo.findByUserId(userId).isEmpty()){
                return Responses.errorResponse(response, "Invalid User Id");
            }
            List<Object> result = new ArrayList<>();
            for(SavedStylistEntity saved : savedStylistRepo.findByUserIdOrderByCreatedAtDesc(userId)){
                stylerRecords.findByStylerId(saved.getStylerId())
                        .filter(stylerEligibility::isBookableStyler)
                        .ifPresent(styler -> result.add(stylerService.listRowWithHours(dtoService.stylerAccountDTO(styler), styler.getStylerId())));
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        } catch (Exception ex) {
            LOG.warning(ex.getMessage());
        }
        return response;
    }
}

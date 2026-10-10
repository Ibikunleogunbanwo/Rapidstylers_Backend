package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.engagement.entity.FeedbackEntity;
import com.macrotel.rapidstylers.engagement.pojo.UserFeedbackData;
import com.macrotel.rapidstylers.engagement.repo.FeedBackRepo;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.entity.*;
import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.repo.*;
import com.macrotel.rapidstylers.service.DTOService;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * General app feedback from users.
 */
@Service
public class FeedbackService {
    @Autowired
    DTOService dtoService;
    private static final Logger LOG = Logger.getLogger(FeedbackService.class.getName());
    @Autowired
    UserRepo userRepo;
    @Autowired
    FeedBackRepo feedBackRepo;

    public BaseResponse addUserFeedBack (UserFeedbackData userFeedbackData){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if userId exist
            Optional<UserEntity> isUserExist = userRepo.findByUserId(userFeedbackData.getUserId());
            if(isUserExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid User Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            FeedbackEntity feedbackEntity = new FeedbackEntity();
            feedbackEntity.setUserId(userFeedbackData.getUserId());
            feedbackEntity.setFeedBackType(userFeedbackData.getFeedbackType());
            feedbackEntity.setEmailAddress(userFeedbackData.getEmailAddress());
            feedbackEntity.setUserId(userFeedbackData.getUserId());
            feedbackEntity.setMessage(AppUtils.sanitizeText(userFeedbackData.getMessage()));
            feedbackEntity.setEmailAddress(userFeedbackData.getEmailAddress());
            feedBackRepo.save(feedbackEntity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Your feedback has been submitted successful, Admin will take care of it.");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listUserFeedBack(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<FeedbackEntity> getAllFeedBack = feedBackRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(FeedbackEntity feedbackEntity : getAllFeedBack){
                result.add(dtoService.feedBackDTO(feedbackEntity));
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
}

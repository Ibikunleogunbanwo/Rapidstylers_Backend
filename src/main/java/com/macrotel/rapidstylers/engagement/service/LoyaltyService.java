package com.macrotel.rapidstylers.engagement.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.engagement.entity.LoyaltyAccountEntity;
import com.macrotel.rapidstylers.engagement.entity.ReferralEntity;
import com.macrotel.rapidstylers.engagement.repo.LoyaltyAccountRepo;
import com.macrotel.rapidstylers.engagement.repo.ReferralRepo;
import java.util.*;
import java.util.Locale;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Loyalty points and referrals.
 */
@Service
public class LoyaltyService {
    AppUtils appUtils = new AppUtils();
    private static final Logger LOG = Logger.getLogger(LoyaltyService.class.getName());
    @Autowired
    CustomerRecords customerRecords;
    @Autowired
    LoyaltyAccountRepo loyaltyAccountRepo;
    @Autowired
    ReferralRepo referralRepo;
    // Shared audit-log / ops-alert sink (see simplification-plan.md).
    @Autowired
    AuditService auditService = new AuditService();

    public BaseResponse getLoyaltyAccount(String userId){
        BaseResponse response = new BaseResponse(true);
        try {
            if(customerRecords.findByUserId(userId).isEmpty()) return Responses.errorResponse(response, "Invalid User Id");
            LoyaltyAccountEntity account = loyaltyAccountRepo.findByUserId(userId).orElseGet(() -> new LoyaltyAccountEntity(userId, "RS-" + appUtils.randomAlphanumeric(8).toUpperCase(Locale.ROOT)));
            if(account.getId() == null) loyaltyAccountRepo.save(account);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(account);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public BaseResponse createReferral(String userId, String referralCode){
        BaseResponse response = new BaseResponse(true);
        try {
            if(referralCode == null || referralCode.trim().isEmpty()){
                return Responses.errorResponse(response, "Referral code cannot be empty");
            }
            Optional<LoyaltyAccountEntity> referrer = loyaltyAccountRepo.findByReferralCode(referralCode.trim().toUpperCase(Locale.ROOT));
            if(referrer.isEmpty() || referrer.get().getUserId().equals(userId)) return Responses.errorResponse(response, "Invalid referral code");
            if(referralRepo.findByReferredUserId(userId).isPresent()) return Responses.errorResponse(response, "Referral already applied");
            ReferralEntity referral = referralRepo.save(new ReferralEntity(referrer.get().getUserId(), userId, referralCode.trim().toUpperCase(Locale.ROOT)));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Referral applied");
            response.setData(referral);
        } catch(Exception ex){ LOG.warning(ex.getMessage()); }
        return response;
    }

    public void awardCompletionPoints(String userId, String appointmentId){
        try {
            LoyaltyAccountEntity account = loyaltyAccountRepo.findByUserId(userId)
                    .orElseGet(() -> new LoyaltyAccountEntity(userId, "RS-" + appUtils.randomAlphanumeric(8).toUpperCase(Locale.ROOT)));
            account.addPoints(10);
            loyaltyAccountRepo.save(account);
            referralRepo.findByReferredUserId(userId).ifPresent(referral -> {
                if("PENDING".equals(referral.getStatus())){
                    referral.setStatus("COMPLETED");
                    referralRepo.save(referral);
                    loyaltyAccountRepo.findByUserId(referral.getReferrerUserId()).ifPresent(referrer -> {
                        referrer.addPoints(25);
                        loyaltyAccountRepo.save(referrer);
                    });
                }
            });
            auditService.audit("system", "SYSTEM", "AWARD_LOYALTY", "APPOINTMENT", appointmentId, "Completion points awarded");
        } catch(Exception ex){
            LOG.warning("Loyalty award failed: " + ex.getMessage());
        }
    }
}

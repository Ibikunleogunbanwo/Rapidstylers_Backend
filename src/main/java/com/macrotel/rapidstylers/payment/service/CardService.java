package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.service.CustomerRecords;
import com.macrotel.rapidstylers.payment.entity.CardDetailsEntity;
import com.macrotel.rapidstylers.payment.pojo.CardDetailsData;
import com.macrotel.rapidstylers.payment.repo.CardDetailsRepo;
import com.stripe.model.Customer;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Customer payment cards saved with Stripe.
 */
@Service
public class CardService {
    private static final Logger LOG = Logger.getLogger(CardService.class.getName());
    @Autowired
    CustomerRecords customerRecords;
    @Autowired
    CardDetailsRepo cardDetailsRepo;
    @Autowired
    StripeService stripeService;

    public BaseResponse getCardSetupIntent(String userId){
        BaseResponse response = new BaseResponse(true);
        try{
            if(!stripeService.isConfigured()){
                return Responses.errorResponse(response, "Payments are not configured yet");
            }
            Optional<UserEntity> isUserExist = customerRecords.findByUserId(userId);
            if(isUserExist.isEmpty()){
                return Responses.errorResponse(response, "Invalid User Id");
            }
            UserEntity user = isUserExist.get();
            Optional<CardDetailsEntity> existing = cardDetailsRepo.findByUserId(userId);
            String customerId = existing.filter(c -> c.getStripeCustomerId() != null)
                    .map(CardDetailsEntity::getStripeCustomerId).orElse(null);
            Customer customer = stripeService.getOrCreateCustomer(customerId, user.getEmailAddress(),
                    (user.getFirstname() + " " + user.getLastname()).trim());
            String clientSecret = stripeService.createSetupIntentClientSecret(customer.getId());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("clientSecret", clientSecret);
            data.put("stripeCustomerId", customer.getId());
            response.setData(data);
        } catch(Exception ex){
            LOG.warning("Setup intent failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not start card setup — please try again");
        }
        return response;
    }

    /**
     * Persists only the Stripe token references needed for a far-future booking
     * so the payment scheduler can authorize when the window opens. Never stores
     * the card itself (number/expiry/cvv) — only customer + payment-method ids.
     */
    public void upsertPaymentReference(String userId, String stripeCustomerId, String paymentMethodId){
        Optional<CardDetailsEntity> existing = cardDetailsRepo.findByUserId(userId);
        CardDetailsEntity entity = existing.orElseGet(CardDetailsEntity::new);
        entity.setUserId(userId);
        entity.setStripeCustomerId(stripeCustomerId);
        entity.setStripePaymentMethodId(paymentMethodId);
        cardDetailsRepo.save(entity);
    }

    /**
     * Persists only Stripe references and display metadata for a card collected
     * inside Stripe's Elements iframe. Raw card numbers, CVVs and expiry dates
     * are never sent to or stored by this application.
     */
    public BaseResponse updateUserCardDetails(CardDetailsData cardDetailsData){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<UserEntity> isUserExist = customerRecords.findByUserId(cardDetailsData.getUserId());
            if(isUserExist.isEmpty()){
                return Responses.errorResponse(response, "Invalid User Id");
            }
            if(!stripeService.isConfigured()){
                return Responses.errorResponse(response, "Payments are not configured yet");
            }
            UserEntity user = isUserExist.get();
            Optional<CardDetailsEntity> existing = cardDetailsRepo.findByUserId(cardDetailsData.getUserId());
            String customerId = existing.filter(c -> c.getStripeCustomerId() != null)
                    .map(CardDetailsEntity::getStripeCustomerId).orElse(null);
            Customer customer = stripeService.getOrCreateCustomer(customerId, user.getEmailAddress(),
                    (user.getFirstname() + " " + user.getLastname()).trim());
            StripeService.CardDisplay display = stripeService.attachPaymentMethod(customer.getId(),
                    cardDetailsData.getPaymentMethodId());

            CardDetailsEntity entity = existing.orElseGet(CardDetailsEntity::new);
            entity.setUserId(cardDetailsData.getUserId());
            entity.setCardName(cardDetailsData.getCardName().trim());
            entity.setStripeCustomerId(customer.getId());
            entity.setStripePaymentMethodId(cardDetailsData.getPaymentMethodId());
            entity.setLast4(display.last4);
            entity.setBrand(display.brand);
            entity.setExpMonth(display.expMonth);
            entity.setExpYear(display.expYear);
            cardDetailsRepo.save(entity);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(existing.isPresent() ? "Card details updated successfully" : "Card details added successfully");
            response.setData(EMPTY_DATA);
        } catch(Exception ex){
            LOG.warning("Card update failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not save your card — please try again");
        }
        return response;
    }
}

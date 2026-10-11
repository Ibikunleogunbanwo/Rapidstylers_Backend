package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import com.macrotel.rapidstylers.styler.service.StylerEligibility;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Stylist Stripe Connect accounts: onboarding links, account status and business summaries.
 */
@Service
public class ConnectAccountService {
    private static final Logger LOG = Logger.getLogger(ConnectAccountService.class.getName());
    @Autowired
    StylerRecords stylerRecords;
    @Autowired
    AppointmentRecords appointmentRecords;
    @Autowired
    StripeService stripeService;
    // Payment domain ops (carved out of this class; see simplification-plan.md).
    // Initialized inline so bare construction (unit tests) behaves as before;
    // Spring replaces both with the context beans via @Autowired.
    @Autowired
    PaymentService paymentService = new PaymentService();
    @Autowired
    StylerEligibility stylerEligibility;

    /**
     * Creates (or reuses) a Stripe Connect Express account for the stylist and
     * returns a hosted onboarding link. returnUrl/refreshUrl come from the
     * frontend (the stylist dashboard) and are where Stripe sends the stylist
     * after finishing or abandoning onboarding.
     */
    public BaseResponse createStylerConnectAccount(String stylerId, String returnUrl, String refreshUrl){
        BaseResponse response = new BaseResponse(true);
        try{
            if(!stripeService.isConfigured()){
                return Responses.errorResponse(response, "Payments are not configured yet");
            }
            Optional<StylerEntity> stylerOpt = stylerRecords.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                return Responses.errorResponse(response, "Invalid Styler Id");
            }
            StylerEntity styler = stylerOpt.get();
            String accountId = styler.getStripeConnectAccountId();
            if(accountId == null || accountId.isBlank()){
                Account account = stripeService.createExpressAccount(stylerId, styler.getEmailAddress(),
                        styler.getBusinessName(), styler.getFirstname(), styler.getLastname());
                accountId = account.getId();
                styler.setStripeConnectAccountId(accountId);
                styler.setConnectOnboardingStatus("PENDING");
                stylerRecords.save(styler);
            }
            AccountLink link = stripeService.createAccountLink(accountId, refreshUrl, returnUrl);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("onboardingUrl", link.getUrl());
            data.put("status", styler.getConnectOnboardingStatus());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch(Exception ex){
            LOG.warning("Connect account setup failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not start payout setup — please try again");
        }
        return response;
    }

    /**
     * Business summary for a stylist dashboard: real appointment, client,
     * revenue, and popular-service stats (replaces the hardcoded card).
     * Status map: 1 pending, 3 accepted, 0 completed, 2 rejected, 4 cancelled.
     */
    public BaseResponse getStylerBusinessSummary(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> stylerOpt = stylerRecords.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                return Responses.errorResponse(response, "Invalid Styler Id");
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(businessSummaryData(stylerOpt.get()));
        } catch(Exception ex){
            LOG.warning("Business summary failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not load business summary");
        }
        return response;
    }

    /**
     * Admin view: per-stylist business stats for every professional, most
     * appointments first. Reuses the same computation as the stylist dashboard.
     */
    public BaseResponse getAdminStylerBusinessSummaries(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<Map<String, Object>> rows = new ArrayList<>();
            for(StylerEntity styler : stylerRecords.findAll()){
                Map<String, Object> row = new LinkedHashMap<>(businessSummaryData(styler));
                row.put("stylerId", styler.getStylerId());
                row.put("businessName", styler.getBusinessName());
                row.put("name", (styler.getFirstname() + " " + styler.getLastname()).trim());
                row.put("emailAddress", styler.getEmailAddress());
                row.put("verificationStatus", styler.getVerificationStatus());
                rows.add(row);
            }
            rows.sort((a, b) -> Long.compare(((Number) b.get("totalAppointments")).longValue(),
                    ((Number) a.get("totalAppointments")).longValue()));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(rows);
        } catch(Exception ex){
            LOG.warning("Admin business summaries failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not load business summaries");
        }
        return response;
    }

    /**
     * Shared per-stylist stats computation. Revenue counts the captured payment
     * amount when the payment lifecycle is active (paymentStatus CAPTURED),
     * otherwise the completed appointment price. netRevenue is the stylist's
     * share after the platform commission, consistent with the payout page.
     */
    private Map<String, Object> businessSummaryData(StylerEntity styler){
        String stylerId = styler.getStylerId();
        List<BookAppointmentEntity> appointments = appointmentRecords.findByStylerId(stylerId);
        if(appointments == null) appointments = new ArrayList<>();

        long total = appointments.size();
        long pending = 0, confirmed = 0, finished = 0, cancelled = 0;
        Set<String> clients = new HashSet<>();
        BigDecimal grossRevenue = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        Map<String, Integer> serviceCounts = new LinkedHashMap<>();
        Map<String, String> serviceNames = new HashMap<>();
        for(SubServiceEntity service : stylerRecords.subServicesOf(stylerId)){
            serviceNames.put(String.valueOf(service.getId()), service.getName());
        }

        for(BookAppointmentEntity appointment : appointments){
            if("1".equals(appointment.getStatus())) pending++;
            else if("3".equals(appointment.getStatus())) confirmed++;
            else if("0".equals(appointment.getStatus())) finished++;
            else if("4".equals(appointment.getStatus())) cancelled++;
            if("2".equals(appointment.getStatus()) || "4".equals(appointment.getStatus())) continue;
            if(appointment.getUserId() != null) clients.add(appointment.getUserId());
            if("0".equals(appointment.getStatus())){
                boolean captured = "CAPTURED".equals(appointment.getPaymentStatus());
                grossRevenue = grossRevenue.add(MoneyUtils.amount(
                        captured && appointment.getPaymentAmount() != null
                                ? appointment.getPaymentAmount() : appointment.getPrice()));
                if(captured && appointment.getPaymentAmount() != null && appointment.getPromoDiscountCents() != null){
                    // The platform pays for promo discounts; the stylist's takings are the full price.
                    grossRevenue = grossRevenue.add(java.math.BigDecimal.valueOf(appointment.getPromoDiscountCents(), 2));
                }
            }
            if(appointment.getSubServiceId() != null){
                serviceCounts.merge(appointment.getSubServiceId(), 1, Integer::sum);
            }
        }

        List<Map<String, Object>> popularServices = new ArrayList<>();
        serviceCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", serviceNames.getOrDefault(entry.getKey(), "Service"));
                    row.put("count", entry.getValue());
                    popularServices.add(row);
                });

        BigDecimal commission = grossRevenue.multiply(BigDecimal.valueOf(paymentService.effectiveCommissionPercent()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal netRevenue = grossRevenue.subtract(commission);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalAppointments", total);
        data.put("clients", clients.size());
        data.put("pending", pending);
        data.put("confirmed", confirmed);
        data.put("finished", finished);
        data.put("cancelled", cancelled);
        data.put("totalRevenue", MoneyUtils.money(grossRevenue));
        // Listing health, so the dashboard can explain a profile that is not
        // reachable instead of leaving the professional to guess why nobody
        // books. `bookable` is the same gate public search uses.
        data.put("addressOnFile", StylerEligibility.hasStreetAddress(styler));
        data.put("bookable", stylerEligibility.isBookableStyler(styler));
        data.put("inServiceArea", stylerEligibility.isInServiceArea(styler));
        data.put("totalCommission", MoneyUtils.money(commission));
        data.put("netRevenue", MoneyUtils.money(netRevenue));
        data.put("popularServices", popularServices);
        return data;
    }

    public BaseResponse getStylerConnectStatus(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> stylerOpt = stylerRecords.findByStylerId(stylerId);
            if(stylerOpt.isEmpty()){
                return Responses.errorResponse(response, "Invalid Styler Id");
            }
            StylerEntity styler = stylerOpt.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", styler.getStripeConnectAccountId());
            data.put("status", styler.getConnectOnboardingStatus() == null
                    ? "NOT_STARTED" : styler.getConnectOnboardingStatus());
            data.put("disabledReason", styler.getConnectDisabledReason());
            // Earnings kept back until payout setup is done, for a "finish setup" banner.
            long waitingCents = 0;
            int waiting = 0;
            for (BookAppointmentEntity held : appointmentRecords
                    .findByStylerIdAndPayoutHoldReason(stylerId, PayoutReleaseService.HOLD_NO_PAYOUT_ACCOUNT)) {
                waiting++;
                waitingCents += held.getStylistShareCents() == null ? 0 : held.getStylistShareCents();
            }
            data.put("payoutsWaiting", waiting);
            data.put("payoutsWaitingAmount", MoneyUtils.moneyCents(waitingCents));
            data.put("canReceivePayouts", PayoutReleaseService.canReceivePayouts(styler));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch(Exception ex){
            LOG.warning("Connect status lookup failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not load payout status");
        }
        return response;
    }

    /**
     * Admin support view: every stylist's Connect payout status, ordered so
     * problems surface first (REJECTED, then PENDING, NOT_STARTED, COMPLETE).
     * Pure DB read — no Stripe calls, so it stays fast regardless of account count.
     */
    public BaseResponse getAdminStylerConnectStatuses(){
        BaseResponse response = new BaseResponse(true);
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for(StylerEntity styler : stylerRecords.findAll()){
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stylerId", styler.getStylerId());
                row.put("name", (styler.getFirstname() + " " + styler.getLastname()).trim());
                row.put("businessName", styler.getBusinessName());
                row.put("emailAddress", styler.getEmailAddress());
                row.put("verificationStatus", styler.getVerificationStatus() == null
                        ? "PENDING" : styler.getVerificationStatus());
                row.put("accountActive", "0".equals(styler.getStatus()));
                row.put("connectStatus", styler.getConnectOnboardingStatus() == null
                        ? "NOT_STARTED" : styler.getConnectOnboardingStatus());
                row.put("connectAccountId", styler.getStripeConnectAccountId());
                row.put("disabledReason", styler.getConnectDisabledReason());
                row.put("registered", styler.getInsertedDt());
                rows.add(row);
            }
            rows.sort((a, b) -> {
                int rankA = connectStatusRank((String) a.get("connectStatus"));
                int rankB = connectStatusRank((String) b.get("connectStatus"));
                int byStatus = Integer.compare(rankA, rankB);
                if(byStatus != 0) return byStatus;
                return String.valueOf(a.get("stylerId")).compareTo(String.valueOf(b.get("stylerId")));
            });
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(rows);
        } catch(Exception ex){
            LOG.warning("Connect status list failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not load stylist payout statuses");
        }
        return response;
    }

    /** Sort rank for the admin overview: rejected and stuck payouts first. */
    private int connectStatusRank(String status){
        switch(status == null ? "NOT_STARTED" : status){
            case "REJECTED": return 0;
            case "PENDING": return 1;
            case "NOT_STARTED": return 2;
            default: return 3;
        }
    }
}

package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.service.AppointmentRecords;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import com.stripe.model.Balance;
import com.stripe.net.RequestOptions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_MESSAGE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * The stylist's Payouts page: what each paid booking earned them after
 * commission, their payout setup status, and their Stripe balance.
 * Moved out of PaymentService unchanged, to keep that class under 800 lines.
 */
@Service
public class StylerPayoutSummary {

    private static final Logger LOG = Logger.getLogger(StylerPayoutSummary.class.getName());

    @Autowired
    StylerRecords stylerRecords;
    @Autowired
    AppointmentRecords appointmentRecords;
    @Autowired
    StripeService stripeService;
    @Autowired
    PaymentService paymentService;

    public BaseResponse getStylerPayouts(String stylerId) {
        BaseResponse response = new BaseResponse(true);
        try {
            Optional<StylerEntity> stylerOpt = stylerRecords.findByStylerId(stylerId);
            if (stylerOpt.isEmpty()) {
                return Responses.errorResponse(response, "Invalid Styler Id");
            }
            StylerEntity styler = stylerOpt.get();
            String accountId = styler.getStripeConnectAccountId();
            boolean connected = accountId != null && !accountId.isBlank();

            BigDecimal totalEarned = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalCommission = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            List<Map<String, Object>> appointments = new ArrayList<>();
            for (BookAppointmentEntity appointment : appointmentRecords.findByStylerId(stylerId)) {
                if (!"0".equals(appointment.getStatus())
                        || appointment.getPaymentIntentId() == null
                        || !"CAPTURED".equals(appointment.getPaymentStatus())) {
                    continue;
                }
                BigDecimal total = MoneyUtils.amount(appointment.getPaymentAmount() == null
                        ? appointment.getPrice() : appointment.getPaymentAmount());
                BigDecimal commission = total.multiply(BigDecimal.valueOf(paymentService.effectiveCommissionPercent()))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal share = total.subtract(commission);
                totalEarned = totalEarned.add(share);
                totalCommission = totalCommission.add(commission);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("appointmentId", appointment.getAppointmentId());
                row.put("date", appointment.getAppointmentDate());
                row.put("arrivalTime", appointment.getArrivalTime());
                // The zone the appointment's times are written in (the
                // stylist's), so the payouts table can label them.
                row.put("timeZone", styler.getTimeZone());
                row.put("total", MoneyUtils.money(total));
                row.put("commission", MoneyUtils.money(commission));
                row.put("stylerShare", MoneyUtils.money(share));
                appointments.add(row);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("connected", connected);
            data.put("status", connected && styler.getConnectOnboardingStatus() != null
                    ? styler.getConnectOnboardingStatus() : "NOT_STARTED");
            data.put("disabledReason", connected ? styler.getConnectDisabledReason() : null);
            data.put("totalEarned", MoneyUtils.money(totalEarned));
            data.put("totalCommission", MoneyUtils.money(totalCommission));
            data.put("stripeAvailable", "0.00");
            data.put("stripePending", "0.00");
            if (connected && stripeService.isConfigured()) {
                try {
                    Balance balance = Balance.retrieve(RequestOptions.builder().setStripeAccount(accountId).build());
                    data.put("stripeAvailable", MoneyUtils.moneyCents(sumBalanceAmounts(balance.getAvailable(), stripeService.currency())));
                    data.put("stripePending", MoneyUtils.moneyCents(sumPendingAmounts(balance.getPending(), stripeService.currency())));
                } catch (Exception ex) {
                    LOG.warning("Connected balance lookup failed: " + ex.getMessage());
                }
            }
            data.put("appointments", appointments);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(data);
        } catch (Exception ex) {
            LOG.warning("Payout summary failed: " + ex.getMessage());
            return Responses.errorResponse(response, "Could not load payout summary");
        }
        return response;
    }

    private long sumBalanceAmounts(java.util.List<Balance.Available> entries, String currency) {
        long total = 0L;
        for (Balance.Available entry : entries) {
            if (entry.getAmount() != null && (currency == null || currency.equals(entry.getCurrency()))) {
                total += entry.getAmount();
            }
        }
        return total;
    }

    private long sumPendingAmounts(java.util.List<Balance.Pending> entries, String currency) {
        long total = 0L;
        for (Balance.Pending entry : entries) {
            if (entry.getAmount() != null && (currency == null || currency.equals(entry.getCurrency()))) {
                total += entry.getAmount();
            }
        }
        return total;
    }
}

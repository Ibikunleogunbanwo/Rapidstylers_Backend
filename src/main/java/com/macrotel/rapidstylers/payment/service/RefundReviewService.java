package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.payment.entity.RefundEntity;
import com.macrotel.rapidstylers.payment.repo.RefundRepo;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * What an admin needs in front of them to decide on a refund: the booking, who
 * it was between, what was charged, what has already been returned, what can
 * still be returned, and whether the stylist has been paid out.
 *
 * <p>Read only. Issuing a refund stays in {@link PaymentService#adminRefund}.
 */
@Service
public class RefundReviewService {

    private static final Logger LOG = Logger.getLogger(RefundReviewService.class.getName());
    /** Payment states in which the customer's card was actually charged. */
    private static final java.util.Set<String> CHARGED = java.util.Set.of("CAPTURED", "REFUNDED", "DISPUTE_LOST");

    @Autowired
    PaymentService paymentService;
    @Autowired
    RefundRepo refundRepo;
    @Autowired
    BookAppointmentRepo bookAppointmentRepo;
    @Autowired
    UserRepo userRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    SubServiceRepo subServiceRepo;

    /** The payment behind one booking, for the admin about to refund it. */
    public BaseResponse paymentContext(String appointmentId) {
        BaseResponse response = new BaseResponse(true);
        if (appointmentId == null || appointmentId.isBlank()) {
            return Responses.errorResponse(response, "appointmentId is required");
        }
        Optional<BookAppointmentEntity> appointment = bookAppointmentRepo.findByAppointmentId(appointmentId.trim());
        if (appointment.isEmpty()) {
            return Responses.errorResponse(response, "Invalid Appointment Id");
        }
        Map<String, Object> context = payment(appointment.get());
        context.put("refunds", refundRepo.findByAppointmentId(appointment.get().getAppointmentId()));
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage("Payment details retrieved");
        response.setData(context);
        return response;
    }

    /**
     * Every refund, newest first. Each row keeps the refund's own fields and adds
     * a "payment" block describing the booking payment it came out of.
     */
    public BaseResponse refundsWithPayments() {
        BaseResponse response = new BaseResponse(true);
        try {
            List<RefundEntity> refunds = refundRepo.findAll();
            refunds.sort(Comparator.comparing(RefundEntity::getCreatedAt,
                    Comparator.nullsLast(String::compareTo)).reversed());
            Map<String, Map<String, Object>> payments = new HashMap<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RefundEntity refund : refunds) {
                Map<String, Object> row = refundFields(refund);
                row.put("payment", payments.computeIfAbsent(refund.getAppointmentId(),
                        id -> bookAppointmentRepo.findByAppointmentId(id).map(this::payment).orElse(null)));
                rows.add(row);
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Refunds retrieved");
            response.setData(rows);
        } catch (Exception ex) {
            LOG.warning("Admin refund list error: " + ex.getMessage());
        }
        return response;
    }

    private Map<String, Object> payment(BookAppointmentEntity appointment) {
        long captured = paymentService.capturedCents(appointment);
        long refunded = paymentService.refundedCents(appointment.getPaymentIntentId());
        boolean refundable = "CAPTURED".equals(appointment.getPaymentStatus()) && captured > refunded;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appointmentId", appointment.getAppointmentId());
        data.put("appointmentStatus", appointment.getStatus());
        data.put("appointmentStatusLabel", statusLabel(appointment.getStatus()));
        data.put("appointmentDate", appointment.getAppointmentDate());
        data.put("arrivalTime", appointment.getArrivalTime());
        data.put("serviceName", serviceName(appointment.getSubServiceId()));
        data.put("bookedAt", appointment.getCreatedAt());
        data.put("completedAt", appointment.getCompletedAt() == null ? null : appointment.getCompletedAt().toString());
        data.put("stylistNote", appointment.getStylerNote());

        data.put("customerId", appointment.getUserId());
        userRepo.findByUserId(appointment.getUserId()).ifPresent(user -> {
            data.put("customerName", fullName(user.getFirstname(), user.getLastname()));
            data.put("customerEmail", user.getEmailAddress());
        });
        data.put("stylistId", appointment.getStylerId());
        stylerRepo.findByStylerId(appointment.getStylerId()).ifPresent(styler -> {
            data.put("stylistName", fullName(styler.getFirstname(), styler.getLastname()));
            data.put("businessName", styler.getBusinessName());
        });

        data.put("paymentIntentId", appointment.getPaymentIntentId());
        data.put("paymentStatus", appointment.getPaymentStatus());
        data.put("servicePrice", appointment.getServicePrice());
        data.put("travelFee", appointment.getTravelFee());
        data.put("capturedAmount", CHARGED.contains(appointment.getPaymentStatus())
                ? MoneyUtils.moneyCents(captured) : "0.00");
        data.put("refundedTotal", MoneyUtils.moneyCents(refunded));
        data.put("refundableRemaining", refundable ? MoneyUtils.moneyCents(captured - refunded) : "0.00");
        data.put("refundable", refundable);
        data.put("commissionPercent", appointment.getCommissionPercent());
        data.put("platformFee", appointment.getPlatformFeeCents() == null ? null
                : MoneyUtils.moneyCents(appointment.getPlatformFeeCents()));
        data.put("stylistShare", appointment.getStylistShareCents() == null ? null
                : MoneyUtils.moneyCents(appointment.getStylistShareCents()));
        // A transfer means the stylist has been paid their share. An admin refund
        // does not take it back, so the platform carries that part of the refund.
        boolean paidOut = appointment.getStripeTransferId() != null && !appointment.getStripeTransferId().isBlank();
        data.put("stylistPaidOut", paidOut);
        data.put("stripeTransferId", appointment.getStripeTransferId());
        return data;
    }

    private static Map<String, Object> refundFields(RefundEntity refund) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", refund.getId());
        row.put("refundId", refund.getRefundId());
        row.put("appointmentId", refund.getAppointmentId());
        row.put("paymentIntentId", refund.getPaymentIntentId());
        row.put("amount", refund.getAmount());
        row.put("reason", refund.getReason());
        row.put("status", refund.getStatus());
        row.put("stripeRefundId", refund.getStripeRefundId());
        row.put("failureCode", refund.getFailureCode());
        row.put("createdBy", refund.getCreatedBy());
        row.put("createdAt", refund.getCreatedAt());
        row.put("completedAt", refund.getCompletedAt());
        return row;
    }

    private String serviceName(String subServiceId) {
        try {
            return subServiceId == null ? null
                    : subServiceRepo.findById(Long.valueOf(subServiceId)).map(SubServiceEntity::getName).orElse(null);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String statusLabel(String status) {
        if (status == null) return "UNKNOWN";
        switch (status) {
            case "1": return "PENDING";
            case "3": return "CONFIRMED";
            case "0": return "COMPLETED";
            case "2": return "REJECTED";
            case "4": return "CANCELLED";
            default: return "UNKNOWN";
        }
    }

    private static String fullName(String first, String last) {
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }
}

package com.macrotel.rapidstylers.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashMap;

@Data
public class AppointmentDTO {
    private UserAccountDTO userData;
    private StylerAccountDTO stylerData;
    private SubServiceDTO subServiceData;
    private String appointmentDate;
    private String arrivalTime;
    private Integer durationMinutes;
    private String serviceTime;
    private String noOfPeople;
    private String servicePrice;
    private String travelFee;
    private Double includedTravelKm;
    private Double travelDistanceKm;
    private Double billableTravelKm;
    private String extraTravelRatePerKm;
    private String price;
    private String status;
    /** Raw status code ('1' pending, '3' accepted, '2' rejected, '0' completed, '4' cancelled) so the UI can render actions. */
    private String statusCode;
    private String appointmentId;
    private String createdAt;
    private String paymentStatus;
    private String paymentFailureCode;
    private String stripeTransferId;
    /** When the appointment was marked completed — lets the UI gate the styler cancel action. */
    private LocalDateTime completedAt;
    /** Completed refund for this appointment, when one exists — shown to customers. */
    private String refundId;
    private String refundStatus;
    private String refundAmount;
    private String refundCompletedAt;
}

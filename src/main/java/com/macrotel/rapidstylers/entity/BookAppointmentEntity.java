package com.macrotel.rapidstylers.entity;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.pojo.BookAppointmentData;
import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

@Data
@Entity
@Table(name = "appointments")
public class BookAppointmentEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userId;
    private String stylerId;
    private String subServiceId;
    // Legacy display fields retained for API compatibility.
    private String appointmentDate;
    private String arrivalTime;
    // Canonical fields used for ordering, validation, and overlap queries.
    @Column(name = "appointment_date_value")
    private LocalDate appointmentDateValue;
    @Column(name = "appointment_start_time")
    private LocalTime appointmentStartTime;
    @Column(name = "appointment_end_time")
    private LocalTime appointmentEndTime;
    @Column(name = "duration_minutes")
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
    private String appointmentId;
    private String createdAt;
    // Stripe payment: the authorized PaymentIntent id and its lifecycle state.
    // paymentStatus is null when payments are disabled (no STRIPE_SECRET_KEY).
    private String paymentIntentId;
    private String paymentStatus;
    private String paymentAmount;
    // Payment is authorized only inside the configured Stripe-safe window.
    private LocalDateTime paymentAuthorizationDueAt;
    private String paymentFailureCode;
    private String stripeTransferId;
    /** When the appointment was marked completed (status "0") — gates the styler cancel window. */
    private LocalDateTime completedAt;

    public BookAppointmentEntity() {
    }

    public BookAppointmentEntity(BookAppointmentData bookAppointmentData) {
        this(bookAppointmentData, com.macrotel.rapidstylers.config.AppConstants.DEFAULT_SERVICE_DURATION_MINUTES);
    }

    public BookAppointmentEntity(BookAppointmentData bookAppointmentData, int durationMinutes) {
        AppUtils appUtils = new AppUtils();
        this.userId  = bookAppointmentData.getUserId();
        this.stylerId = bookAppointmentData.getStylerId();
        this.subServiceId = bookAppointmentData.getSubServiceId();
        this.appointmentDate = bookAppointmentData.getAppointmentDate();
        this.arrivalTime = bookAppointmentData.getArrivalTime();
        this.appointmentDateValue = LocalDate.parse(bookAppointmentData.getAppointmentDate());
        this.appointmentStartTime = parseArrivalTime(bookAppointmentData.getArrivalTime());
        this.durationMinutes = durationMinutes;
        this.appointmentEndTime = this.appointmentStartTime.plusMinutes(durationMinutes);
        this.serviceTime = bookAppointmentData.getServiceTime();
        this.noOfPeople = bookAppointmentData.getNoOfPeople();
        this.price = appUtils.currencyFormat(bookAppointmentData.getPrice());
        this.servicePrice = appUtils.currencyFormat(bookAppointmentData.getPrice());
        this.travelFee = "0.00";
        this.includedTravelKm = 15.0;
        this.travelDistanceKm = 0.0;
        this.billableTravelKm = 0.0;
        this.extraTravelRatePerKm = "0.00";
        this.status = "1";
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss"));
        this.appointmentId = appUtils.randomAlphanumeric(5);
    }

    /**
     * Canonical arrival time is 24-hour HH:mm (aligned with the availability
     * API). The 12-hour h:mm a fallback only tolerates legacy rows written
     * before the format alignment; new bookings are always 24-hour.
     */
    private static LocalTime parseArrivalTime(String value) {
        String normalized = value.trim().toUpperCase();
        try {
            return LocalTime.parse(normalized, DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH));
        } catch (DateTimeParseException ex) {
            try {
                return LocalTime.parse(normalized, DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));
            } catch (DateTimeParseException legacy) {
                throw new IllegalArgumentException("Invalid appointment arrival time", legacy);
            }
        }
    }
}

package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

@Data
public class BookAppointmentData {
    @NotEmpty(message = "Styler Id cannot be empty")
    private String stylerId;
    // userId is derived from the JWT subject by the controller — never from the client.
    private String userId;
    @NotEmpty(message = "Appointment Date cannot be empty")
    @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$", message = "Appointment date must be in YYYY-MM-DD format")
    private String appointmentDate;
    // Price is derived server-side from subServiceId; retained only for legacy clients.
    @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$", message = "Price must be a valid amount")
    private String price;
    @NotEmpty(message = "Arrival Time cannot be empty")
    // Canonical 24-hour format, aligned with the availability API (HH:mm).
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Arrival time must be in HH:mm 24-hour format (e.g. 09:30)")
    private String arrivalTime;
    @NotEmpty(message = "No of People cannot be empty")
    private String noOfPeople;
    @NotEmpty(message = "Sub Service Id cannot be empty")
    @Pattern(regexp = "^\\d+$", message = "Sub Service Id must be numeric")
    private String subServiceId;
    private String serviceTime;
    private Double travelDistanceKm;
    /** Optional client-generated key used to safely retry a booking request. */
    private String idempotencyKey;
    /**
     * One-time Stripe payment method (pm_...) collected by the booking modal
     * via Stripe Elements. Used to authorize near-term bookings immediately;
     * persisted as a token reference (never the card itself) so the scheduler
     * can authorize far-future bookings when the window opens. Optional for
     * legacy/preview calls.
     */
    private String paymentMethodId;

}

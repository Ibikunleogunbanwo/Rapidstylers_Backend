package com.macrotel.rapidstylers.booking.pojo;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import lombok.Data;

/** A customer saying a completed appointment didn't happen, or an admin closing that. */
@Data
public class ServiceDisputeData {
    @NotEmpty(message = "Appointment Id cannot be empty")
    private String appointmentId;
    /** The customer's account of what went wrong. */
    @Size(max = 1000, message = "Keep it under 1000 characters")
    private String reason;
    /** The admin's note when resolving. */
    @Size(max = 1000, message = "Keep it under 1000 characters")
    private String note;
}

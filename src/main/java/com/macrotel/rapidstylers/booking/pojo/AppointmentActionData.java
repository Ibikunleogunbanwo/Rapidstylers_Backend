package com.macrotel.rapidstylers.booking.pojo;

import javax.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class AppointmentActionData {
    @NotEmpty(message = "Appointment Id cannot be empty")
    private String appointmentId;
    /** Optional stylist note recorded at accept/decline for later radius/preference review. */
    private String decisionNote;
}

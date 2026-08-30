package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class AppointmentActionData {
    @NotEmpty(message = "Appointment Id cannot be empty")
    private String appointmentId;
    /** Optional stylist note recorded at accept/decline for later radius/preference review. */
    private String decisionNote;
}

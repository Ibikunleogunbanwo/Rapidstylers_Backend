package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class AppointmentActionData {
    @NotEmpty(message = "Appointment Id cannot be empty")
    private String appointmentId;
}

package com.macrotel.rapidstylers.dto;

import lombok.Data;

import java.util.HashMap;

@Data
public class AppointmentDTO {
    private UserAccountDTO userData;
    private StylerAccountDTO stylerData;
    private SubServiceDTO subServiceData;
    private String appointmentDate;
    private String arrivalTime;
    private String serviceTime;
    private String noOfPeople;
    private String price;
    private String status;
    private String appointmentId;
    private String createdAt;
}

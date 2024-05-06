package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class BookAppointmentData {
    @NotEmpty(message = "Styler Id cannot be empty")
    private String stylerId;
    @NotEmpty(message = "User Id cannot be empty")
    private String userId;
    @NotEmpty(message = "Appointment Data cannot be empty")
    private String appointmentDate;
    @NotEmpty(message = "Price cannot be empty")
    private String price;
    @NotEmpty(message = "Arrival Time cannot be empty")
    private String arrivalTime;
    @NotEmpty(message = "No of People cannot be empty")
    private String noOfPeople;
    private String subServiceId;
    private String serviceTime;

}

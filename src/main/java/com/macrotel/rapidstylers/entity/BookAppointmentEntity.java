package com.macrotel.rapidstylers.entity;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.pojo.BookAppointmentData;
import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
    private String appointmentDate;
    private String arrivalTime;
    private String serviceTime;
    private String noOfPeople;
    private String price;
    private String status;
    private String appointmentId;
    private String createdAt;

    public BookAppointmentEntity() {
    }

    public BookAppointmentEntity(BookAppointmentData bookAppointmentData) {
        AppUtils appUtils = new AppUtils();
        this.userId  = bookAppointmentData.getUserId();
        this.stylerId = bookAppointmentData.getStylerId();
        this.subServiceId = bookAppointmentData.getSubServiceId();
        this.appointmentDate = bookAppointmentData.getAppointmentDate();
        this.arrivalTime = bookAppointmentData.getArrivalTime();
        this.serviceTime = bookAppointmentData.getServiceTime();
        this.noOfPeople = bookAppointmentData.getNoOfPeople();
        this.price = appUtils.currencyFormat(bookAppointmentData.getPrice());
        this.status = "1";
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        this.appointmentId = appUtils.randomAlphanumeric(5);
    }
}

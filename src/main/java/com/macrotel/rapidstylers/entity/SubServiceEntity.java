package com.macrotel.rapidstylers.entity;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.pojo.SubServiceData;
import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "sub_services")
public class SubServiceEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String stylerId;
    private String name;
    private String price;
    @Column(name = "duration_minutes")
    private Integer durationMinutes;
    private String status;
    private String createdAt;

    public SubServiceEntity() {
    }

    public SubServiceEntity(SubServiceData subServiceData){
        AppUtils appUtils = new AppUtils();
        this.stylerId = subServiceData.getStylerId();
        this.name = AppUtils.sanitizeText(subServiceData.getName());
        this.price =  appUtils.currencyFormat(subServiceData.getPrice());
        this.durationMinutes = subServiceData.getDurationMinutes() == null
                ? com.macrotel.rapidstylers.config.AppConstants.DEFAULT_SERVICE_DURATION_MINUTES
                : subServiceData.getDurationMinutes();
        this.status= "0";
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss"));
    }
}

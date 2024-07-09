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
    private String status;
    private String createdAt;

    public SubServiceEntity() {
    }

    public SubServiceEntity(SubServiceData subServiceData){
        AppUtils appUtils = new AppUtils();
        this.stylerId = subServiceData.getStylerId();
        this.name =subServiceData.getName();
        this.price =  appUtils.currencyFormat(subServiceData.getPrice());
        this.status= "0";
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss"));
    }
}

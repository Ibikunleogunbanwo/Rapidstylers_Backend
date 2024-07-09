package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "service_types")
public class ServiceEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String serviceName;
    private String insertedDt;
    private String status;
    private String serviceImageUrl;
    private String description;

    public ServiceEntity() {
        this.status = "0";
        this.insertedDt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy"));
    }
}

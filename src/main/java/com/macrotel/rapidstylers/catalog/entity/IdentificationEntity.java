package com.macrotel.rapidstylers.catalog.entity;

import java.io.Serializable;
import java.time.LocalDate;
import javax.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "identifications")
public class IdentificationEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String identificationName;
    private String status;
    private String insertedDate;

    public IdentificationEntity() {
        this.insertedDate = String.valueOf(LocalDate.now());
        this.status= "0";
    }
}

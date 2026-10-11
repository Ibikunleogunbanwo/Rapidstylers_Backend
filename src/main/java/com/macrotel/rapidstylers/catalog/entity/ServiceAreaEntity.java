package com.macrotel.rapidstylers.catalog.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** One province (city null) or one city the platform serves. See {@code ServiceAreaService}. */
@Data
@Entity
@Table(name = "service_areas")
public class ServiceAreaEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** Full province name as stylists' profiles write it, e.g. "Alberta". */
    @Column(nullable = false, length = 60)
    private String province;
    /** Null: the whole province. */
    @Column(length = 100)
    private String city;
    /** "province|city" in lower case; unique, so an area cannot be added twice. */
    @Column(name = "area_key", nullable = false, length = 170, unique = true)
    private String areaKey;
    @Column(nullable = false)
    private boolean active = true;
    @Column(length = 500)
    private String note;
    @Column(name = "updated_by", length = 100)
    private String updatedBy;
    /** UTC. */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

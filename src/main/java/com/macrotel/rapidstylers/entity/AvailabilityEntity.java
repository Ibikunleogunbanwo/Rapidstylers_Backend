package com.macrotel.rapidstylers.entity;

import com.macrotel.rapidstylers.pojo.AvailabilityData;
import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "availability")
public class AvailabilityEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String stylerId;
    // Day of week as 0 (Sunday) .. 6 (Saturday) — matches JS Date#getDay()
    private String dayOfWeek;
    private String startTime; // "HH:mm" 24h
    private String endTime;   // "HH:mm" 24h
    private String createdAt;

    public AvailabilityEntity() {
    }

    public AvailabilityEntity(String stylerId, AvailabilityData data) {
        this.stylerId = stylerId;
        this.dayOfWeek = data.getDayOfWeek();
        this.startTime = data.getStartTime();
        this.endTime = data.getEndTime();
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss"));
    }
}

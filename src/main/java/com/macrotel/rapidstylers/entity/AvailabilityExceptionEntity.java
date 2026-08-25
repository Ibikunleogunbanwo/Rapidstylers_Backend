package com.macrotel.rapidstylers.entity;

import com.macrotel.rapidstylers.pojo.ExceptionData;
import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "availability_exceptions")
public class AvailabilityExceptionEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String stylerId;
    // Specific date blocked: "YYYY-MM-DD" (e.g. "2026-09-15")
    private String blockedDate;
    // Optional reason: "Vacation", "Sick day", "Personal", etc.
    private String reason;
    private String createdAt;

    public AvailabilityExceptionEntity() {
    }

    public AvailabilityExceptionEntity(String stylerId, ExceptionData data) {
        this.stylerId = stylerId;
        this.blockedDate = data.getBlockedDate();
        this.reason = data.getReason() != null ? data.getReason() : "";
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss"));
    }
}

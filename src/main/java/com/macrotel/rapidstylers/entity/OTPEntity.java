package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "otp_codes")
public class OTPEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String emailAddress;
    private String code;
    private String purpose;
    private String insertedDt;
    private String isUsed;
    // Abandoned-signup recovery milestone already emailed for this attempt:
    // 0 = none, 1 = 24h reminder, 2 = 7d, 3 = 14d, 4 = 1 month. New attempts
    // start at 0, so a fresh sign-up can be followed up afresh.
    private Integer followupStage;
    // When the latest recovery email (followupStage) was sent, for the admin funnel view.
    private LocalDateTime followupUpdatedAt;

    public OTPEntity() {
        this.insertedDt = String.valueOf(LocalDateTime.now().format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")));
        this.isUsed = "1";
    }
}

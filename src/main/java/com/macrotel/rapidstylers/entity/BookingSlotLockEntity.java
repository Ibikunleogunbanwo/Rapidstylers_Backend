package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Entity
@Table(
        name = "booking_slot_locks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_booking_slot_styler_date_start",
                columnNames = {"styler_id", "appointment_date", "slot_start"}
        )
)
public class BookingSlotLockEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "styler_id", nullable = false)
    private String stylerId;
    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;
    @Column(name = "slot_start", nullable = false)
    private LocalTime slotStart;
    @Column(name = "appointment_id", nullable = false)
    private String appointmentId;

    public BookingSlotLockEntity() {
    }

    public BookingSlotLockEntity(String stylerId, LocalDate appointmentDate, LocalTime slotStart, String appointmentId) {
        this.stylerId = stylerId;
        this.appointmentDate = appointmentDate;
        this.slotStart = slotStart;
        this.appointmentId = appointmentId;
    }
}

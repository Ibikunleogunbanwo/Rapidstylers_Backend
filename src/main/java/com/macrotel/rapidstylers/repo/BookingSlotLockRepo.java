package com.macrotel.rapidstylers.repo;

import com.macrotel.rapidstylers.entity.BookingSlotLockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import javax.transaction.Transactional;
import java.time.LocalDate;
import java.time.LocalTime;

@Repository
public interface BookingSlotLockRepo extends JpaRepository<BookingSlotLockEntity, Long> {
    @Transactional
    void deleteByAppointmentId(String appointmentId);

    boolean existsByStylerIdAndAppointmentDateAndSlotStart(
            String stylerId, LocalDate appointmentDate, LocalTime slotStart);
}

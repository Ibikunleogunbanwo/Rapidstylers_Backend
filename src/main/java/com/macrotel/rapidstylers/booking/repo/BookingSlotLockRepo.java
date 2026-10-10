package com.macrotel.rapidstylers.booking.repo;

import com.macrotel.rapidstylers.booking.entity.BookingSlotLockEntity;
import java.time.LocalDate;
import java.time.LocalTime;
import javax.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingSlotLockRepo extends JpaRepository<BookingSlotLockEntity, Long> {
    @Transactional
    void deleteByAppointmentId(String appointmentId);

    boolean existsByStylerIdAndAppointmentDateAndSlotStart(
            String stylerId, LocalDate appointmentDate, LocalTime slotStart);
}
